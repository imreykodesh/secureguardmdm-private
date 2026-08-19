package com.secureguard.mdm.ministore.data

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import com.secureguard.mdm.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection

@Singleton
class MiniStoreCatalogClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson,
    private val preferences: MiniStorePreferences,
) {
    private val fetchMutex = Mutex()

    suspend fun fetchVerifiedCatalog(): MiniStoreCatalogPayload = fetchMutex.withLock {
        val envelopeBytes = downloadCatalog()
        val envelope = gson.fromJson(String(envelopeBytes, StandardCharsets.UTF_8), MiniStoreCatalogEnvelope::class.java)
            ?: error("Catalog envelope is empty")
        val publicKeyConfig = context.resources.openRawResource(R.raw.secureguard_mini_store_public_key)
            .bufferedReader().use { gson.fromJson(it, PublicKeyConfig::class.java) }

        require(envelope.schemaVersion == SCHEMA_VERSION) { "Unsupported catalog envelope" }
        require(envelope.algorithm == "Ed25519") { "Unsupported catalog signature algorithm" }
        require(envelope.keyId == publicKeyConfig.keyId) { "Unknown catalog signing key" }
        require(publicKeyConfig.schemaVersion == 1 && publicKeyConfig.algorithm == "Ed25519") { "Invalid embedded trust root" }

        val payloadBytes = Base64.decode(envelope.payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val signatureBytes = Base64.decode(envelope.signature, Base64.DEFAULT)
        val publicKeyBytes = Base64.decode(publicKeyConfig.publicKeySpkiBase64, Base64.DEFAULT)
        require(verifyEd25519(publicKeyBytes, signatureBytes, payloadBytes)) {
            "Catalog signature verification failed"
        }

        val payload = gson.fromJson(String(payloadBytes, StandardCharsets.UTF_8), MiniStoreCatalogPayload::class.java)
            ?: error("Catalog payload is empty")
        validatePayload(payload)
        val payloadSha256 = MessageDigest.getInstance("SHA-256").digest(payloadBytes)
            .joinToString("") { "%02x".format(it) }
        preferences.acceptCatalog(payload.revision, payloadSha256)
        payload
    }

    private fun downloadCatalog(): ByteArray {
        val connection = URL(CATALOG_URL).openConnection() as HttpsURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            require(connection.responseCode == HttpsURLConnection.HTTP_OK) {
                "Catalog server returned HTTP ${connection.responseCode}"
            }
            val declaredLength = connection.contentLengthLong
            require(declaredLength in -1..MAX_CATALOG_BYTES.toLong()) { "Catalog is too large" }
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_CATALOG_BYTES) { "Catalog is too large" }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun validatePayload(payload: MiniStoreCatalogPayload) {
        require(payload.schemaVersion == SCHEMA_VERSION) { "Unsupported catalog payload" }
        require(payload.revision > 0) { "Invalid catalog revision" }
        require(payload.apps.size <= 500) { "Catalog contains too many apps" }
        val packages = HashSet<String>()
        payload.apps.forEach { app ->
            require(PACKAGE_REGEX.matches(app.packageName)) { "Invalid package in catalog" }
            require(app.packageName != context.packageName) { "A Bloq cannot appear in the mini-store catalog" }
            require(packages.add(app.packageName)) { "Duplicate package in catalog" }
            require(app.versionCode.toLongOrNull()?.let { it > 0 } == true) { "Invalid version code" }
            require(app.displayName.isNotBlank() && app.displayName.length <= 120) { "Invalid display name" }
            require(app.versionName.isNotBlank() && app.versionName.length <= 100) { "Invalid version name" }
            require(app.minSdk == null || app.minSdk in 1..100) { "Invalid minimum SDK" }
            require(app.apkSize > 0) { "Invalid APK size" }
            require(SHA256_REGEX.matches(app.apkSha256)) { "Invalid APK hash" }
            require(SHA256_REGEX.matches(app.apkSignerSha256)) { "Invalid APK signer" }
            val expectedPrefix = "$DOWNLOAD_PREFIX${app.packageName}/${app.versionCode}/"
            require(app.downloadUrl.startsWith(expectedPrefix) && app.downloadUrl.endsWith("/${app.apkSha256}.apk")) {
                "APK URL is not immutable or is outside the managed host"
            }
            require('?' !in app.downloadUrl && '#' !in app.downloadUrl && ".." !in app.downloadUrl) { "Unsafe APK URL" }
        }
    }

    private fun verifyEd25519(
        publicKeyBytes: ByteArray,
        signatureBytes: ByteArray,
        payloadBytes: ByteArray,
    ): Boolean {
        require(publicKeyBytes.size == ED25519_SPKI_PREFIX.size + ED25519_PUBLIC_KEY_BYTES) {
            "Invalid Ed25519 public key length"
        }
        require(
            publicKeyBytes.copyOfRange(0, ED25519_SPKI_PREFIX.size)
                .contentEquals(ED25519_SPKI_PREFIX),
        ) { "Invalid Ed25519 public key encoding" }
        require(signatureBytes.size == ED25519_SIGNATURE_BYTES) {
            "Invalid Ed25519 signature length"
        }
        val rawPublicKey = publicKeyBytes.copyOfRange(
            ED25519_SPKI_PREFIX.size,
            publicKeyBytes.size,
        )
        return Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(rawPublicKey, 0))
            val domain = SIGNATURE_DOMAIN.toByteArray(StandardCharsets.UTF_8)
            update(domain, 0, domain.size)
            update(payloadBytes, 0, payloadBytes.size)
            verifySignature(signatureBytes)
        }
    }

    private data class PublicKeyConfig(
        val schemaVersion: Int,
        val algorithm: String,
        val keyId: String,
        val publicKeySpkiBase64: String,
    )

    companion object {
        const val CATALOG_URL = "https://imreykodesh.com/downloads/secureguard-mini-store/catalog.json"
        private const val DOWNLOAD_PREFIX = "https://downloads.imreykodesh.com/downloads/secureguard-mini-store/"
        private const val SIGNATURE_DOMAIN = "secureguard-mini-store-catalog/v1\n"
        private const val SCHEMA_VERSION = 1
        private const val MAX_CATALOG_BYTES = 2 * 1024 * 1024
        private const val ED25519_PUBLIC_KEY_BYTES = 32
        private const val ED25519_SIGNATURE_BYTES = 64
        private val ED25519_SPKI_PREFIX = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        )
        private val PACKAGE_REGEX = Regex("^[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+$")
        private val SHA256_REGEX = Regex("^[a-f0-9]{64}$")
    }
}

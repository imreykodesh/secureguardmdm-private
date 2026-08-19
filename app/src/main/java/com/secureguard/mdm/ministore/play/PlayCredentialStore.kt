package com.secureguard.mdm.ministore.play

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.aurora.gplayapi.data.models.AuthData
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Singleton
class PlayCredentialStore @Inject constructor(
    @ApplicationContext context: Context,
    private val audit: PlaySessionAudit,
) {
    private val authFile = AtomicFile(context.noBackupFilesDir.resolve(FILE_NAME))
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Reads the stored session.
     *
     * Two earlier behaviours here could destroy a working session without
     * leaving a trace, and both are now handled explicitly:
     *
     * - A missing Keystore key used to be re-created on read. The new key could
     *   never decrypt data written with the old one, so the read failed and the
     *   session file was deleted. The key is now only looked up on read.
     * - Any failure at all used to delete the file. A transient Keystore or I/O
     *   fault therefore looked identical to real corruption. Only an
     *   unrecoverable failure clears the file now; anything else keeps it for the
     *   next attempt and is recorded.
     */
    @Synchronized
    fun load(scope: String): AuthData? {
        if (!authFile.baseFile.exists()) {
            audit.record(PlaySessionEvent.SESSION_ABSENT, "no stored session file")
            return null
        }

        val key = runCatching { existingKey() }.getOrElse { error ->
            audit.record(
                PlaySessionEvent.SESSION_LOAD_TRANSIENT_KEPT,
                "keystore unavailable: ${describe(error)}",
            )
            return null
        }

        if (key == null) {
            // The ciphertext can never be read again, so keeping it would only
            // hide the cause of the next sign-in prompt.
            clear()
            audit.record(
                PlaySessionEvent.KEYSTORE_KEY_MISSING_CLEARED,
                "keystore alias missing; stored session is unrecoverable",
            )
            return null
        }

        return runCatching {
            val encoded = authFile.openRead().use { it.readBytes() }
            require(encoded.size > HEADER_BYTES + MIN_GCM_TAG_BYTES) { "Stored Play session is truncated" }
            val buffer = ByteBuffer.wrap(encoded)
            require(buffer.get() == FORMAT_VERSION) { "Unsupported Play session format" }
            val ivSize = buffer.get().toInt() and 0xff
            require(ivSize in 12..32 && buffer.remaining() > ivSize) { "Invalid Play session IV" }
            val iv = ByteArray(ivSize).also(buffer::get)
            val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                updateAAD(associatedData(scope))
            }
            val plaintext = cipher.doFinal(encrypted).toString(StandardCharsets.UTF_8)
            json.decodeFromString(AuthData.serializer(), plaintext)
        }.onSuccess {
            audit.record(PlaySessionEvent.SESSION_LOADED, "stored session decrypted")
        }.getOrElse { error ->
            if (isUnrecoverable(error)) {
                clear()
                audit.record(
                    PlaySessionEvent.SESSION_UNREADABLE_CLEARED,
                    "unrecoverable: ${describe(error)}",
                )
            } else {
                audit.record(
                    PlaySessionEvent.SESSION_LOAD_TRANSIENT_KEPT,
                    "kept for retry: ${describe(error)}",
                )
            }
            null
        }
    }

    @Synchronized
    fun save(scope: String, authData: AuthData) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            updateAAD(associatedData(scope))
        }
        val plaintext = json.encodeToString(AuthData.serializer(), authData)
            .toByteArray(StandardCharsets.UTF_8)
        val encrypted = cipher.doFinal(plaintext)
        val outputBytes = ByteArrayOutputStream(2 + cipher.iv.size + encrypted.size).apply {
            write(FORMAT_VERSION.toInt())
            write(cipher.iv.size)
            write(cipher.iv)
            write(encrypted)
        }.toByteArray()

        val output = authFile.startWrite()
        try {
            output.write(outputBytes)
            output.fd.sync()
            authFile.finishWrite(output)
        } catch (error: Exception) {
            authFile.failWrite(output)
            throw error
        }
    }

    @Synchronized
    fun clear() {
        authFile.delete()
    }

    /** Read path: looks the key up without ever creating a replacement. */
    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun isUnrecoverable(error: Throwable): Boolean =
        generateSequence(error as Throwable?) { it.cause }.any {
            it is AEADBadTagException ||
                it is SerializationException ||
                it is IllegalArgumentException
        }

    /** Type and short message only; never the stored value. */
    private fun describe(error: Throwable): String =
        "${error.javaClass.simpleName}: ${error.message.orEmpty().take(120)}"

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun associatedData(scope: String): ByteArray {
        require(scope.isNotBlank()) { "Play credential scope must not be blank" }
        return "$ASSOCIATED_DATA_PREFIX\n$scope".toByteArray(StandardCharsets.UTF_8)
    }

    companion object {
        private const val FILE_NAME = "mini_store_play_auth.bin"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "secureguard_mini_store_play_auth_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION: Byte = 2
        private const val HEADER_BYTES = 2
        private const val MIN_GCM_TAG_BYTES = 16
        private const val ASSOCIATED_DATA_PREFIX = "secureguard-mini-store-play-auth/v2"
    }
}

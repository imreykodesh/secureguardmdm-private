package com.secureguard.mdm.ministore.data

import android.content.Context
import android.util.AtomicFile
import com.secureguard.mdm.data.local.PreferencesManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MiniStorePreferences @Inject constructor(
    @ApplicationContext context: Context,
    private val preferences: PreferencesManager,
) {
    private val catalogFloorFile = AtomicFile(File(context.noBackupFilesDir, "mini_store_catalog_floor"))

    fun isPasswordRequired(): Boolean =
        preferences.loadBoolean(PreferencesManager.KEY_MINI_STORE_REQUIRE_PASSWORD, true)

    fun setPasswordRequired(required: Boolean) =
        preferences.saveBoolean(PreferencesManager.KEY_MINI_STORE_REQUIRE_PASSWORD, required)

    fun getBlacklist(): Set<String> =
        preferences.loadStringSet(PreferencesManager.KEY_MINI_STORE_BLACKLIST, emptySet()).toSet()

    fun setBlacklist(packages: Set<String>) =
        preferences.saveStringSet(PreferencesManager.KEY_MINI_STORE_BLACKLIST, packages.toSet())

    @Synchronized
    fun acceptedCatalog(): AcceptedCatalog {
        val parts = try {
            catalogFloorFile.openRead().bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readLines().map(String::trim)
            }
        } catch (_: java.io.FileNotFoundException) {
            // openRead restores AtomicFile's backup first; only a genuinely empty state reaches here.
            return AcceptedCatalog(0L, "")
        }
        require(parts.size == 2) { "Catalog rollback floor is corrupt" }
        val revision = parts[0].toLongOrNull() ?: error("Catalog rollback revision is corrupt")
        require(revision >= 0 && SHA256.matches(parts[1])) { "Catalog rollback floor is corrupt" }
        return AcceptedCatalog(revision, parts[1])
    }

    @Synchronized
    fun acceptCatalog(revision: Long, payloadSha256: String) {
        require(revision > 0 && SHA256.matches(payloadSha256)) { "Invalid catalog acceptance state" }
        val accepted = acceptedCatalog()
        require(revision >= accepted.revision) { "Catalog rollback was rejected" }
        if (revision == accepted.revision) {
            require(accepted.payloadSha256 == payloadSha256) { "Catalog revision was reused with different content" }
            return
        }

        var output: FileOutputStream? = null
        try {
            output = catalogFloorFile.startWrite()
            output.write("$revision\n$payloadSha256\n".toByteArray(Charsets.UTF_8))
            catalogFloorFile.finishWrite(output)
        } catch (error: Exception) {
            output?.let(catalogFloorFile::failWrite)
            throw IllegalStateException("Could not persist catalog rollback floor", error)
        }
    }

    data class AcceptedCatalog(val revision: Long, val payloadSha256: String)

    companion object {
        private val SHA256 = Regex("^[a-f0-9]{64}$")
    }
}

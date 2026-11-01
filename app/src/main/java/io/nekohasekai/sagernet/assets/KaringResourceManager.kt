package io.nekohasekai.sagernet.assets

import android.content.Context
import libcore.Libcore
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/** The three wire namespaces owned by Karing's packaged SRS resources. */
enum class KaringRuleFamily(val wireName: String, val archiveName: String, val indexName: String) {
        ACL("acl", "acl.zip", "acl_codes.txt"),
        GEOSITE("geosite", "geosite.zip", "geosite_codes.txt"),
        GEOIP("geoip", "geoip.zip", "geoip_codes.txt");

        companion object {
            fun parse(value: String) = values().firstOrNull { it.wireName == value }
        }
}

data class KaringRuleCode(
    val family: KaringRuleFamily,
    val code: String,
    val bundled: Boolean,
    val valid: Boolean,
    val byteSize: Long,
    val sha256: String?,
)

data class Validation(
        val valid: Boolean,
        val family: KaringRuleFamily,
        val code: String,
        val file: File?,
        val size: Long,
        val sha256: String?,
        val ruleCount: Int,
        val error: String? = null,
    )

/** Owns Karing's ACL/GeoSite/GeoIP SRS assets; independent of routing/editor/database. */
class KaringResourceManager(context: Context, externalRoot: File? = null) {

    companion object {
        private const val MAX_DOWNLOAD_BYTES = 64L * 1024L * 1024L
        private const val MAX_CODE_LENGTH = 128
        // Shared across manager instances so bulk, individual and config installs cannot collide.
        private val locks = ConcurrentHashMap<String, Any>()
        private val SAFE_CODE = Regex("^[A-Za-z0-9][A-Za-z0-9@._!-]{0,127}$")
    }

    private val appContext = context.applicationContext
    // Must match Libcore.InitCore(externalAssets) so Go lookup sees extracted files.
    private val storageRoot = (externalRoot ?: (appContext.getExternalFilesDir(null) ?: appContext.filesDir)).canonicalFile
    private val root = File(storageRoot, "karing")
    private val indexes = EnumMap<KaringRuleFamily, List<String>>(KaringRuleFamily::class.java)
    private val bundledCodes = EnumMap<KaringRuleFamily, Set<String>>(KaringRuleFamily::class.java)

    init {
        require(root.canonicalFile.path == storageRoot.path || root.canonicalFile.path.startsWith(storageRoot.path + File.separator))
        root.mkdirs()
        for (family in KaringRuleFamily.values()) {
            indexes[family] = readIndex(family)
            bundledCodes[family] = readBundledCodes(family)
        }
    }

    /** Enumerates codes from the packaged index, never from an arbitrary directory. */
    fun list(family: KaringRuleFamily): List<KaringRuleCode> = indexes[family].orEmpty().map { code ->
        val file = target(family, code)
        val validation = validate(family, code)
        KaringRuleCode(family, code, isBundled(family, code), validation.valid, validation.size, validation.sha256)
    }

    fun list(family: String): List<KaringRuleCode> = list(KaringRuleFamily.parse(family) ?: throw IllegalArgumentException("unknown family"))

    fun resolve(reference: String): File {
        val parts = reference.split(":")
        require(parts.size == 2) { "invalid Karing reference" }
        return resolve(KaringRuleFamily.parse(parts[0]) ?: throw IllegalArgumentException("unknown Karing family"), parts[1])
    }

    fun resolve(family: KaringRuleFamily, code: String): File {
        checkCode(code)
        require(indexes[family].orEmpty().contains(code)) { "code is not in packaged index: ${family.wireName}:$code" }
        val file = target(family, code)
        val result = validate(family, code)
        if (!result.valid) throw IOException(result.error ?: "invalid SRS: ${file.name}")
        return file
    }

    fun validate(family: KaringRuleFamily, code: String): Validation {
        return try {
            checkCode(code)
            if (!indexes[family].orEmpty().contains(code)) return invalid(family, code, "code is not indexed")
            val file = target(family, code)
            if (!file.isFile) return invalid(family, code, "resource is not installed")
            val native = Libcore.validateKaringSRSFile(file.absolutePath, family.wireName, code)
            if (!native.valid) return invalid(family, code, native.error ?: "invalid SRS", file)
            Validation(true, family, code, file, file.length(), native.sha256, native.ruleCount.toInt())
        } catch (e: Throwable) {
            invalid(family, code, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Extracts only one selected SRS, then parses it before committing. */
    suspend fun ensure(family: KaringRuleFamily, code: String): File {
        checkCode(code)
        require(indexes[family].orEmpty().contains(code)) { "code is not indexed" }
        val key = target(family, code).path
        synchronized(locks.getOrPut(key) { Any() }) {
            val current = validate(family, code)
            if (current.valid) return current.file!!
            val destination = target(family, code)
            destination.parentFile?.mkdirs()
            // The native validator requires the exact basename; a private staging
            // directory prevents other processes from sharing the same .part file.
            val stage = File.createTempFile(".karing-", "", destination.parentFile)
            check(stage.delete() && stage.mkdir()) { "cannot create resource staging directory" }
            val part = File(stage, destination.name + ".part")
            try {
                extractEntry(family, code, part)
                val checked = validatePath(family, code, part)
                if (!checked.valid) throw IOException(checked.error ?: "packaged SRS failed validation")
                if (!validate(family, code).valid) atomicReplace(part, destination)
                return destination
            } finally {
                part.delete()
                stage.delete()
            }
        }
    }

    data class InstallAllResult(val total: Int, val succeeded: Int, val failures: Map<String, String>)

    /** Install independently: a failed entry never undoes successful entries. */
    suspend fun installAll(
        family: KaringRuleFamily,
        progress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): InstallAllResult = installKaringCodes(indexes[family].orEmpty(), { code ->
        ensure(family, code)
    }, progress)

    /** Downloads one SRS over HTTPS; URL filenames and redirects are ignored. */
    suspend fun download(url: String, family: KaringRuleFamily, code: String): File {
        checkCode(code)
        require(indexes[family].orEmpty().contains(code)) { "code is not indexed" }
        val parsed = URL(url)
        require(parsed.protocol.equals("https", true)) { "Karing resource URL must use HTTPS" }
        val key = target(family, code).path
        synchronized(locks.getOrPut(key) { Any() }) {
            val destination = target(family, code)
            val part = File(destination.parentFile, destination.name + ".part")
            part.parentFile?.mkdirs()
            part.delete()
            val connection = (parsed.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                requestMethod = "GET"
            }
            try {
                val status = connection.responseCode
                require(status in 200..299) { "resource download HTTP $status" }
                val declared = connection.contentLengthLong
                require(declared < 0 || declared <= MAX_DOWNLOAD_BYTES) { "resource is too large" }
                BufferedInputStream(connection.inputStream).use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= MAX_DOWNLOAD_BYTES) { "resource is too large" }
                            output.write(buffer, 0, read)
                        }
                        require(declared < 0 || total == declared) { "resource download was truncated" }
                        output.fd.sync()
                    }
                }
                val checked = validatePath(family, code, part)
                if (!checked.valid) throw IOException(checked.error ?: "downloaded SRS failed validation")
                atomicReplace(part, destination)
                return destination
            } finally {
                connection.disconnect()
                if (part.isFile) part.delete()
            }
        }
    }

    private fun readIndex(family: KaringRuleFamily): List<String> {
        val values = appContext.assets.open("karing/${family.archiveName}").use { archive ->
            ZipInputStream(BufferedInputStream(archive)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == family.indexName) {
                        return@use zip.readBytes().toString(Charsets.UTF_8).lineSequence()
                            .map { it.trim() }.filter { it.isNotEmpty() }.toList()
                    }
                    entry = zip.nextEntry
                }
                emptyList()
            }
        }
        require(values.isNotEmpty() && values.distinct().size == values.size) { "empty or duplicate ${family.wireName} index" }
        values.forEach(::checkCode)
        return values.sorted()
    }

    private fun isBundled(family: KaringRuleFamily, code: String): Boolean =
        bundledCodes[family].orEmpty().contains(code)

    private fun readBundledCodes(family: KaringRuleFamily): Set<String> {
        return appContext.assets.open("karing/${family.archiveName}").use { archive ->
            ZipInputStream(BufferedInputStream(archive)).use { zip ->
                generateSequence { zip.nextEntry }
                    .mapNotNull { entry ->
                        val prefix = "${family.wireName}/"
                        entry.name.takeIf { it.startsWith(prefix) && it.endsWith(".srs") }
                            ?.removePrefix(prefix)?.removeSuffix(".srs")
                    }.toSet()
            }
        }
    }

    private fun extractEntry(family: KaringRuleFamily, code: String, destination: File) {
        val expected = "${family.wireName}/$code.srs"
        destination.parentFile?.mkdirs()
        appContext.assets.open("karing/${family.archiveName}").use { archive ->
            ZipInputStream(BufferedInputStream(archive)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == expected) {
                        FileOutputStream(destination).use { output ->
                            zip.copyTo(output)
                            output.fd.sync()
                        }
                        return
                    }
                    entry = zip.nextEntry
                }
            }
        }
        throw IOException("bundled resource missing: $expected")
    }

    private fun target(family: KaringRuleFamily, code: String): File {
        checkCode(code)
        val directory = File(root, family.wireName)
        val result = File(directory, "$code.srs").canonicalFile
        require(result.parentFile == directory.canonicalFile) { "unsafe resource path" }
        return result
    }

    private fun validatePath(family: KaringRuleFamily, code: String, path: File): Validation {
        return try {
            val native = Libcore.validateKaringSRSFile(path.absolutePath, family.wireName, code)
            if (!native.valid) return invalid(family, code, native.error ?: "invalid SRS", path)
            Validation(true, family, code, path, path.length(), native.sha256, native.ruleCount.toInt())
        } catch (e: Throwable) {
            invalid(family, code, e.message ?: "invalid SRS", path)
        }
    }

    private fun invalid(family: KaringRuleFamily, code: String, error: String, file: File? = null) =
        Validation(false, family, code, file, file?.length() ?: 0L, null, 0, error)

    private fun checkCode(code: String) {
        require(code.length in 1..MAX_CODE_LENGTH && SAFE_CODE.matches(code)) { "unsafe Karing code" }
    }

    private fun atomicReplace(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            try {
                java.nio.file.Files.move(source.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                return
            } catch (_: Exception) {
                // Both files are on the same filesystem; rename is the fallback.
            }
        }
        if (!source.renameTo(destination)) throw IOException("cannot commit Karing resource")
    }
}

/** Isolated loop makes per-item errors observable and testable without native code. */
internal suspend fun installKaringCodes(
    codes: List<String>,
    install: suspend (String) -> Unit,
    progress: suspend (Int, Int) -> Unit,
): KaringResourceManager.InstallAllResult {
    val failures = linkedMapOf<String, String>()
    codes.forEachIndexed { index, code ->
        try {
            install(code)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failures[code] = e.message ?: e.javaClass.simpleName
        }
        progress(index + 1, codes.size)
    }
    return KaringResourceManager.InstallAllResult(codes.size, codes.size - failures.size, failures)
}

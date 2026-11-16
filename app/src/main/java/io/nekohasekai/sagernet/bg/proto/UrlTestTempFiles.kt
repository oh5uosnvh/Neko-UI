package io.nekohasekai.sagernet.bg.proto

import java.io.File

/** Atomic filenames and instance-local ownership; register before writing content. */
internal class UrlTestTempFiles {
    private val files = ArrayList<File>()

    fun create(directory: File, prefix: String, suffix: String): File {
        directory.mkdirs()
        return File.createTempFile(prefix, suffix, directory).also { files.add(it) }
    }

    fun deleteOwned() {
        files.removeAll { it.delete(); true }
    }
}

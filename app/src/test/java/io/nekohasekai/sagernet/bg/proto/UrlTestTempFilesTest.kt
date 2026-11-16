package io.nekohasekai.sagernet.bg.proto

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class UrlTestTempFilesTest {
    @Test fun concurrentInstancesNeverOverwriteOrDeleteOtherInstancesFiles() {
        val directory = Files.createTempDirectory("urltest-ownership-").toFile()
        val executor = Executors.newFixedThreadPool(16)
        val gate = CountDownLatch(1)
        try {
            val results = (0 until 64).map { id ->
                executor.submit<Pair<UrlTestTempFiles, List<java.io.File>>> {
                    assertTrue(gate.await(5, TimeUnit.SECONDS))
                    val owner = UrlTestTempFiles()
                    val files = listOf("trojan_go_", "mieru_", "naive_", "hysteria_").map { prefix ->
                        owner.create(directory, prefix, ".json").also { it.writeText("instance-$id") }
                    } + listOf(".ca", ".crt").map { suffix ->
                        owner.create(directory, "certificate_", suffix).also { it.writeText("instance-$id") }
                    }
                    owner to files
                }
            }
            gate.countDown()
            val owners = results.map { it.get(10, TimeUnit.SECONDS) }
            val all = owners.flatMap { it.second }
            assertEquals(384, all.map { it.canonicalPath }.toSet().size)
            owners.forEachIndexed { id, (_, files) ->
                files.forEach { assertEquals("instance-$id", it.readText()) }
            }
            owners.filterIndexed { id, _ -> id % 2 == 0 }.forEach { it.first.deleteOwned() }
            owners.forEachIndexed { id, (_, files) ->
                files.forEach { file ->
                    assertEquals(id % 2 != 0, file.exists())
                    if (file.exists()) assertEquals("instance-$id", file.readText())
                }
            }
            owners.forEach { it.first.deleteOwned(); it.first.deleteOwned() }
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { gate.countDown(); executor.shutdownNow(); directory.deleteRecursively() }
    }

    @Test fun failedWriteStillLeavesFileRegisteredForCleanup() {
        val directory = Files.createTempDirectory("urltest-partial-").toFile()
        try {
            val owner = UrlTestTempFiles()
            val file = owner.create(directory, "partial_", ".json")
            // Simulate a failure after allocation but before configuration is written.
            assertTrue(file.exists())
            owner.deleteOwned()
            assertFalse(file.exists())
        } finally { directory.deleteRecursively() }
    }
}

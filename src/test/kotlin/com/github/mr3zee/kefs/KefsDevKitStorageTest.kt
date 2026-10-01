package com.github.mr3zee.kefs

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

class KefsDevKitStorageTest : BasePlatformTestCase() {
    fun testInitiallyIncompleteJarIsRetriedByWatcher(): Unit = runBlocking {
        checkIncompleteJarRetry(useWatcher = true)
    }

    fun testInitiallyIncompleteJarIsRetriedByUpdate(): Unit = runBlocking {
        checkIncompleteJarRetry(useWatcher = false)
    }

    @OptIn(ExperimentalPathApi::class)
    private suspend fun checkIncompleteJarRetry(useWatcher: Boolean) {
        val tempDir = Files.createTempDirectory("kefs-devkit-storage-test")
        val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = KefsStorage(project, parentScope)
            // Keep the cached test jar out of the user's KEFS cache.
            KefsStorage::class.java.getDeclaredField("_cacheDir").apply { isAccessible = true }
                .set(storage, CompletableDeferred(tempDir.resolve("cache")))
            val resolvedCacheDir = KefsStorage::class.java.getDeclaredField("resolvedCacheDir")
                .apply { isAccessible = true }.get(storage) as AtomicBoolean
            resolvedCacheDir.set(true)

            val source = Files.createDirectories(tempDir.resolve("libs")).resolve("plugin.jar")
            val bytes = devKitJarBytes()
            Files.write(source, bytes.copyOf(bytes.size / 2))
            assertEquals(DevKitDetection.Incomplete, KefsDevKit.inspect(source))
            assertTrue(storage.getDevKitPluginPath(source) is DevKitLookup.NotDevKit)

            val jobs = KefsStorage::class.java.getDeclaredField("devKitJobs")
                .apply { isAccessible = true }.get(storage) as Map<*, *>
            awaitCondition { jobs.isEmpty() }
            assertTrue(storage.requestDiscovery().isEmpty())

            if (!useWatcher) {
                storage.fileWatcher.reset()
            }
            Files.write(source, bytes)
            if (!useWatcher) {
                storage.runActualization()
            }

            // No second provider call: the watcher or Update must discover the completed jar.
            awaitCondition { storage.requestDiscovery().any { it.pluginName == "test.kefs.retry" } }
            val discovery = storage.requestDiscovery().single { it.pluginName == "test.kefs.retry" }
            assertTrue(discovery.jar.startsWith(tempDir.resolve("cache")))
            assertTrue(bytes.contentEquals(Files.readAllBytes(discovery.jar)))
        } finally {
            parentScope.coroutineContext.job.cancelAndJoin()
            tempDir.deleteRecursively()
        }
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(30000) {
            while (!condition()) {
                delay(100)
            }
        }
    }

    private fun devKitJarBytes(): ByteArray {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue(KefsDevKit.MANIFEST_ATTRIBUTE, "true")
        }
        val bytes = ByteArrayOutputStream()
        JarOutputStream(bytes, manifest).use { jar ->
            jar.putNextEntry(ZipEntry("META-INF/kotlin/plugin/test.kefs.retry/versions/2.0.0/test/Registrar.class"))
            jar.closeEntry()
        }
        return bytes.toByteArray()
    }
}

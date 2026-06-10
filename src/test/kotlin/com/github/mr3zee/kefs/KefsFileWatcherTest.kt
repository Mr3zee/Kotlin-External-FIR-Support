package com.github.mr3zee.kefs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText

class KefsFileWatcherTest {
    private lateinit var tempDir: Path
    private lateinit var localRepoDir: Path
    private lateinit var cacheDir: Path
    private lateinit var watcher: KefsFileWatcher

    private val localRepoChanges = CopyOnWriteArrayList<Path>()
    private val cacheDirChangeCount = AtomicInteger(0)

    @Before
    fun setUp(): Unit = runBlocking {
        tempDir = Files.createTempDirectory("kefs-watcher-test")
        localRepoDir = tempDir.resolve("local-repo").also { it.createDirectories() }
        cacheDir = tempDir.resolve("cache").also { it.createDirectories() }

        watcher = KefsFileWatcher(object : FileWatcherCallback {
            override fun onLocalRepoChange(repoRoot: Path) {
                localRepoChanges.add(repoRoot)
            }

            override fun onCacheDirExternalChange() {
                cacheDirChangeCount.incrementAndGet()
            }
        })

        watcher.registerCacheDir(cacheDir)
        watcher.registerLocalRepo(localRepoDir)
    }

    @OptIn(ExperimentalPathApi::class)
    @After
    fun tearDown() {
        watcher.close()
        tempDir.deleteRecursively()
    }

    // --- Local Repo Tests ---

    @Test
    fun testLocalRepoFileCreation(): Unit = runBlocking {
        createFile(localRepoDir.resolve("artifact.jar"), "content")

        awaitCondition { localRepoChanges.isNotEmpty() }

        assertTrue("Expected local repo change event", localRepoChanges.isNotEmpty())
        assertEquals(localRepoDir.toAbsolutePath().normalize(), localRepoChanges.first())
    }

    @Test
    fun testLocalRepoFileModification(): Unit = runBlocking {
        val file = localRepoDir.resolve("artifact.jar")
        createFile(file, "original")

        awaitCondition { localRepoChanges.isNotEmpty() }
        localRepoChanges.clear()

        modifyFile(file, "modified")

        awaitCondition { localRepoChanges.isNotEmpty() }

        assertTrue("Expected local repo change event on modification", localRepoChanges.isNotEmpty())
    }

    @Test
    fun testLocalRepoFileDeletion(): Unit = runBlocking {
        val file = localRepoDir.resolve("artifact.jar")
        createFile(file, "content")

        awaitCondition { localRepoChanges.isNotEmpty() }
        localRepoChanges.clear()

        deleteFile(file)

        awaitCondition { localRepoChanges.isNotEmpty() }

        assertTrue("Expected local repo change event on deletion", localRepoChanges.isNotEmpty())
    }

    @Test
    fun testLocalRepoNewSubdirectory(): Unit = runBlocking {
        val subdir = localRepoDir.resolve("org/example/1.0")

        withContext(Dispatchers.IO) {
            subdir.createDirectories()
        }

        // Wait for directory creation events to propagate and subdirectory to be registered
        awaitCondition { localRepoChanges.isNotEmpty() }
        localRepoChanges.clear()

        // Give the watcher time to register the new subdirectory
        processEventsFor(500)

        // Now create a file in the new subdirectory
        createFile(subdir.resolve("artifact.jar"), "content")

        awaitCondition { localRepoChanges.isNotEmpty() }

        assertTrue("Expected local repo change event for file in new subdirectory", localRepoChanges.isNotEmpty())
    }

    // --- Cache Dir Tests ---

    @Test
    fun testCacheDirExternalFileCreation(): Unit = runBlocking {
        createFile(cacheDir.resolve("plugin.jar"), "content")

        awaitCondition { cacheDirChangeCount.get() > 0 }

        assertTrue("Expected cache dir external change event", cacheDirChangeCount.get() > 0)
    }

    @Test
    fun testCacheDirExternalFileModification(): Unit = runBlocking {
        val file = cacheDir.resolve("plugin.jar")
        createFile(file, "original")

        awaitCondition { cacheDirChangeCount.get() > 0 }
        cacheDirChangeCount.set(0)

        modifyFile(file, "modified")

        awaitCondition { cacheDirChangeCount.get() > 0 }

        assertTrue("Expected cache dir external change event on modification", cacheDirChangeCount.get() > 0)
    }

    @Test
    fun testCacheDirDeletion(): Unit = runBlocking {
        val file = cacheDir.resolve("plugin.jar")
        createFile(file, "content")

        awaitCondition { cacheDirChangeCount.get() > 0 || cacheDirChangeCount.get() > 0 }
        cacheDirChangeCount.set(0)
        cacheDirChangeCount.set(0)

        deleteFile(file)

        awaitCondition { cacheDirChangeCount.get() > 0 }

        assertTrue("Expected cache dir deleted event", cacheDirChangeCount.get() > 0)
    }

    // --- Self-update suppression tests ---

    @Test
    fun testCacheDirSelfUpdateSuppression(): Unit = runBlocking {
        val session = watcher.markSelfUpdateStart()
        try {
            createFile(cacheDir.resolve("self-update.jar"), "content")

            // Process events for a while - should NOT trigger callback
            processEventsFor(2000)
        } finally {
            session.end()
        }

        assertEquals("Self-update should NOT trigger cache dir external change", 0, cacheDirChangeCount.get())
        assertEquals("Self-update should NOT trigger cache dir deleted", 0, cacheDirChangeCount.get())
    }

    @Test
    fun testCacheDirSelfUpdateThenExternalChange(): Unit = runBlocking {
        // Self-update: should be suppressed
        val session = watcher.markSelfUpdateStart()
        createFile(cacheDir.resolve("self-update.jar"), "self-content")
        processEventsFor(1500)
        session.end()

        assertEquals("Self-update should be suppressed", 0, cacheDirChangeCount.get())

        // Wait for the self-update grace period to expire before making an external change
        delay(KefsFileWatcher.SELF_UPDATE_GRACE_PERIOD_MS + 500)
        // Drain any buffered events from the self-update that arrive during the grace period
        processEventsFor(1500)
        assertEquals("Events during grace period should be suppressed", 0, cacheDirChangeCount.get())

        // External change: should fire
        createFile(cacheDir.resolve("external.jar"), "external-content")

        awaitCondition { cacheDirChangeCount.get() > 0 }

        assertTrue("External change after self-update should trigger callback", cacheDirChangeCount.get() > 0)
    }

    /**
     * Regression test: file DELETION during self-update must be suppressed.
     * This was the root cause of the infinite loop on Windows where the locator's
     * temp file cleanup triggered onCacheDirExternalChange -> clearState -> cancel locator.
     */
    @Test
    fun testCacheDirSelfUpdateSuppressesDeleteEvents(): Unit = runBlocking {
        // Create a file first, then delete it during self-update
        val file = cacheDir.resolve("temp.jar.downloading")
        createFile(file, "temp content")
        awaitCondition { cacheDirChangeCount.get() > 0 }
        cacheDirChangeCount.set(0)

        val session = watcher.markSelfUpdateStart()
        try {
            deleteFile(file)
            processEventsFor(2000)
        } finally {
            session.end()
        }

        assertEquals("DELETE during self-update should be suppressed", 0, cacheDirChangeCount.get())
    }

    /**
     * Regression test: multiple file operations (create + delete + create) during self-update
     * must all be suppressed. This simulates the locator's workflow of cleaning up .downloading
     * files and creating new ones.
     */
    @Test
    fun testCacheDirSelfUpdateSuppressesMultipleOperations(): Unit = runBlocking {
        val session = watcher.markSelfUpdateStart()
        try {
            // Simulate locator workflow: create .downloading, then delete it, then create new one
            val downloading = cacheDir.resolve("artifact.jar.downloading")
            createFile(downloading, "partial download")
            processEventsFor(500)
            deleteFile(downloading)
            processEventsFor(500)
            createFile(downloading, "new download content")
            processEventsFor(500)
            // Simulate rename to final jar
            val finalJar = cacheDir.resolve("artifact.jar")
            withContext(Dispatchers.IO) {
                java.nio.file.Files.move(downloading, finalJar)
            }
            processEventsFor(1000)
        } finally {
            session.end()
        }

        assertEquals("All operations during self-update should be suppressed", 0, cacheDirChangeCount.get())
    }

    /**
     * Tests that the grace period after SelfUpdateSession.end() suppresses late-arriving events.
     * OS file watchers (especially on Windows) can deliver events asynchronously after the
     * file operations that caused them.
     */
    @Test
    fun testGracePeriodSuppressesDelayedEvents(): Unit = runBlocking {
        val session = watcher.markSelfUpdateStart()
        createFile(cacheDir.resolve("artifact.jar"), "content")
        processEventsFor(500)
        session.end()

        // Immediately process events — these are "delayed" events from the self-update
        // that arrive after end() but within the grace period
        processEventsFor(1000)

        assertEquals("Events during grace period should be suppressed", 0, cacheDirChangeCount.get())
    }

    /**
     * Tests that external changes AFTER the grace period expires are properly detected.
     */
    @Test
    fun testExternalDeleteAfterGracePeriodFires(): Unit = runBlocking {
        // Create a file outside of self-update
        val file = cacheDir.resolve("external.jar")
        createFile(file, "content")
        awaitCondition { cacheDirChangeCount.get() > 0 }
        cacheDirChangeCount.set(0)

        // Do a self-update cycle
        val session = watcher.markSelfUpdateStart()
        createFile(cacheDir.resolve("self.jar"), "self")
        processEventsFor(500)
        session.end()

        // Wait for grace period to expire
        delay(KefsFileWatcher.SELF_UPDATE_GRACE_PERIOD_MS + 500)
        processEventsFor(1500)
        cacheDirChangeCount.set(0)

        // Now delete a file — should trigger callback
        deleteFile(file)
        awaitCondition { cacheDirChangeCount.get() > 0 }

        assertTrue("DELETE after grace period should trigger callback", cacheDirChangeCount.get() > 0)
    }

    /**
     * Regression test: ending one self-update session twice must not affect another
     * session still in flight. A cancelled actualize job used to decrement the shared
     * counter twice (finally + cancellation handler), taking it from 2 to 0 while a
     * concurrent job was still downloading; the watcher then treated the plugin's own
     * downloads as external changes, cancelling jobs in an infinite loop.
     */
    @Test
    fun testDoubleSessionEndDoesNotBreakConcurrentSuppression(): Unit = runBlocking {
        val cancelled = watcher.markSelfUpdateStart()
        val inFlight = watcher.markSelfUpdateStart()

        // Simulate the historical double-decrement of a cancelled job: end() is idempotent
        cancelled.end()
        cancelled.end()

        // Wait out the grace period so suppression relies on the counter alone
        delay(KefsFileWatcher.SELF_UPDATE_GRACE_PERIOD_MS + 500)

        try {
            // The in-flight session must still suppress the watcher
            createFile(cacheDir.resolve("self-update.jar"), "content")
            processEventsFor(2000)
        } finally {
            inFlight.end()
        }

        assertEquals(
            "Self-update concurrent with a double-ended session should still be suppressed",
            0,
            cacheDirChangeCount.get(),
        )
    }

    @Test
    fun testNoSpuriousEvents(): Unit = runBlocking {
        // Just wait without making changes
        processEventsFor(2000)

        assertTrue("No local repo changes expected", localRepoChanges.isEmpty())
        assertEquals("No cache dir changes expected", 0, cacheDirChangeCount.get())
        assertEquals("No cache dir deletions expected", 0, cacheDirChangeCount.get())
    }

    // --- Registration order tests ---

    /**
     * Regression test for Finding 2: registerLocalRepo before registerCacheDir
     * must still produce working watch keys for local repo changes.
     */
    @Test
    fun testLocalRepoRegisteredBeforeCacheDir(): Unit = runBlocking {
        // Create a fresh watcher with reversed registration order
        watcher.close()

        val localChanges = CopyOnWriteArrayList<Path>()
        val cacheChanges = AtomicInteger(0)

        val freshWatcher = KefsFileWatcher(object : FileWatcherCallback {
            override fun onLocalRepoChange(repoRoot: Path) {
                localChanges.add(repoRoot)
            }

            override fun onCacheDirExternalChange() {
                cacheChanges.incrementAndGet()
            }
        })

        // Register local repo FIRST (before watchService is initialized)
        freshWatcher.registerLocalRepo(localRepoDir)
        // Then cache dir
        freshWatcher.registerCacheDir(cacheDir)

        try {
            // Mutate local repo
            createFile(localRepoDir.resolve("new-artifact.jar"), "content")

            val deadline = System.currentTimeMillis() + 15000
            while (System.currentTimeMillis() < deadline) {
                freshWatcher.processOneEvent()
                if (localChanges.isNotEmpty()) break
            }

            assertTrue(
                "Local repo callback should fire even when registerLocalRepo is called before registerCacheDir",
                localChanges.isNotEmpty()
            )
            assertEquals(localRepoDir.toAbsolutePath().normalize(), localChanges.first())
        } finally {
            freshWatcher.close()
        }
    }

    // --- Helpers ---

    private suspend fun createFile(path: Path, content: String) {
        withContext(Dispatchers.IO) {
            path.parent.createDirectories()
            path.writeText(content)
        }
    }

    private suspend fun modifyFile(path: Path, content: String) {
        withContext(Dispatchers.IO) {
            path.writeText(content)
        }
    }

    private suspend fun deleteFile(path: Path) {
        withContext(Dispatchers.IO) {
            Files.deleteIfExists(path)
        }
    }

    private suspend fun processEventsFor(durationMs: Long) {
        val deadline = System.currentTimeMillis() + durationMs
        while (System.currentTimeMillis() < deadline) {
            watcher.processOneEvent()
        }
    }

    private suspend fun awaitCondition(
        timeoutMs: Long = 15000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            watcher.processOneEvent()

            if (condition()) {
                return
            }
        }

        // One final check
        if (!condition()) {
            throw AssertionError("Condition not met within ${timeoutMs}ms")
        }
    }
}

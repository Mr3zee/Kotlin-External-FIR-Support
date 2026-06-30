package com.github.mr3zee.kefs

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.nio.file.ClosedWatchServiceException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.Watchable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.PathWalkOption
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.walk

internal interface FileWatcherCallback {
    fun onLocalRepoChange(repoRoot: Path)
    fun onCacheDirExternalChange()
}

internal class KefsFileWatcher(
    private val callback: FileWatcherCallback,
) : Closeable {
    @Volatile
    private var watchService: WatchService? = null
    @Volatile
    private var watchServiceUnavailable = false
    private val registrationLock = Mutex()

    private val watchedDirToRoot = ConcurrentHashMap<Path, Path>()
    private val watchedDirToKey = ConcurrentHashMap<Path, WatchKey>()
    private val registeredRoots = ConcurrentHashMap.newKeySet<Path>()
    private val localRepoRoots = ConcurrentHashMap.newKeySet<Path>()
    private val cacheDirSelfUpdates = AtomicLong(0)
    private val selfUpdateEndTimestamp = AtomicLong(0)

    private val logger by lazy { thisLogger() }

    suspend fun registerLocalRepo(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        registrationLock.withLock {
            if (ensureWatchServiceInitialized(normalized) == null) return@withLock
            // Mark as a local repo root only once we know we will actually watch it, so a
            // concurrent reset() (which clears localRepoRoots) cannot leave a watched root
            // misclassified as a cache dir in processOneEvent.
            localRepoRoots.add(normalized)
            registerDirectoryTreeWatch(normalized)
        }
    }

    suspend fun registerCacheDir(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        registrationLock.withLock {
            if (ensureWatchServiceInitialized(normalized) == null) return@withLock
            registerDirectoryTreeWatch(normalized)
        }
    }

    private suspend fun ensureWatchServiceInitialized(path: Path): WatchService? {
        watchService?.let { return it }
        if (watchServiceUnavailable) return null

        val created = runCatchingExceptCancellation {
            withContext(Dispatchers.IO) {
                path.fileSystem.newWatchService()
            }
        }.getOrElse { e ->
            watchServiceUnavailable = true
            logger.warn("Failed to initialize file watch service; hot-reload disabled until next state refresh", e)
            null
        }

        watchService = created
        return created
    }

    /**
     * A single self-update of the cache dir. [end] is idempotent, so each started
     * self-update decrements the counter exactly once no matter how many code paths
     * (finally blocks, cancellation handlers) call it. An unbalanced extra end() call
     * used to drive the counter to zero while another self-update was still in flight,
     * making the watcher treat the plugin's own downloads as external changes.
     */
    internal inner class SelfUpdateSession {
        private val ended = AtomicBoolean(false)

        fun end() {
            if (ended.compareAndSet(false, true)) {
                cacheDirSelfUpdates.decrementAndGet()
                selfUpdateEndTimestamp.set(System.currentTimeMillis())
            }
        }
    }

    fun markSelfUpdateStart(): SelfUpdateSession {
        cacheDirSelfUpdates.incrementAndGet()
        return SelfUpdateSession()
    }

    /**
     * Returns true if the cache dir is currently being updated by the locator,
     * or was updated recently (within a grace period to absorb delayed OS events).
     */
    private fun isSelfUpdating(): Boolean {
        if (cacheDirSelfUpdates.get() > 0) return true
        // Grace period to absorb delayed file watcher events delivered after the self-update ends.
        // WatchService polls every 1s, and OS event delivery can be delayed further on Windows.
        val ts = selfUpdateEndTimestamp.get()
        return ts != 0L && System.currentTimeMillis() - ts < SELF_UPDATE_GRACE_PERIOD_MS
    }

    companion object {
        internal const val SELF_UPDATE_GRACE_PERIOD_MS = 2000L
    }

    /**
     * Processes one event from the watch service.
     * Returns true if an event was processed, false if poll timed out.
     */
    suspend fun processOneEvent(): Boolean {
        val service = watchService
        if (service == null) {
            // No watch service available (failed to initialize or not yet initialized).
            // Sleep to avoid busy-spinning the caller's `while (true) { processOneEvent() }` loop.
            delay(1000)
            return false
        }

        val key = try {
            withContext(Dispatchers.IO) {
                service.poll(1000, TimeUnit.MILLISECONDS)
            }
        } catch (_: ClosedWatchServiceException) {
            // The service was closed concurrently (e.g. reset() during a state clear).
            // Sleep before returning so the caller's `while (true)` loop does not busy-spin
            // until reset() finishes nulling the field; the next iteration then picks up the
            // freshly created service (or null -> delay again).
            delay(1000)
            return false
        } ?: return false

        // WatchKey.watchable() may return a different Path type (e.g. UnixPath)
        // than what we stored (e.g. MultiRoutingFsPath), so look up by string
        val rawPath = key.watchable() as? Path
        val path = rawPath?.let { findWatchedPath(it) } ?: rawPath

        if (!key.isValid || path == null) {
            val root = path?.let { watchedDirToRoot[it] }
            deregisterWatchedDir(path)
            if (root != null && !isLocalRepoRoot(root)) {
                if (!isSelfUpdating()) {
                    logger.debug("File watcher: invalid key for cache dir $path, triggering external change")
                    callback.onCacheDirExternalChange()
                } else {
                    logger.debug("File watcher: invalid key for cache dir $path suppressed (self-updating)")
                }
            }
            return true
        }

        val root = watchedDirToRoot[path]
        if (root == null) {
            key.pollEvents()
            key.cancel()
            return true
        }

        val events = key.pollEvents()
        val isCacheDir = !isLocalRepoRoot(root)

        for (event in events) {
            val contextName = (event.context() as? Path) ?: continue
            val resolved = path.resolve(contextName)

            when (event.kind()) {
                StandardWatchEventKinds.ENTRY_CREATE -> {
                    if (Files.isDirectory(resolved)) {
                        registerSubdirectoriesWatch(resolved, root)
                    }
                }

                StandardWatchEventKinds.ENTRY_DELETE -> {
                    if (isCacheDir) {
                        if (!isSelfUpdating()) {
                            logger.debug("File watcher: cache dir DELETE event for $contextName, triggering external change")
                            callback.onCacheDirExternalChange()
                        } else {
                            logger.debug("File watcher: cache dir DELETE event for $contextName suppressed (self-updating)")
                        }
                        key.reset()
                        return true
                    }
                    watchedDirToRoot.keys.filter { it.startsWith(resolved) }.forEach { removedPath ->
                        watchedDirToRoot.remove(removedPath)
                        watchedDirToKey.remove(removedPath)?.cancel()
                    }
                }
            }
        }

        if (events.isEmpty()) {
            key.reset()
            return true
        }

        if (!isCacheDir) {
            callback.onLocalRepoChange(root)
        } else {
            if (!isSelfUpdating()) {
                logger.debug("File watcher detected external changes in cache dir: ${events.map { "${it.kind()}: ${it.context()}" }}")
                callback.onCacheDirExternalChange()
            } else {
                logger.debug("File watcher: cache dir events suppressed (self-updating): ${events.map { "${it.kind()}: ${it.context()}" }}")
            }
        }
        key.reset()
        return true
    }

    fun cancelAllWatchKeys() {
        watchedDirToKey.values.forEach { it.cancel() }
        watchedDirToKey.clear()
        watchedDirToRoot.clear()
        registeredRoots.clear()
    }

    suspend fun reset() {
        registrationLock.withLock {
            cancelAllWatchKeys()
            localRepoRoots.clear()
            watchService?.let { service ->
                runCatchingExceptCancellation { service.close() }
            }
            watchService = null
            watchServiceUnavailable = false
        }
    }

    override fun close() {
        cancelAllWatchKeys()
        localRepoRoots.clear()
        watchService?.let { service ->
            runCatchingExceptCancellation { service.close() }
        }
        watchService = null
    }

    private suspend fun registerDirectoryTreeWatch(root: Path) {
        if (!registeredRoots.add(root)) return
        if (!root.exists()) {
            registeredRoots.remove(root)
            return
        }
        registerSubdirectoriesWatch(root, root)
    }

    @OptIn(ExperimentalPathApi::class)
    private suspend fun registerSubdirectoriesWatch(dir: Path, root: Path) {
        runCatchingExceptCancellation {
            withContext(Dispatchers.IO) {
                dir.walk(PathWalkOption.INCLUDE_DIRECTORIES).filter {
                    it.isDirectory()
                }.forEach { subdir ->
                    if (watchedDirToRoot.putIfAbsent(subdir, root) == null) {
                        val key = subdir.registerSafe(
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_DELETE,
                        )
                        if (key != null) {
                            watchedDirToKey[subdir] = key
                        }
                    }
                }
            }
        }
    }

    /**
     * WatchKey.watchable() may return a different Path type than what was stored
     * in watchedDirToRoot (e.g. sun.nio.fs.UnixPath vs MultiRoutingFsPath).
     * Look up by string path to find the matching stored key.
     */
    private fun findWatchedPath(rawPath: Path): Path? {
        // Fast path: direct lookup works when Path types match
        if (watchedDirToRoot.containsKey(rawPath)) return rawPath
        // Slow path: match by string representation
        val pathStr = rawPath.invariantSeparatorsPathString
        return watchedDirToRoot.keys.find { it.invariantSeparatorsPathString == pathStr }
    }

    private fun isLocalRepoRoot(root: Path): Boolean {
        return localRepoRoots.contains(root)
    }

    private fun deregisterWatchedDir(dir: Path?) {
        if (dir != null) {
            watchedDirToRoot.remove(dir)
            watchedDirToKey.remove(dir)?.cancel()
        }
    }

    @Suppress("SameParameterValue")
    private fun Watchable.registerSafe(vararg events: WatchEvent.Kind<*>): WatchKey? {
        val service = watchService ?: return null
        return try {
            register(service, *events)
        } catch (e: Exception) {
            logger.warn("Failed to register watch key for $this", e)
            null
        }
    }
}

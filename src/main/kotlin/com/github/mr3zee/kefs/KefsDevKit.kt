package com.github.mr3zee.kefs

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros.WORKSPACE_FILE
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile
import java.util.jar.Manifest
import java.util.zip.ZipInputStream
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name

/**
 * A jar produced by the [Compiler Plugin DevKit](https://github.com/Kotlin/compiler-plugin-dev-kit).
 *
 * Such a jar bundles one build of the plugin per supported Kotlin compiler version
 * and picks the matching one itself at runtime, so it is loaded as is —
 * without searching repositories for an IDE-compatible artifact.
 */
internal class DevKitJarInfo(
    val pluginId: String,
    val kotlinVersions: List<String>,
    // IDE build -> Kotlin version, the dev kit uses them instead of the Kotlin version reported by the IDE
    val ideMappings: Map<String, String> = emptyMap(),
)

internal sealed interface DevKitDetection {
    class DevKit(val info: DevKitJarInfo) : DevKitDetection

    object NotDevKit : DevKitDetection

    // the jar can't be read, most likely it is still being written
    object Incomplete : DevKitDetection
}

internal data class FileStamp(
    val size: Long,
    val lastModified: Long,
)

internal object KefsDevKit {
    const val MANIFEST_ATTRIBUTE = "Kotlin-Compiler-Plugin-Multi-Release"
    const val REPOSITORY_NAME = "Compiler Plugin DevKit"
    const val UNKNOWN_VERSION = "unversioned"

    private const val MANIFEST_PATH = "META-INF/MANIFEST.MF"
    private const val PLUGIN_PATH = "META-INF/kotlin/plugin"
    private const val IDE_MAPPINGS_PATH = "META-INF/org/jetbrains/kotlin/compiler/plugin/devkit/ide-mappings.txt"

    // META-INF/kotlin/plugin/<pluginId>/versions/<kotlin-version>/...
    private val versionEntryRegex = "^$PLUGIN_PATH/([^/]+)/versions/([^/]+)/".toRegex()

    // nested entries are matched greedily, so a dev kit library embedded as a dependency resolves to its own classes
    private val versionedPrefixRegex = "^.*$PLUGIN_PATH/[^/]+/versions/[^/]+/".toRegex()
    private val dependencyRegex = "$PLUGIN_PATH/[^/]+/dependencies/".toRegex()

    // <artifact-id>-<version>
    private val artifactVersionRegex = "^(.+?)-(\\d.*)$".toRegex()

    private const val CACHED_CHECKSUM_LENGTH = 12
    private val cachedChecksumRegex = "[0-9a-f]{$CACHED_CHECKSUM_LENGTH}".toRegex()

    /**
     * Returns dev kit info for the [jar] or null if it wasn't produced by the dev kit
     * (or can't be read, for example, when it is still being written).
     */
    fun detect(jar: Path): DevKitJarInfo? {
        return (inspect(jar) as? DevKitDetection.DevKit)?.info
    }

    /**
     * Same as [detect], but tells apart the jars that are not dev kit ones
     * and the jars that can't be read and may become dev kit ones once they are fully written.
     */
    fun inspect(jar: Path): DevKitDetection {
        val info = try {
            doDetect(jar)
        } catch (_: Exception) {
            return DevKitDetection.Incomplete
        }

        return if (info != null) DevKitDetection.DevKit(info) else DevKitDetection.NotDevKit
    }

    private fun doDetect(jar: Path): DevKitJarInfo? {
        val file = try {
            jar.toFile()
        } catch (_: UnsupportedOperationException) {
            return doDetectStreaming(jar)
        }

        // only the central directory and the manifest are read, nothing else is unpacked
        JarFile(file, false).use { jarFile ->
            if (jarFile.manifest?.mainAttributes?.getValue(MANIFEST_ATTRIBUTE) != "true") {
                return null
            }

            val ideMappings = jarFile.getJarEntry(IDE_MAPPINGS_PATH)?.let { entry ->
                jarFile.getInputStream(entry).use { it.readBytes().decodeToString() }
            }

            return devKitInfo(jarFile.stream().map { it.name }.iterator().asSequence(), ideMappings)
        }
    }

    // for paths that are not backed by a regular file: reads the jar sequentially
    private fun doDetectStreaming(jar: Path): DevKitJarInfo? {
        var isMultiRelease: Boolean? = null
        var ideMappings: String? = null
        val names = mutableListOf<String>()

        ZipInputStream(Files.newInputStream(jar)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == MANIFEST_PATH) {
                    isMultiRelease = Manifest(zis).mainAttributes.getValue(MANIFEST_ATTRIBUTE) == "true"

                    // the manifest usually goes first, so regular jars are not read any further
                    if (!isMultiRelease) {
                        return null
                    }
                } else {
                    if (entry.name == IDE_MAPPINGS_PATH) {
                        ideMappings = zis.readBytes().decodeToString()
                    }
                    names.add(entry.name)
                }

                entry = zis.nextEntry
            }
        }

        if (isMultiRelease != true) {
            return null
        }

        return devKitInfo(names.asSequence(), ideMappings)
    }

    private fun devKitInfo(entryNames: Sequence<String>, ideMappings: String?): DevKitJarInfo? {
        val versions = LinkedHashMap<String, LinkedHashSet<String>>()

        entryNames.forEach { name ->
            versionEntryRegex.find(name)?.let { match ->
                val (pluginId, version) = match.destructured
                versions.getOrPut(pluginId) { LinkedHashSet() }.add(version)
            }
        }

        // the dev kit expects the plugin id to be the only directory there
        val (pluginId, kotlinVersions) = versions.entries.singleOrNull() ?: return null

        return DevKitJarInfo(
            pluginId = pluginId,
            kotlinVersions = kotlinVersions.toList(),
            ideMappings = ideMappings?.let { KefsDevKitVersions.parseIdeMappings(it) }.orEmpty(),
        )
    }

    fun stampOf(path: Path): FileStamp? {
        return try {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
            if (attributes.isRegularFile) {
                FileStamp(attributes.size(), attributes.lastModifiedTime().toMillis())
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Splits a jar name into an artifact id and a version: `my-plugin-1.2.0.jar` -> (`my-plugin`, `1.2.0`).
     */
    fun artifactAndVersion(jar: Path): Pair<String, String> {
        val baseName = jar.name.removeSuffix(".jar")
        val match = artifactVersionRegex.matchEntire(baseName)
            ?: return baseName to UNKNOWN_VERSION

        val (artifact, version) = match.destructured
        return artifact to version
    }

    /**
     * Every content of a source jar gets its own file name in the cache.
     * A jar that is already loaded by the IDE is never overwritten this way.
     */
    fun cachedJarName(artifact: String, sourceId: String, checksum: String): String {
        return "$artifact-$sourceId-${checksum.take(CACHED_CHECKSUM_LENGTH)}.jar"
    }

    /**
     * The cache dir is shared between projects and IDEs. The id tells apart the jars of the same artifact
     * that come from different locations, so one project never deletes the jars of another one.
     */
    fun sourceId(source: Path): String {
        return "%08x".format(source.invariantSeparatorsPathString.hashCode())
    }

    fun isCachedJarOf(artifact: String, sourceId: String, fileName: String): Boolean {
        val prefix = "$artifact-$sourceId-"
        if (!fileName.startsWith(prefix) || !fileName.endsWith(".jar")) {
            return false
        }

        // `my-plugin-cli-<source>-<checksum>.jar` is not a jar of `my-plugin`
        val checksum = fileName.substring(prefix.length, fileName.length - ".jar".length)
        return cachedChecksumRegex.matches(checksum)
    }

    /**
     * Maps an entry of a dev kit jar to the path of a class as it is seen in runtime.
     * Returns null for embedded dependencies: they are regular libraries
     * and exceptions with their frames can't be attributed to the plugin.
     */
    fun runtimeClassPath(entryName: String): String? {
        val versioned = versionedPrefixRegex.find(entryName)
        val path = if (versioned != null) entryName.substring(versioned.range.last + 1) else entryName

        return if (dependencyRegex.containsMatchIn(path)) null else path
    }

    fun repository(source: Path): KotlinArtifactsRepository {
        return KotlinArtifactsRepository(
            name = REPOSITORY_NAME,
            value = source.toString(),
            type = KotlinArtifactsRepository.Type.PATH,
        )
    }
}

internal class KefsDevKitState : BaseState() {
    // line separated plugin ids
    var disabledPlugins by string("")
}

/**
 * Dev kit plugins are not configured in settings, they are discovered when the IDE requests them.
 * The registry keeps the ones seen in this session and remembers which of them were disabled.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "com.github.mr3zee.kotlinPlugins.KotlinPluginsDevKit",
    storages = [Storage(WORKSPACE_FILE)],
)
internal class KefsDevKitRegistry : SimplePersistentStateComponent<KefsDevKitState>(KefsDevKitState()) {
    private val artifacts = ConcurrentHashMap<String, List<String>>()

    /**
     * Returns true if the artifact was not known before.
     */
    fun register(pluginId: String, artifact: String): Boolean {
        var new = false
        artifacts.compute(pluginId) { _, old ->
            if (old != null && artifact in old) {
                old
            } else {
                new = true
                old.orEmpty() + artifact
            }
        }
        return new
    }

    fun isDevKit(pluginName: String): Boolean = artifacts.containsKey(pluginName)

    fun isEnabled(pluginName: String): Boolean = pluginName !in disabled()

    /**
     * Returns true if the state of any known dev kit plugin was changed.
     */
    fun setEnabled(pluginNames: Set<String>, enabled: Boolean): Boolean {
        val known = pluginNames.filter { isDevKit(it) }.toSet()
        val old = disabled()
        val new = if (enabled) old - known else old + known
        if (new == old) {
            return false
        }

        state.disabledPlugins = new.joinToString("\n")
        return true
    }

    fun descriptors(): List<KotlinPluginDescriptor> {
        return artifacts.keys.sorted().mapNotNull { descriptorByName(it) }
    }

    fun descriptorByName(pluginName: String): KotlinPluginDescriptor? {
        val ids = artifacts[pluginName] ?: return null

        return KotlinPluginDescriptor(
            name = pluginName,
            ids = ids.map { MavenId(it) },
            versionMatching = KotlinPluginDescriptor.VersionMatching.EXACT,
            enabled = isEnabled(pluginName),
            ignoreExceptions = false,
            repositories = emptyList(),
            replacement = null,
        )
    }

    private fun disabled(): Set<String> {
        return state.disabledPlugins.orEmpty().lines().filter { it.isNotBlank() }.toSet()
    }
}

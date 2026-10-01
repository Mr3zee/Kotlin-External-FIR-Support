package com.github.mr3zee.kefs

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText

class KefsDevKitTest {
    private lateinit var tempDir: Path

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("kefs-devkit-test")
    }

    @OptIn(ExperimentalPathApi::class)
    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun jar(name: String, multiRelease: Boolean, vararg entries: String): Path {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            if (multiRelease) {
                mainAttributes.putValue(KefsDevKit.MANIFEST_ATTRIBUTE, "true")
            }
        }

        val path = tempDir.resolve(name)
        JarOutputStream(Files.newOutputStream(path), manifest).use { jos ->
            entries.forEach {
                jos.putNextEntry(ZipEntry(it))
                jos.closeEntry()
            }
        }
        return path
    }

    private val devKitEntries = arrayOf(
        "com/example/PluginInfo.class",
        "META-INF/kotlin/plugin/com.example/versions/2.2.0/com/example/Registrar.class",
        "META-INF/kotlin/plugin/com.example/versions/2.3.20-ij253-45/com/example/Registrar.class",
        "META-INF/kotlin/plugin/com.example/versions/2.3.20-ij253-45/com/example/Registrar\$Nested.class",
        "META-INF/kotlin/plugin/com.example/dependencies/lib-1.0/org/lib/Util.class",
        "META-INF/kotlin/plugin/com.example/dependencies/runtime-1.0/META-INF/kotlin/plugin/devkit/versions/2.2.0/org/devkit/Runtime.class",
    )

    @Test
    fun `detects dev kit jar`() {
        val info = KefsDevKit.detect(jar("plugin-1.0.0.jar", multiRelease = true, *devKitEntries))

        assertNotNull(info)
        assertEquals("com.example", info!!.pluginId)
        assertEquals(listOf("2.2.0", "2.3.20-ij253-45"), info.kotlinVersions)
    }

    @Test
    fun `ignores jars without the manifest attribute`() {
        assertNull(KefsDevKit.detect(jar("plugin-1.0.0.jar", multiRelease = false, *devKitEntries)))
    }

    @Test
    fun `ignores jars without versions`() {
        assertNull(KefsDevKit.detect(jar("plugin-1.0.0.jar", multiRelease = true, "com/example/PluginInfo.class")))
    }

    @Test
    fun `ignores broken and missing jars`() {
        val broken = tempDir.resolve("broken.jar").apply { writeText("not a jar") }

        assertNull(KefsDevKit.detect(broken))
        assertNull(KefsDevKit.detect(tempDir.resolve("missing.jar")))
    }

    @Test
    fun `splits jar name into artifact and version`() {
        assertEquals("my-plugin" to "1.2.0", KefsDevKit.artifactAndVersion(Path.of("libs/my-plugin-1.2.0.jar")))
        assertEquals(
            "compiler-plugin-k2" to "0.1.0-dev-123",
            KefsDevKit.artifactAndVersion(Path.of("compiler-plugin-k2-0.1.0-dev-123.jar")),
        )
        assertEquals(
            "my-plugin" to KefsDevKit.UNKNOWN_VERSION,
            KefsDevKit.artifactAndVersion(Path.of("my-plugin.jar")),
        )
    }

    @Test
    fun `cached jar name depends on content`() {
        val name = KefsDevKit.cachedJarName("my-plugin", "0123456789abcdef0123456789abcdef")

        assertEquals("my-plugin-0123456789ab.jar", name)
        assertEquals(true, KefsDevKit.isCachedJarOf("my-plugin", name))
        assertEquals(false, KefsDevKit.isCachedJarOf("other-plugin", name))
    }

    @Test
    fun `cached jar of an artifact with a longer name is not matched`() {
        val name = KefsDevKit.cachedJarName("my-plugin-cli", "0123456789abcdef0123456789abcdef")

        assertEquals(true, KefsDevKit.isCachedJarOf("my-plugin-cli", name))
        assertEquals(false, KefsDevKit.isCachedJarOf("my-plugin", name))
        assertEquals(false, KefsDevKit.isCachedJarOf("my-plugin", "my-plugin-0123456789ab.jar.tmp"))
    }

    @Test
    fun `unreadable jars are told apart from regular ones`() {
        val regular = jar("regular-1.0.0.jar", multiRelease = false, *devKitEntries)
        val devKit = jar("plugin-1.0.0.jar", multiRelease = true, *devKitEntries)

        // a jar that is still being written has no central directory yet
        val partial = tempDir.resolve("partial-1.0.0.jar")
        Files.write(partial, Files.readAllBytes(devKit).copyOf(Files.size(devKit).toInt() / 2))

        assertEquals(DevKitDetection.NotDevKit, KefsDevKit.inspect(regular))
        assertEquals(DevKitDetection.Incomplete, KefsDevKit.inspect(partial))
        assertEquals("com.example", (KefsDevKit.inspect(devKit) as DevKitDetection.DevKit).info.pluginId)
    }

    @Test
    fun `analyzer maps versioned classes and skips dependencies`(): Unit = runBlocking {
        val result = KefsJarAnalyzer.analyze(jar("plugin-1.0.0.jar", multiRelease = true, *devKitEntries))

        assertEquals(
            KefsAnalyzedJar.Success(
                setOf(
                    "com.example.PluginInfo",
                    "com.example.Registrar",
                    "com.example.Registrar.Nested",
                    "org.devkit.Runtime",
                )
            ),
            result,
        )
    }
}

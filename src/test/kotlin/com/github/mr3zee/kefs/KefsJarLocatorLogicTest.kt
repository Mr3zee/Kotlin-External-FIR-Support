package com.github.mr3zee.kefs

import org.junit.Assert.*
import org.junit.Test

class KefsJarLocatorLogicTest {

    // -- parseManifestXmlToVersions tests --

    @Test
    fun `parseManifestXmlToVersions valid maven metadata returns version list`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata>
              <groupId>org.example</groupId>
              <artifactId>my-artifact</artifactId>
              <versioning>
                <latest>2.0.0</latest>
                <release>2.0.0</release>
                <versions>
                  <version>1.0.0</version>
                  <version>1.5.0</version>
                  <version>2.0.0</version>
                </versions>
                <lastUpdated>20250101000000</lastUpdated>
              </versioning>
            </metadata>
        """.trimIndent()

        val versions = parseManifestXmlToVersions(xml)
        assertEquals(listOf("1.0.0", "1.5.0", "2.0.0"), versions)
    }

    @Test
    fun `parseManifestXmlToVersions malformed XML returns empty list`() {
        val xml = "this is not xml at all <><><>"
        val versions = parseManifestXmlToVersions(xml)
        assertEquals(emptyList<String>(), versions)
    }

    @Test
    fun `parseManifestXmlToVersions missing versioning returns empty list`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata>
              <groupId>org.example</groupId>
              <artifactId>my-artifact</artifactId>
            </metadata>
        """.trimIndent()

        val versions = parseManifestXmlToVersions(xml)
        assertEquals(emptyList<String>(), versions)
    }

    // -- accumulate tests --

    @Test
    fun `accumulate single FailedToFetch returned as-is`() {
        val failedToFetch = LocatorResult.FailedToFetch(
            state = ArtifactState.FailedToFetch("connection timeout"),
        )

        val result = KefsJarLocator.accumulate(
            failedToFetch = listOf(failedToFetch),
            notFound = emptyList(),
            cached = null,
        )

        assertTrue("Should return FailedToFetch", result is LocatorResult.FailedToFetch)
        assertEquals("connection timeout", (result as LocatorResult.FailedToFetch).state.message)
    }

    @Test
    fun `accumulate multiple FailedToFetch returns aggregated message`() {
        val f1 = LocatorResult.FailedToFetch(
            state = ArtifactState.FailedToFetch("timeout from repo1"),
        )
        val f2 = LocatorResult.FailedToFetch(
            state = ArtifactState.FailedToFetch("timeout from repo2"),
        )

        val result = KefsJarLocator.accumulate(
            failedToFetch = listOf(f1, f2),
            notFound = emptyList(),
            cached = null,
        )

        assertTrue("Should return FailedToFetch", result is LocatorResult.FailedToFetch)
        val message = (result as LocatorResult.FailedToFetch).state.message
        assertTrue("Should contain repo1 message", message.contains("timeout from repo1"))
        assertTrue("Should contain repo2 message", message.contains("timeout from repo2"))
    }

    @Test
    fun `accumulate single NotFound returned as-is`() {
        val notFound = LocatorResult.NotFound(
            state = ArtifactState.NotFound("no matching version"),
        )

        val result = KefsJarLocator.accumulate(
            failedToFetch = emptyList(),
            notFound = listOf(notFound),
            cached = null,
        )

        assertTrue("Should return NotFound", result is LocatorResult.NotFound)
        assertEquals("no matching version", (result as LocatorResult.NotFound).state.message)
    }

    @Test
    fun `accumulate multiple NotFound returns aggregated message`() {
        val n1 = LocatorResult.NotFound(
            state = ArtifactState.NotFound("not in repo1"),
        )
        val n2 = LocatorResult.NotFound(
            state = ArtifactState.NotFound("not in repo2"),
        )

        val result = KefsJarLocator.accumulate(
            failedToFetch = emptyList(),
            notFound = listOf(n1, n2),
            cached = null,
        )

        assertTrue("Should return NotFound", result is LocatorResult.NotFound)
        val message = (result as LocatorResult.NotFound).state.message
        assertTrue("Should contain repo1 message", message.contains("not in repo1"))
        assertTrue("Should contain repo2 message", message.contains("not in repo2"))
    }

    @Test
    fun `accumulate mixed failures and notFound returns combined FailedToFetch message`() {
        val f1 = LocatorResult.FailedToFetch(
            state = ArtifactState.FailedToFetch("fetch error"),
        )
        val n1 = LocatorResult.NotFound(
            state = ArtifactState.NotFound("not found error"),
        )

        val result = KefsJarLocator.accumulate(
            failedToFetch = listOf(f1),
            notFound = listOf(n1),
            cached = null,
        )

        assertTrue("Should return FailedToFetch for mixed failures", result is LocatorResult.FailedToFetch)
        val message = (result as LocatorResult.FailedToFetch).state.message
        assertTrue("Should contain fetch error", message.contains("fetch error"))
        assertTrue("Should contain not found error", message.contains("not found error"))
    }

    @Test
    fun `accumulate nothing returns unknown error FailedToFetch`() {
        val result = KefsJarLocator.accumulate(
            failedToFetch = emptyList(),
            notFound = emptyList(),
            cached = null,
        )

        assertTrue("Should return FailedToFetch", result is LocatorResult.FailedToFetch)
        assertEquals("Unknown error", (result as LocatorResult.FailedToFetch).state.message)
    }

    /**
     * When a cached result exists but the bundle is incomplete (some artifacts found, others not),
     * accumulate should return FoundButBundleIsIncomplete instead of the cached result.
     * This happens when moved() fails for some artifacts in a bundle.
     */
    @Test
    fun `accumulate with cached but incomplete bundle returns FoundButBundleIsIncomplete`() {
        val cached = LocatorResult.Cached(
            jar = Jar(
                path = java.nio.file.Path.of("/tmp/test.jar"),
                checksum = "abc123",
                isLocal = false,
                kotlinVersionMismatch = null,
            ),
            filter = MatchFilter("1.0.0".requested(), KotlinPluginDescriptor.VersionMatching.EXACT),
            origin = KotlinArtifactsRepository("test", "http://example.com", KotlinArtifactsRepository.Type.URL),
            resolvedVersion = "1.0.0".resolved(),
        )

        val result = KefsJarLocator.accumulate(
            failedToFetch = emptyList(),
            notFound = emptyList(),
            cached = cached,
        )

        assertTrue(
            "Should return FoundButBundleIsIncomplete when cached exists but bundle is incomplete",
            result is LocatorResult.FoundButBundleIsIncomplete,
        )
    }

    // -- md5 / checksum tests --

    @Test
    fun `md5 checksum is computed correctly`() {
        val tempFile = java.nio.file.Files.createTempFile("kefs-test", ".jar")
        try {
            tempFile.toFile().writeText("test content for checksum")
            val checksum = md5(tempFile).asChecksum()
            // MD5 checksum should be a 32-character hex string
            assertEquals("Checksum should be 32 hex chars", 32, checksum.length)
            assertTrue("Checksum should only contain hex characters", checksum.all { it in '0'..'9' || it in 'a'..'f' })

            // Same content should produce same checksum
            val checksum2 = md5(tempFile).asChecksum()
            assertEquals("Same file should produce same checksum", checksum, checksum2)
        } finally {
            java.nio.file.Files.deleteIfExists(tempFile)
        }
    }

    @Test
    fun `md5 checksum differs for different content`() {
        val file1 = java.nio.file.Files.createTempFile("kefs-test1", ".jar")
        val file2 = java.nio.file.Files.createTempFile("kefs-test2", ".jar")
        try {
            file1.toFile().writeText("content A")
            file2.toFile().writeText("content B")

            val checksum1 = md5(file1).asChecksum()
            val checksum2 = md5(file2).asChecksum()

            assertNotEquals("Different content should produce different checksums", checksum1, checksum2)
        } finally {
            java.nio.file.Files.deleteIfExists(file1)
            java.nio.file.Files.deleteIfExists(file2)
        }
    }
}

package com.github.mr3zee.kefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KefsDevKitVersionsTest {
    private val pluginVersions = listOf(
        "2.4.0",
        "2.3.20",
        "2.3.21",
        "2.4.10",
        "2.4.20-Beta1",
        "2.4.20-Beta2",
        "2.4.20-RC",
        "2.4.20-dev-6724",
        "2.5.0-dev-9401",
    )

    private val ideMappings = mapOf(
        "253.29346.240" to "2.3.0-dev-9992",
        "261.26222.65" to "2.4.0-dev-2631",
        "262.9437.185" to "2.4.20-dev-6724",
    )

    private fun compatibility(
        ideBuild: String,
        kotlinVersion: String,
        versions: List<String> = pluginVersions,
        mappings: Map<String, String> = ideMappings,
    ) = KefsDevKitVersions.compatibility(versions, mappings, ideBuild, kotlinVersion)

    private fun resolved(ideBuild: String, kotlinVersion: String, versions: List<String> = pluginVersions): String {
        return (compatibility(ideBuild, kotlinVersion, versions) as DevKitCompatibility.Supported).pluginKotlinVersion
    }

    private fun version(value: String) = DevKitKotlinVersion.parse(value)!!

    @Test
    fun `ide older than every build of the plugin is not supported`() {
        val result = compatibility("253.29346.240", "2.3.20-ij253-87")

        assertTrue(result is DevKitCompatibility.Unsupported)
        assertEquals("2.3.0-dev-9992", (result as DevKitCompatibility.Unsupported).ideKotlinVersion)
    }

    @Test
    fun `ide builds are mapped to kotlin versions`() {
        assertEquals("2.4.0", resolved("261.26222.65", "2.4.0-ij261-71"))
        assertEquals("2.4.20-dev-6724", resolved("262.9437.185", "2.4.20-ij262-34"))
    }

    @Test
    fun `unknown ide build uses the closest older build of the same major`() {
        assertEquals("2.4.0", resolved("261.99999.1", "2.4.0-ij261-99"))
        // no older build of the same major: the reported version is used, the lowest one of the same base wins
        assertEquals("2.4.20-dev-6724", resolved("262.1.1", "2.4.20-ij262-1"))
        assertEquals("2.4.10", resolved("270.1.1", "2.4.10"))
    }

    @Test
    fun `stable version ignores dev builds of the plugin`() {
        assertEquals("2.4.10", resolved("", "2.4.10"))
        assertEquals("2.4.10", resolved("", "2.4.20-Beta1-12", versions = listOf("2.4.10", "2.4.20-Beta2", "2.4.20-dev-1")))
        assertEquals("2.4.20-RC", resolved("", "2.6.0"))
    }

    @Test
    fun `dev version prefers dev builds of the same base version`() {
        assertEquals("2.4.20-dev-6724", resolved("", "2.4.20-dev-7000"))
        // an older dev build of the same base version is treated as its base version
        assertEquals("2.4.20-RC", resolved("", "2.4.20-dev-100"))
        assertEquals("2.4.10", resolved("", "2.4.20-dev-100", versions = listOf("2.4.10", "2.4.20-dev-6724")))
        assertEquals("2.5.0-dev-9401", resolved("", "2.5.20-dev-1"))
    }

    @Test
    fun `ide newer than every build of the plugin is supported but marked`() {
        val newer = compatibility("", "2.6.0-dev-100") as DevKitCompatibility.Supported
        assertEquals("2.5.0-dev-9401", newer.pluginKotlinVersion)
        assertEquals(true, newer.isNewerIde)

        val inRange = compatibility("262.9437.185", "2.4.20-ij262-34") as DevKitCompatibility.Supported
        assertEquals(false, inRange.isNewerIde)

        val sameBase = compatibility("", "2.5.0-dev-9999") as DevKitCompatibility.Supported
        assertEquals(false, sameBase.isNewerIde)
    }

    @Test
    fun `unparsable versions give no answer`() {
        assertNull(compatibility("", "not-a-version"))
        assertNull(compatibility("", "2.4.0", versions = listOf("2.4.0", "latest")))
    }

    @Test
    fun `versions are ordered as in the dev kit`() {
        val ordered = listOf(
            "2.3.20-dev-100",
            "2.3.20-dev-200",
            "2.3.20-Beta1",
            "2.3.20-Beta2",
            "2.3.20-RC-200",
            "2.3.20-RC",
            "2.3.20-RC2",
            "2.3.20-1",
            "2.3.20",
            "2.3.21",
            "2.4.0-dev-1",
        ).map { version(it) }

        assertEquals(ordered, ordered.shuffled().sorted())
        assertEquals(version("2.4"), version("2.4.0"))
        assertEquals(true, version("2.4.0-ij261-64").isIdeBuild)
        assertEquals(true, version("2.4.0-ij261-64").isDev)
    }

    @Test
    fun `parses ide mappings`() {
        val mappings = KefsDevKitVersions.parseIdeMappings(
            """
                # IntelliJ IDEA 2025.3.1.1 (stable)
                253.29346.240=2.3.0-dev-9992

                broken line
                262.9437.185 = 2.4.20-dev-6724
            """.trimIndent()
        )

        assertEquals(mapOf("253.29346.240" to "2.3.0-dev-9992", "262.9437.185" to "2.4.20-dev-6724"), mappings)
    }
}

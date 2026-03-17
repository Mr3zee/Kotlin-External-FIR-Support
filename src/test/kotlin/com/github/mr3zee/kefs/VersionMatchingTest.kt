package com.github.mr3zee.kefs

import org.junit.Assert.*
import org.junit.Test

class VersionMatchingTest {

    // --- EXACT matching ---

    @Test
    fun `EXACT version present returns correct resolved version`() {
        val versions = listOf(listOf("2.2.0-1.0.0", "2.2.0-1.1.0", "2.2.0-2.0.0"))
        val filter = MatchFilter("1.1.0".requested(), KotlinPluginDescriptor.VersionMatching.EXACT)

        val result = getMatching(versions, "2.2.0-", filter)

        assertNotNull(result)
        assertEquals("1.1.0", result!!.value)
    }

    @Test
    fun `EXACT version absent returns null`() {
        val versions = listOf(listOf("2.2.0-1.0.0", "2.2.0-1.1.0"))
        val filter = MatchFilter("9.9.9".requested(), KotlinPluginDescriptor.VersionMatching.EXACT)

        val result = getMatching(versions, "2.2.0-", filter)

        assertNull(result)
    }

    @Test
    fun `EXACT match takes precedence even with LATEST matching`() {
        // The exact check runs before the matching strategy switch
        val versions = listOf(listOf("2.2.0-1.0.0", "2.2.0-2.0.0"))
        val filter = MatchFilter("1.0.0".requested(), KotlinPluginDescriptor.VersionMatching.LATEST)

        val result = getMatching(versions, "2.2.0-", filter)

        assertNotNull(result)
        assertEquals("1.0.0", result!!.value)
    }

    // --- SAME_MAJOR matching ---

    @Test
    fun `SAME_MAJOR selects highest in same major`() {
        // Use a requested version NOT in the list so the exact-match shortcut does not fire
        val versions = listOf(listOf("prefix-1.0.0", "prefix-1.2.0", "prefix-1.5.0", "prefix-2.0.0"))
        val filter = MatchFilter("1.0.1".requested(), KotlinPluginDescriptor.VersionMatching.SAME_MAJOR)

        val result = getMatching(versions, "prefix-", filter)

        assertNotNull(result)
        assertEquals("1.5.0", result!!.value)
    }

    @Test
    fun `SAME_MAJOR with no versions in same major returns null`() {
        val versions = listOf(listOf("prefix-2.0.0", "prefix-3.0.0"))
        val filter = MatchFilter("1.0.0".requested(), KotlinPluginDescriptor.VersionMatching.SAME_MAJOR)

        val result = getMatching(versions, "prefix-", filter)

        assertNull(result)
    }

    @Test
    fun `SAME_MAJOR with 0-dot-x versions matches by minor`() {
        // When major is "0", matching uses minor version instead
        // Use a requested version NOT in the list so the exact-match shortcut does not fire
        val versions = listOf(listOf("prefix-0.1.0", "prefix-0.1.5", "prefix-0.2.0", "prefix-0.2.3"))
        val filter = MatchFilter("0.1.1".requested(), KotlinPluginDescriptor.VersionMatching.SAME_MAJOR)

        val result = getMatching(versions, "prefix-", filter)

        assertNotNull(result)
        assertEquals("0.1.5", result!!.value)
    }

    @Test
    fun `SAME_MAJOR requires resolved at least requested`() {
        // Only version 1.0.0 is in major 1, but requested is 1.5.0 which is higher
        val versions = listOf(listOf("prefix-1.0.0", "prefix-2.0.0"))
        val filter = MatchFilter("1.5.0".requested(), KotlinPluginDescriptor.VersionMatching.SAME_MAJOR)

        val result = getMatching(versions, "prefix-", filter)

        assertNull(result)
    }

    // --- LATEST matching ---

    @Test
    fun `LATEST selects absolute highest version`() {
        // Use a requested version NOT in the list so the exact-match shortcut does not fire
        val versions = listOf(listOf("prefix-1.0.0", "prefix-2.0.0", "prefix-3.0.0"))
        val filter = MatchFilter("0.9.0".requested(), KotlinPluginDescriptor.VersionMatching.LATEST)

        val result = getMatching(versions, "prefix-", filter)

        assertNotNull(result)
        assertEquals("3.0.0", result!!.value)
    }

    @Test
    fun `LATEST with different max across multiple artifact lists returns null`() {
        // Two artifact lists have different maximums, so distinct().singleOrNull() returns null
        // Use a requested version NOT in any list so the exact-match shortcut does not fire
        val versions = listOf(
            listOf("prefix-1.0.0", "prefix-3.0.0"),
            listOf("prefix-1.0.0", "prefix-2.0.0"),
        )
        val filter = MatchFilter("0.5.0".requested(), KotlinPluginDescriptor.VersionMatching.LATEST)

        val result = getMatching(versions, "prefix-", filter)

        assertNull(result)
    }

    @Test
    fun `LATEST with same max across multiple artifact lists returns that version`() {
        // Use a requested version NOT in any list so the exact-match shortcut does not fire
        val versions = listOf(
            listOf("prefix-1.0.0", "prefix-3.0.0"),
            listOf("prefix-2.0.0", "prefix-3.0.0"),
        )
        val filter = MatchFilter("0.5.0".requested(), KotlinPluginDescriptor.VersionMatching.LATEST)

        val result = getMatching(versions, "prefix-", filter)

        assertNotNull(result)
        assertEquals("3.0.0", result!!.value)
    }

    @Test
    fun `LATEST requires resolved at least requested`() {
        val versions = listOf(listOf("prefix-1.0.0"))
        val filter = MatchFilter("2.0.0".requested(), KotlinPluginDescriptor.VersionMatching.LATEST)

        val result = getMatching(versions, "prefix-", filter)

        assertNull(result)
    }

    // --- Edge cases ---

    @Test
    fun `empty version list returns null`() {
        val versions = emptyList<List<String>>()
        val filter = MatchFilter("1.0.0".requested(), KotlinPluginDescriptor.VersionMatching.LATEST)

        val result = getMatching(versions, "prefix-", filter)

        assertNull(result)
    }

    @Test
    fun `empty inner list returns null`() {
        val versions = listOf(emptyList<String>())
        val filter = MatchFilter("1.0.0".requested(), KotlinPluginDescriptor.VersionMatching.LATEST)

        val result = getMatching(versions, "prefix-", filter)

        assertNull(result)
    }

    @Test
    fun `prefix filtering works correctly`() {
        // Use a requested version NOT in the list so the exact-match shortcut does not fire
        val versions = listOf(listOf("2.2.0-1.0.0", "2.1.0-1.0.0", "2.2.0-2.0.0"))
        val filter = MatchFilter("0.5.0".requested(), KotlinPluginDescriptor.VersionMatching.LATEST)

        // Only versions starting with "2.2.0-" should be considered (i.e. 1.0.0 and 2.0.0)
        val result = getMatching(versions, "2.2.0-", filter)

        assertNotNull(result)
        assertEquals("2.0.0", result!!.value)
    }

    @Test
    fun `prefix filtering excludes non-matching versions`() {
        val versions = listOf(listOf("other-1.0.0", "other-2.0.0"))
        val filter = MatchFilter("1.0.0".requested(), KotlinPluginDescriptor.VersionMatching.EXACT)

        val result = getMatching(versions, "prefix-", filter)

        assertNull(result)
    }

    @Test
    fun `replacement-style prefix filters and strips correctly`() {
        // Simulates a replacement pattern like <kotlin-version>-ij<lib-version>
        // where getVersionString("2.2.0", "") produces "2.2.0-ij"
        val versions = listOf(listOf("2.2.0-ij0.9.0", "2.2.0-ij1.0.0", "2.2.0-ij1.2.0", "2.2.0-1.5.0"))
        val filter = MatchFilter("0.5.0".requested(), KotlinPluginDescriptor.VersionMatching.LATEST)

        val result = getMatching(versions, "2.2.0-ij", filter)

        assertNotNull(result)
        // Only "2.2.0-ij*" versions are considered; "2.2.0-1.5.0" is excluded
        assertEquals("1.2.0", result!!.value)
    }

    @Test
    fun `replacement-style prefix does not match default-format versions`() {
        // Versions in default format should not match a replacement-derived prefix
        val versions = listOf(listOf("2.2.0-1.0.0", "2.2.0-2.0.0"))
        val filter = MatchFilter("1.0.0".requested(), KotlinPluginDescriptor.VersionMatching.EXACT)

        val result = getMatching(versions, "2.2.0-ij", filter)

        assertNull(result)
    }

    @Test
    fun `getVersionString with empty lib version produces correct prefix`() {
        val replacement = KotlinPluginDescriptor.Replacement(
            version = "<kotlin-version>-ij<lib-version>",
            detect = "<artifact-id>",
            search = "<artifact-id>",
        )

        val prefix = replacement.getVersionString("2.2.0", "")
        assertEquals("2.2.0-ij", prefix)

        // And the default-format equivalent
        val defaultReplacement = KotlinPluginDescriptor.Replacement(
            version = "<kotlin-version>-<lib-version>",
            detect = "<artifact-id>",
            search = "<artifact-id>",
        )
        val defaultPrefix = defaultReplacement.getVersionString("2.2.0", "")
        assertEquals("2.2.0-", defaultPrefix)
    }

    // --- SNAPSHOT matching ---

    @Test
    fun `SNAPSHOT exact match works when metadata lists SNAPSHOT version`() {
        val versions = listOf(listOf("2.2.0-1.0.0-SNAPSHOT", "2.2.0-1.0.0"))
        val filter = MatchFilter("1.0.0-SNAPSHOT".requested(), KotlinPluginDescriptor.VersionMatching.EXACT)

        val result = getMatching(versions, "2.2.0-", filter)

        assertNotNull(result)
        assertEquals("1.0.0-SNAPSHOT", result!!.value)
    }

    @Test
    fun `SNAPSHOT matches timestamped version in metadata`() {
        val versions = listOf(listOf("2.2.0-1.0.0-20260316.123456-1", "2.2.0-1.0.0"))
        val filter = MatchFilter("1.0.0-SNAPSHOT".requested(), KotlinPluginDescriptor.VersionMatching.EXACT)

        val result = getMatching(versions, "2.2.0-", filter)

        assertNotNull("SNAPSHOT should match timestamped version", result)
        assertEquals("1.0.0-20260316.123456-1", result!!.value)
    }

    @Test
    fun `SNAPSHOT matches latest timestamped version across multiple artifacts`() {
        val versions = listOf(
            listOf("2.2.0-1.0.0-20260316.123456-1", "2.2.0-1.0.0-20260317.654321-2"),
            listOf("2.2.0-1.0.0-20260316.123456-1", "2.2.0-1.0.0-20260317.654321-2"),
        )
        val filter = MatchFilter("1.0.0-SNAPSHOT".requested(), KotlinPluginDescriptor.VersionMatching.EXACT)

        val result = getMatching(versions, "2.2.0-", filter)

        assertNotNull(result)
        assertEquals("1.0.0-20260317.654321-2", result!!.value)
    }

    @Test
    fun `SNAPSHOT does not match unrelated versions with same base`() {
        // "1.0.0-beta1" should NOT match "1.0.0-SNAPSHOT"
        val versions = listOf(listOf("2.2.0-1.0.0-beta1", "2.2.0-1.0.0-rc1"))
        val filter = MatchFilter("1.0.0-SNAPSHOT".requested(), KotlinPluginDescriptor.VersionMatching.EXACT)

        val result = getMatching(versions, "2.2.0-", filter)

        assertNull("SNAPSHOT should not match non-snapshot qualifiers", result)
    }

    @Test
    fun `SNAPSHOT returns null when no matching versions exist`() {
        val versions = listOf(listOf("2.2.0-2.0.0", "2.2.0-3.0.0"))
        val filter = MatchFilter("1.0.0-SNAPSHOT".requested(), KotlinPluginDescriptor.VersionMatching.EXACT)

        val result = getMatching(versions, "2.2.0-", filter)

        assertNull(result)
    }

    @Test
    fun `SNAPSHOT with different max picks latest common version`() {
        val versions = listOf(
            listOf("2.2.0-1.0.0-20260316.123456-1", "2.2.0-1.0.0-20260317.654321-2"),
            listOf("2.2.0-1.0.0-20260316.123456-1"), // only has the older one
        )
        val filter = MatchFilter("1.0.0-SNAPSHOT".requested(), KotlinPluginDescriptor.VersionMatching.EXACT)

        val result = getMatching(versions, "2.2.0-", filter)

        assertNotNull("Should find the latest common snapshot version", result)
        assertEquals("1.0.0-20260316.123456-1", result!!.value)
    }

    @Test
    fun `SNAPSHOT with no common timestamps across artifacts returns null`() {
        val versions = listOf(
            listOf("2.2.0-1.0.0-20260316.123456-1"),
            listOf("2.2.0-1.0.0-20260317.654321-2"), // completely disjoint
        )
        val filter = MatchFilter("1.0.0-SNAPSHOT".requested(), KotlinPluginDescriptor.VersionMatching.EXACT)

        val result = getMatching(versions, "2.2.0-", filter)

        assertNull("No common snapshot versions should return null", result)
    }

    @Test
    fun `SNAPSHOT with LATEST matching falls through to timestamp matching`() {
        val versions = listOf(listOf("2.2.0-1.0.0-20260316.123456-1"))
        val filter = MatchFilter("1.0.0-SNAPSHOT".requested(), KotlinPluginDescriptor.VersionMatching.LATEST)

        val result = getMatching(versions, "2.2.0-", filter)

        assertNotNull(result)
        assertEquals("1.0.0-20260316.123456-1", result!!.value)
    }

    @Test
    fun `SNAPSHOT with SAME_MAJOR resolves regular version in same major`() {
        // Request 1.0.0-SNAPSHOT with SAME_MAJOR, only 1.5.0 available (no snapshot timestamps)
        val versions = listOf(listOf("2.2.0-1.5.0", "2.2.0-2.0.0"))
        val filter = MatchFilter("1.0.0-SNAPSHOT".requested(), KotlinPluginDescriptor.VersionMatching.SAME_MAJOR)

        val result = getMatching(versions, "2.2.0-", filter)

        assertNotNull("SAME_MAJOR should find version in major 1", result)
        assertEquals("1.5.0", result!!.value)
    }

    @Test
    fun `SNAPSHOT with SAME_MAJOR and 0-dot-x matches by minor`() {
        val versions = listOf(listOf("2.2.0-0.11.0", "2.2.0-0.11.5", "2.2.0-0.12.0"))
        val filter = MatchFilter("0.11.0-SNAPSHOT".requested(), KotlinPluginDescriptor.VersionMatching.SAME_MAJOR)

        val result = getMatching(versions, "2.2.0-", filter)

        assertNotNull("SAME_MAJOR with 0.x should match by minor", result)
        assertEquals("0.11.5", result!!.value)
    }

    // --- SNAPSHOT helper tests ---

    @Test
    fun `snapshotBaseOrNull returns base for SNAPSHOT version`() {
        assertEquals("1.0.0", "1.0.0-SNAPSHOT".snapshotBaseOrNull())
        assertEquals("0.11.0-grpc", "0.11.0-grpc-SNAPSHOT".snapshotBaseOrNull())
    }

    @Test
    fun `snapshotBaseOrNull returns null for non-SNAPSHOT version`() {
        assertNull("1.0.0".snapshotBaseOrNull())
        assertNull("1.0.0-beta1".snapshotBaseOrNull())
    }

    @Test
    fun `isSnapshotOf matches timestamped versions`() {
        assertTrue("0.11.0-20260316.123456-1".isSnapshotOf("0.11.0"))
        assertTrue("1.0.0-20261231.235959-99".isSnapshotOf("1.0.0"))
    }

    @Test
    fun `isSnapshotOf rejects non-timestamp suffixes`() {
        assertFalse("0.11.0-beta1".isSnapshotOf("0.11.0"))
        assertFalse("0.11.0-SNAPSHOT".isSnapshotOf("0.11.0"))
        assertFalse("0.11.0".isSnapshotOf("0.11.0"))
        assertFalse("0.12.0-20260316.123456-1".isSnapshotOf("0.11.0"))
    }

    // --- JarId equality ---

    @Test
    fun `JarId equality ignores resolvedVersion`() {
        val id1 = JarId("plugin", "org.example:art", "1.0.0".requested(), "1.0.0".resolved())
        val id2 = JarId("plugin", "org.example:art", "1.0.0".requested(), "2.0.0".resolved())

        assertEquals(id1, id2)
        assertEquals(id1.hashCode(), id2.hashCode())
    }

    @Test
    fun `JarId not equal when pluginName differs`() {
        val id1 = JarId("plugin-a", "org.example:art", "1.0.0".requested(), "1.0.0".resolved())
        val id2 = JarId("plugin-b", "org.example:art", "1.0.0".requested(), "1.0.0".resolved())

        assertNotEquals(id1, id2)
    }

    @Test
    fun `JarId not equal when mavenId differs`() {
        val id1 = JarId("plugin", "org.example:art-a", "1.0.0".requested(), "1.0.0".resolved())
        val id2 = JarId("plugin", "org.example:art-b", "1.0.0".requested(), "1.0.0".resolved())

        assertNotEquals(id1, id2)
    }

    @Test
    fun `JarId not equal when requestedVersion differs`() {
        val id1 = JarId("plugin", "org.example:art", "1.0.0".requested(), "1.0.0".resolved())
        val id2 = JarId("plugin", "org.example:art", "2.0.0".requested(), "1.0.0".resolved())

        assertNotEquals(id1, id2)
    }

    // --- RequestedPluginKey equality ---

    @Test
    fun `RequestedPluginKey equality works correctly`() {
        val key1 = RequestedPluginKey("org.example:art", "1.0.0".requested())
        val key2 = RequestedPluginKey("org.example:art", "1.0.0".requested())

        assertEquals(key1, key2)
        assertEquals(key1.hashCode(), key2.hashCode())
    }

    @Test
    fun `RequestedPluginKey not equal when mavenId differs`() {
        val key1 = RequestedPluginKey("org.example:art-a", "1.0.0".requested())
        val key2 = RequestedPluginKey("org.example:art-b", "1.0.0".requested())

        assertNotEquals(key1, key2)
    }

    @Test
    fun `RequestedPluginKey not equal when requestedVersion differs`() {
        val key1 = RequestedPluginKey("org.example:art", "1.0.0".requested())
        val key2 = RequestedPluginKey("org.example:art", "2.0.0".requested())

        assertNotEquals(key1, key2)
    }
}

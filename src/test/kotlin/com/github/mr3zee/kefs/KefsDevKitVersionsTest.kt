package com.github.mr3zee.kefs

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.util.BuildNumber
import org.junit.Assert.assertEquals
import org.junit.Test

class KefsDevKitVersionsTest {
    private fun compatibility(
        sinceBuild: BuildNumber,
        untilBuild: BuildNumber? = null,
        ideBuild: BuildNumber = ApplicationInfo.getInstance().build,
    ) = KefsDevKitVersions.compatibility(sinceBuild, untilBuild, ideBuild)

    private fun build(value: String) = BuildNumber.fromString(value)!!

    @Test
    fun `ide at untilBuild is supported`() {
        assertEquals(
            DevKitCompatibility.Supported,
            compatibility(build("263"), build("263.1234"), ideBuild = build("263.1234"))
        )
    }

    @Test
    fun `ide at sinceBuild is supported`() {
        assertEquals(DevKitCompatibility.Supported, compatibility(build("263.1234"), ideBuild = build("263.1234")))
    }

    @Test
    fun `ide older than plugin is not supported`() {
        assertEquals(DevKitCompatibility.TooOld, compatibility(build("271"), ideBuild = build("263")))
    }

    @Test
    fun `ide newer than plugin is not supported`() {
        assertEquals(DevKitCompatibility.TooNew, compatibility(build("261"), build("263"), ideBuild = build("271")))
    }
}

package com.github.mr3zee.kefs

import com.intellij.openapi.util.BuildNumber

/**
 * Tells which build of a dev kit plugin is picked for the IDE.
 */
internal enum class DevKitCompatibility { TooOld, TooNew, Supported }

/**
 * Checks version compatibility based on `sinceBuild` and `untilBuild` range specified by the DevKit jar.
 */
internal object KefsDevKitVersions {
    fun compatibility(
        sinceBuild: BuildNumber,
        untilBuild: BuildNumber?,
        ideBuild: BuildNumber,
    ): DevKitCompatibility = when {
        sinceBuild > ideBuild -> DevKitCompatibility.TooOld
        untilBuild != null && untilBuild < ideBuild -> DevKitCompatibility.TooNew
        else -> DevKitCompatibility.Supported
    }
}

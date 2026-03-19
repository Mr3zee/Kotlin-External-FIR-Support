package com.github.mr3zee.kefs

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val NL = System.lineSeparator()

internal fun formatExceptionReport(
    report: ExceptionsReport,
    kotlinIdeVersion: String,
    ideBuildNumber: String,
    isAndroidStudio: Boolean,
    rawKotlinVersion: String,
): String {
    val androidStudioInfo = if (isAndroidStudio) {
        """
Android Studio: yes
Raw Kotlin version (stub): $rawKotlinVersion
        """.trimIndent()
    } else {
        "Android Studio: no"
    }

    return """
KEFS Report for ${report.pluginName} (${report.mavenId})

IDE build number: $ideBuildNumber
$androidStudioInfo
Kotlin IDE version: $kotlinIdeVersion
Kotlin version mismatch: ${report.kotlinVersionMismatch ?: "none"}
Requested version: ${report.requestedVersion}
Resolved version: ${report.resolvedVersion}
Origin repository: ${report.origin.value}
Checksum: ${report.checksum}
Is probably incompatible: ${report.isProbablyIncompatible}

Exceptions:
${report.exceptions.joinToString("$NL$NL") { it.stackTraceToString() }}
    """.trimIndent()
}

internal fun formatReportFilename(pluginName: String, mavenId: String, now: Instant): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")
        .withZone(ZoneId.systemDefault())
    val nowFormatted = formatter.format(now)
    return "${pluginName}-${mavenId}-${nowFormatted}"
        .replace(":", "-")
        .replace(".", "-")
        .plus(".txt")
}

internal fun Throwable.distinctStacktrace(lookup: Set<String>): String {
    return stackTrace.filter { it.className in lookup }.joinToString("|") { it.toString() } +
            "|" +
            cause?.distinctStacktrace(lookup).orEmpty()
}

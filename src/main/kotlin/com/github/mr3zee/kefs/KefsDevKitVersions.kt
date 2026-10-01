package com.github.mr3zee.kefs

/**
 * Tells which build of a dev kit plugin is picked for the IDE.
 */
internal sealed interface DevKitCompatibility {
    /**
     * @param ideKotlinVersion the Kotlin version the dev kit uses for the IDE, after its IDE build mappings are applied.
     * @param pluginKotlinVersion the build of the plugin that is picked for it.
     * @param isNewerIde the IDE is newer than every build of the plugin: the newest one is loaded,
     * but nothing guarantees it to work.
     */
    class Supported(
        val ideKotlinVersion: String,
        val pluginKotlinVersion: String,
        val isNewerIde: Boolean,
    ) : DevKitCompatibility

    // the IDE is older than every build of the plugin, the dev kit has no code to load
    class Unsupported(val ideKotlinVersion: String) : DevKitCompatibility
}

/**
 * Repeats the version resolution a dev kit jar does at runtime (`CompilerVersionAliases` and `VersionResolution`
 * of the dev kit), so an unsupported IDE is reported by KEFS instead of a `ClassNotFoundException` from the jar.
 */
internal object KefsDevKitVersions {
    /**
     * Returns null when the versions can't be parsed, nothing is known about the compatibility then.
     *
     * @param ideMappings IDE build to Kotlin version mappings bundled into the jar.
     * @param kotlinVersion the Kotlin version reported by the IDE, without any KEFS specific resolution.
     */
    fun compatibility(
        pluginKotlinVersions: List<String>,
        ideMappings: Map<String, String>,
        ideBuild: String,
        kotlinVersion: String,
    ): DevKitCompatibility? {
        val current = ideKotlinVersion(ideMappings, ideBuild, kotlinVersion) ?: return null
        val versions = pluginKotlinVersions.associateBy { DevKitKotlinVersion.parse(it) ?: return null }

        val resolved = resolve(current, versions.keys.toList())
            ?: return DevKitCompatibility.Unsupported(current.toString())

        return DevKitCompatibility.Supported(
            ideKotlinVersion = current.toString(),
            pluginKotlinVersion = versions.getValue(resolved),
            isNewerIde = versions.keys.all { it.baseVersion() < current.baseVersion() },
        )
    }

    /**
     * The dev kit maps IDE builds to the Kotlin versions they are built from,
     * a build that is not listed uses the closest older build of the same IDE major.
     */
    fun ideKotlinVersion(ideMappings: Map<String, String>, ideBuild: String, kotlinVersion: String): DevKitKotlinVersion? {
        val alias = ideBuildNumber(ideBuild)?.let { build ->
            val builds = ideMappings.mapNotNull { (key, value) -> ideBuildNumber(key)?.let { it to value } }

            builds.find { (key, _) -> key == build }?.second
                ?: builds
                    .filter { (key, _) -> key.first() == build.first() && buildComparator.compare(key, build) < 0 }
                    .maxWithOrNull { a, b -> buildComparator.compare(a.first, b.first) }
                    ?.second
        }

        return DevKitKotlinVersion.parse(alias ?: kotlinVersion)
    }

    fun parseIdeMappings(text: String): Map<String, String> {
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split('=')
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }
            .toMap()
    }

    /**
     * Picks the highest build of the plugin that is not newer than the [current] version.
     * Dev versions are special: the ones of the same base version are compared by their build numbers first,
     * otherwise a dev version is treated as its base version when compared to the non-dev ones.
     */
    fun resolve(current: DevKitKotlinVersion, versions: List<DevKitKotlinVersion>): DevKitKotlinVersion? {
        if (current.isIdeBuild) {
            // IDE build numbers are not comparable with the Kotlin ones
            versions.filter { it.hasSameBaseVersionAs(current) }.minOrNull()?.let { return it }
        }

        if (current.isDev) {
            versions.filter { it.isDev && it.hasSameBaseVersionAs(current) && current >= it }.maxOrNull()?.let { return it }

            val base = current.baseVersion()
            return versions.filter { if (it.isDev) current >= it else base >= it }.maxOrNull()
        }

        return versions.filter { !it.isDev && current >= it }.maxOrNull()
    }

    private fun ideBuildNumber(build: String): List<Int>? {
        return build.split('.').map { it.toIntOrNull() ?: return null }
    }

    private val buildComparator = Comparator<List<Int>> { a, b ->
        for (i in 0 until minOf(a.size, b.size)) {
            if (a[i] != b[i]) {
                return@Comparator a[i].compareTo(b[i])
            }
        }
        a.size.compareTo(b.size)
    }
}

/**
 * A Kotlin version as the dev kit understands it: its `KotlinToolingVersion` with the same ordering.
 */
internal class DevKitKotlinVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val classifier: String?,
) : Comparable<DevKitKotlinVersion> {
    enum class Maturity { SNAPSHOT, DEV, MILESTONE, ALPHA, BETA, RC, STABLE }

    val maturity: Maturity = run {
        val classifier = classifier?.lowercase()
        when {
            classifier == null || classifier.matches(stableRegex) -> Maturity.STABLE
            classifier == "snapshot" -> Maturity.SNAPSHOT
            classifier.matches(rcRegex) -> Maturity.RC
            classifier.matches(betaRegex) -> Maturity.BETA
            classifier.matches(alphaRegex) -> Maturity.ALPHA
            classifier.matches(milestoneRegex) -> Maturity.MILESTONE
            else -> Maturity.DEV
        }
    }

    val isDev: Boolean get() = maturity == Maturity.DEV

    val isIdeBuild: Boolean get() = classifier?.startsWith("ij", ignoreCase = true) == true

    // e.g. 2 for rc2, dev versions never have it
    private val classifierNumber: Int? = when {
        classifier == null || isDev || classifier.matches(buildNumberOnlyRegex) -> null
        else -> classifierRegex.matchEntire(classifier)?.groupValues?.getOrNull(2)?.toIntOrNull()
    }

    private val buildNumber: Int? = when {
        classifier == null -> null
        classifier.matches(buildNumberOnlyRegex) -> classifier.toIntOrNull()
        else -> classifierRegex.matchEntire(classifier)?.groupValues?.getOrNull(4)?.toIntOrNull()
    }

    fun baseVersion(): DevKitKotlinVersion = DevKitKotlinVersion(major, minor, patch, null)

    fun hasSameBaseVersionAs(other: DevKitKotlinVersion): Boolean {
        return major == other.major && minor == other.minor && patch == other.patch
    }

    override fun compareTo(other: DevKitKotlinVersion): Int {
        if (this == other) return 0

        (major - other.major).takeIf { it != 0 }?.let { return it }
        (minor - other.minor).takeIf { it != 0 }?.let { return it }
        (patch - other.patch).takeIf { it != 0 }?.let { return it }
        (maturity.ordinal - other.maturity.ordinal).takeIf { it != 0 }?.let { return it }

        // 2.3.20 > 2.3.20-200
        if (classifier == null && other.classifier != null) return 1
        if (classifier != null && other.classifier == null) return -1

        compareNumbers(classifierNumber, other.classifierNumber, missing = -1).takeIf { it != 0 }?.let { return it }

        // 2.3.20-RC > 2.3.20-RC-200
        return compareNumbers(buildNumber, other.buildNumber, missing = 1)
    }

    // 'missing' is the result for the case when only this number is absent
    private fun compareNumbers(a: Int?, b: Int?, missing: Int): Int {
        return when {
            a != null && b != null -> a - b
            a == null && b != null -> missing
            a != null -> -missing
            else -> 0
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DevKitKotlinVersion) return false

        return major == other.major && minor == other.minor && patch == other.patch &&
                classifier?.lowercase() == other.classifier?.lowercase()
    }

    override fun hashCode(): Int {
        var result = major
        result = 31 * result + minor
        result = 31 * result + patch
        result = 31 * result + (classifier?.lowercase()?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "$major.$minor.$patch" + if (classifier != null) "-$classifier" else ""
    }

    companion object {
        private val stableRegex = """(release-)?\d+""".toRegex()
        private val rcRegex = """(rc)(\d*)?(-release)?(-?\d+)?""".toRegex()
        private val betaRegex = """beta(\d*)?(-release)?(-?\d+)?""".toRegex()
        private val alphaRegex = """alpha(\d*)?(-release)?(-?\d+)?""".toRegex()
        private val milestoneRegex = """m\d+(-release)?(-\d+)?""".toRegex()

        private val buildNumberOnlyRegex = """\d+""".toRegex()
        private val classifierRegex = """(.+?)(\d*)?(-release)?-?(\d*)?""".toRegex()

        fun parse(version: String): DevKitKotlinVersion? {
            val parts = version.split("-", limit = 2)
            val base = parts[0].split(".")

            return DevKitKotlinVersion(
                major = base[0].toIntOrNull() ?: return null,
                minor = base.getOrNull(1)?.toIntOrNull() ?: return null,
                patch = base.getOrNull(2)?.toIntOrNull() ?: 0,
                classifier = parts.getOrNull(1),
            )
        }
    }
}

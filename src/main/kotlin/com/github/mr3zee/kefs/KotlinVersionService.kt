package com.github.mr3zee.kefs

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import org.jetbrains.kotlin.idea.fir.extensions.KotlinK2BundledCompilerPlugins
import java.util.Properties

private val ANDROID_STUDIO_DEV_REGEX = Regex("""^(\d+\.\d+)\.255-dev-\d+$""")

@Service
public class KotlinVersionService {
    private val logger by lazy { thisLogger() }

    private val rawVersion by lazy {
        KotlinK2BundledCompilerPlugins::class.java.classLoader
            .getResourceAsStream("META-INF/compiler.version")?.use { stream ->
                stream.readAllBytes().decodeToString()
            }?.trim() ?: error("Kotlin version file not found")
    }

    private val version by lazy {
        resolveAndroidStudioVersion(rawVersion) ?: rawVersion
    }

    /**
     * Returns the effective Kotlin version to use for artifact resolution.
     * For standard IntelliJ IDEA, this is the raw version from `META-INF/compiler.version`.
     * For Android Studio (which uses stub versions like `2.2.255-dev-255`),
     * this is the real Kotlin version resolved from the bundled mapping.
     */
    public fun getKotlinIdePluginVersion(): String = version

    /**
     * Returns the raw Kotlin version string from `META-INF/compiler.version`
     * without any Android Studio resolution. Use for diagnostics and display.
     */
    public fun getRawKotlinIdePluginVersion(): String = rawVersion

    /**
     * Returns `true` if running in Android Studio with a resolved Kotlin version
     * that differs from the raw stub version.
     */
    public fun isAndroidStudio(): Boolean = rawVersion != version

    /**
     * Returns the IDE build number without the product code prefix.
     * E.g. `252.28238.7.2523.14688667` for Android Studio build `AI-252.28238.7.2523.14688667`.
     */
    public fun getIdeBuildNumber(): String =
        ApplicationInfo.getInstance().build.asStringWithoutProductCode()

    private fun resolveAndroidStudioVersion(raw: String): String? {
        if (!ANDROID_STUDIO_DEV_REGEX.matches(raw)) return null

        val buildStr = ApplicationInfo.getInstance().build.asStringWithoutProductCode()
        val platformBuild = buildStr.split(".").take(3).joinToString(".")

        val resolved = loadAndroidStudioMapping()[platformBuild]

        if (resolved != null) {
            logger.info(
                "Android Studio detected: resolved stub version $raw to $resolved " +
                        "(platform build: $platformBuild, IDE build: $buildStr)"
            )
        } else {
            logger.warn(
                "Android Studio detected with stub version $raw, " +
                        "but no mapping found for platform build $platformBuild (IDE build: $buildStr). " +
                        "Update android-studio-kotlin-versions.properties."
            )
        }

        return resolved
    }

    private fun loadAndroidStudioMapping(): Map<String, String> {
        val properties = Properties()
        KotlinVersionService::class.java.classLoader
            .getResourceAsStream("android-studio-kotlin-versions.properties")
            ?.use { properties.load(it) }
            ?: run {
                logger.warn("android-studio-kotlin-versions.properties not found in resources")
                return emptyMap()
            }

        return properties.entries.associate { (key, value) ->
            key.toString() to value.toString()
        }
    }
}

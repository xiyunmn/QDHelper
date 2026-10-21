plugins {
    id("qdhelper.android.application")
}

val majorVersion = 1
val minorVersion = 0
val patchVersion = 0
val baseVersionName = "$majorVersion.$minorVersion.$patchVersion"
val gitCommitCount = providers.exec {
    workingDir(rootProject.projectDir)
    commandLine("git", "-c", "safe.directory=${rootProject.projectDir.absolutePath}", "rev-list", "--count", "HEAD")
}.standardOutput.asText.get().trim().toInt()
require(gitCommitCount in 1..1_000_000_000) { "Git revision count is outside the supported version range" }
val revisionVersionCode = 100000 + gitCommitCount * 2
val ciRevision = providers.gradleProperty("CI_VERSION_NAME").orNull
require(ciRevision == null || ciRevision.matches(Regex("r[0-9]+\\.[0-9a-f]{7,40}"))) {
    "CI_VERSION_NAME must be r<revision>.<short SHA>"
}
val ciVersionName = ciRevision?.let { "$baseVersionName.$it" }

fun versionNameForBuildType(buildType: String): String = ciVersionName ?: when (buildType) {
    "debug" -> "$baseVersionName-debug"
    "beta" -> "$baseVersionName-beta"
    else -> baseVersionName
}
fun versionCodeForBuildType(buildType: String): Int =
    revisionVersionCode + if (buildType == "release" && ciVersionName == null) 1 else 0

val releaseKeystoreFile = providers.environmentVariable("RELEASE_KEYSTORE_FILE").orNull
val releaseKeystorePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseKeystoreFile,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

if (ciRevision != null) {
    require(hasReleaseSigning) { "CI builds require all release signing credentials" }
}

android {
    namespace = "com.xiyunmn.qdhelper"

    defaultConfig {
        applicationId = "io.github.xiyunmn.qdhelper"
        versionCode = versionCodeForBuildType("release")
        versionName = ciVersionName ?: baseVersionName
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseKeystoreFile))
                storePassword = requireNotNull(releaseKeystorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        // Every shipped channel must prune unused code (including the Lucide catalog).
        configureEach {
            // AGP disables R8 optimization/obfuscation for debuggable artifacts.
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }

        debug {
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
        }

        release {
            isDebuggable = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        create("beta") {
            initWith(getByName("release"))
            versionNameSuffix = "-beta"
            matchingFallbacks += "release"
            signingConfig = signingConfigs.getByName(if (hasReleaseSigning) "release" else "debug")
        }
    }
}

androidComponents {
    onVariants { variant ->
        val buildType = variant.buildType ?: "release"
        variant.outputs.forEach { output ->
            output.versionName.set(versionNameForBuildType(buildType))
            output.versionCode.set(versionCodeForBuildType(buildType))
        }
    }
}

listOf("debug", "beta", "release").forEach { buildType ->
    val taskSuffix = buildType.replaceFirstChar { it.uppercase() }
    val moduleVersionName = versionNameForBuildType(buildType)
    val moduleVersionCode = versionCodeForBuildType(buildType)
    tasks.withType<Sync>().matching { it.name == "process${taskSuffix}JavaRes" }.configureEach {
        inputs.property("moduleVersionName", moduleVersionName)
        inputs.property("moduleVersionCode", moduleVersionCode)
        filteringCharset = "UTF-8"
        filesMatching("META-INF/xposed/module.prop") {
            filter { line -> when {
                line.startsWith("version=") -> "version=$moduleVersionName"
                line.startsWith("versionCode=") -> "versionCode=$moduleVersionCode"
                else -> line
            } }
        }
    }
}

dependencies {
    implementation(project(":foundation"))
    implementation(project(":hook-runtime"))
    implementation(project(":config"))
    implementation(project(":settings-api"))
    implementation(project(":settings-ui"))
    implementation(project(":host-api"))
    implementation(project(":host-resolver"))
    implementation(project(":feature-api"))
    implementation(project(":shared-io"))
    implementation(project(":shared-ui"))
    implementation(project(":shared-compose"))
    implementation(project(":shared-media"))
    implementation(project(":capability:free-copy"))
    implementation(project(":capability:original-image"))
    implementation(project(":features:dialogs"))
    implementation(project(":features:account"))
    implementation(project(":features:startup"))
    implementation(project(":features:bookshelf"))
    implementation(project(":features:navigation"))
    implementation(project(":features:glass-bars"))
    implementation(project(":features:page-customization"))
    implementation(project(":features:reader"))
    implementation(project(":features:read-time"))
    implementation(project(":features:content-tools"))
    implementation(project(":features:book-shield"))
    implementation(project(":features:reader-font"))
    implementation(project(":features:network-identity"))
    implementation(project(":features:welfare"))
    implementation(project(":features:diagnostics"))
    implementation(project(":features:maintenance"))
    compileOnly("io.github.libxposed:api:102.0.0")
}

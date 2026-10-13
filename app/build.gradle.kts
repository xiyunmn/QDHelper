plugins {
    id("qdhelper.android.application")
}

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

android {
    namespace = "com.xiyunmn.qdhelper"

    defaultConfig {
        applicationId = "io.github.xiyunmn.qdhelper"
        versionCode = 10000
        versionName = "1.0.0"
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
    testImplementation("io.github.libxposed:api:102.0.0")
    testImplementation("junit:junit:4.13.2")
}

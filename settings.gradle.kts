pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "QDHelper"
include(
    ":app",
    ":foundation",
    ":hook-runtime",
    ":config",
    ":settings-api",
    ":settings-ui",
    ":host-api",
    ":host-resolver",
    ":feature-api",
    ":shared-io",
    ":shared-fonts",
    ":shared-ui",
    ":shared-compose",
    ":shared-media",
    ":capability:free-copy",
    ":capability:original-image",
    ":features:dialogs",
    ":features:account",
    ":features:startup",
    ":features:bookshelf",
    ":features:navigation",
    ":features:glass-bars",
    ":features:page-customization",
    ":features:reader",
    ":features:read-time",
    ":features:content-tools",
    ":features:book-shield",
    ":features:reader-font",
    ":features:network-identity",
    ":features:welfare",
    ":features:diagnostics",
    ":features:maintenance",
)

project(":foundation").projectDir = file("modules/foundation")
project(":hook-runtime").projectDir = file("modules/hook-runtime")
project(":config").projectDir = file("modules/config")
project(":settings-api").projectDir = file("modules/settings-api")
project(":settings-ui").projectDir = file("modules/settings-ui")
project(":host-api").projectDir = file("modules/host-api")
project(":host-resolver").projectDir = file("modules/host-resolver")
project(":feature-api").projectDir = file("modules/feature-api")
project(":shared-io").projectDir = file("modules/shared-io")
project(":shared-fonts").projectDir = file("modules/shared-fonts")
project(":shared-ui").projectDir = file("modules/shared-ui")
project(":shared-compose").projectDir = file("modules/shared-compose")
project(":shared-media").projectDir = file("modules/shared-media")
project(":capability").projectDir = file("modules/capability")
project(":capability:free-copy").projectDir = file("modules/capability/free-copy")
project(":capability:original-image").projectDir = file("modules/capability/original-image")
project(":features").projectDir = file("modules/features")
project(":features:dialogs").projectDir = file("modules/features/dialogs")
project(":features:account").projectDir = file("modules/features/account")
project(":features:startup").projectDir = file("modules/features/startup")
project(":features:bookshelf").projectDir = file("modules/features/bookshelf")
project(":features:navigation").projectDir = file("modules/features/navigation")
project(":features:glass-bars").projectDir = file("modules/features/glass-bars")
project(":features:page-customization").projectDir = file("modules/features/page-customization")
project(":features:reader").projectDir = file("modules/features/reader")
project(":features:read-time").projectDir = file("modules/features/read-time")
project(":features:content-tools").projectDir = file("modules/features/content-tools")
project(":features:book-shield").projectDir = file("modules/features/book-shield")
project(":features:reader-font").projectDir = file("modules/features/reader-font")
project(":features:network-identity").projectDir = file("modules/features/network-identity")
project(":features:welfare").projectDir = file("modules/features/welfare")
project(":features:diagnostics").projectDir = file("modules/features/diagnostics")
project(":features:maintenance").projectDir = file("modules/features/maintenance")

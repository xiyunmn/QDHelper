plugins {
    id("qdhelper.android.library")
}

val dexKitVersion = "2.3.0"

android {
    namespace = "com.xiyunmn.qdhelper.host.resolver"
    buildFeatures.buildConfig = true
    defaultConfig {
        buildConfigField("String", "DEXKIT_VERSION", "\"$dexKitVersion\"")
    }
}

dependencies {
    implementation(project(":foundation"))
    implementation(project(":hook-runtime"))
    implementation(project(":config"))
    implementation(project(":host-api"))
    implementation("org.luckypray:dexkit:$dexKitVersion")
}

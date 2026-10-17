plugins {
    id("qdhelper.android.resource-feature")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    buildFeatures { compose = true }
    namespace = "com.xiyunmn.qdhelper.feature.glassbars"
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation-layout")
    implementation(project(":shared-compose"))
    implementation(project(":feature-api"))
    implementation(project(":shared-ui"))
}

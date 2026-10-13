plugins {
    id("qdhelper.android.resource-library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android { namespace = "com.xiyunmn.qdhelper.shared.compose"; buildFeatures { compose = true } }

dependencies {
    implementation(project(":shared-ui"))
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    api("androidx.lifecycle:lifecycle-runtime:2.9.4")
    api("androidx.savedstate:savedstate:1.3.3")
}

plugins {
    id("qdhelper.android.resource-feature")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.xiyunmn.qdhelper.feature.bookshield"
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":feature-api"))
    implementation(project(":shared-io"))
    implementation(project(":shared-ui"))
    implementation(project(":shared-compose"))
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
}

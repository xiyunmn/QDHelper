plugins {
    id("qdhelper.android.resource-library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.xiyunmn.qdhelper.settings.ui"
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("com.composables:icons-lucide-android:2.2.1")
    // Material3's internal BackHandler uses activity-compose transitively. Keep that dependency;
    // this module itself hosts ComposeView in a framework Dialog and does not use an Activity entry.
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.runtime:runtime-saveable")
    implementation("androidx.lifecycle:lifecycle-runtime:2.9.4")
    implementation("androidx.savedstate:savedstate:1.3.3")
    implementation(project(":foundation"))
    implementation(project(":feature-api"))
    implementation(project(":settings-api"))
    implementation(project(":config"))
    implementation(project(":host-api"))
    implementation(project(":shared-ui"))
    implementation(project(":shared-compose"))
}

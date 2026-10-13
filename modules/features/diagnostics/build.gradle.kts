plugins {
    id("qdhelper.android.feature")
}

android {
    namespace = "com.xiyunmn.qdhelper.feature.diagnostics"
}

dependencies {
    implementation(project(":feature-api"))
    implementation(project(":shared-io"))
    implementation(project(":shared-ui"))
    // Real org.json implementation for JVM tests; the Android stub throws instead of parsing.
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}

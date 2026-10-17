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
}

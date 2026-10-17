plugins {
    id("qdhelper.android.feature")
}

android {
    namespace = "com.xiyunmn.qdhelper.feature.contenttools"
}

dependencies {
    implementation(project(":feature-api"))
    implementation(project(":shared-media"))
    implementation(project(":shared-io"))
    implementation(project(":capability:free-copy"))
    implementation(project(":capability:original-image"))
}

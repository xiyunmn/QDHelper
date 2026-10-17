plugins {
    id("qdhelper.android.feature")
}

android {
    namespace = "com.xiyunmn.qdhelper.feature.startup"
}

dependencies {
    implementation(project(":feature-api"))
    implementation(project(":shared-io"))
    implementation(project(":shared-ui"))
}

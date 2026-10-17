plugins {
    id("qdhelper.android.feature")
}

android {
    namespace = "com.xiyunmn.qdhelper.feature.maintenance"
}

dependencies {
    implementation(project(":feature-api"))
    implementation(project(":shared-io"))
}

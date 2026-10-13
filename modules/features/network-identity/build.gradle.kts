plugins {
    id("qdhelper.android.feature")
}

android {
    namespace = "com.xiyunmn.qdhelper.feature.networkidentity"
}

dependencies {
    implementation(project(":feature-api"))
}

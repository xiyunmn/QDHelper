plugins {
    id("qdhelper.android.feature")
}

android {
    namespace = "com.xiyunmn.qdhelper.feature.navigation"
}

dependencies {
    implementation(project(":feature-api"))
}

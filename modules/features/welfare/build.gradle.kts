plugins {
    id("qdhelper.android.feature")
}

android {
    namespace = "com.xiyunmn.qdhelper.feature.welfare"
}

dependencies {
    implementation(project(":feature-api"))
}

plugins {
    id("qdhelper.android.feature")
}

android {
    namespace = "com.xiyunmn.qdhelper.feature.readtime"
}

dependencies {
    implementation(project(":feature-api"))
}

plugins {
    id("qdhelper.android.library")
}

android {
    namespace = "com.xiyunmn.qdhelper.shared.fonts"
}

dependencies {
    implementation(project(":foundation"))
    implementation(project(":config"))
}

plugins {
    id("qdhelper.android.library")
}

android {
    namespace = "com.xiyunmn.qdhelper.settings.api"
}

dependencies {
    implementation(project(":foundation"))
    implementation(project(":config"))
}

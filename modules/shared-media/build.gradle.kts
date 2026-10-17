plugins {
    id("qdhelper.android.library")
}

android {
    namespace = "com.xiyunmn.qdhelper.shared.media"
}

dependencies {
    implementation(project(":shared-io"))
}

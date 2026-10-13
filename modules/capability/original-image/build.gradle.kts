plugins {
    id("qdhelper.android.library")
}

android {
    namespace = "com.xiyunmn.qdhelper.capability.originalimage"
}

dependencies {
    implementation(project(":foundation"))
    implementation(project(":hook-runtime"))
    implementation(project(":shared-ui"))
    implementation(project(":shared-media"))
}

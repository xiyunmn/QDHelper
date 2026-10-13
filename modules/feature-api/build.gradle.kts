plugins {
    id("qdhelper.android.library")
}

android {
    namespace = "com.xiyunmn.qdhelper.feature.api"
}

dependencies {
    api(project(":foundation"))
    api(project(":hook-runtime"))
    api(project(":config"))
    api(project(":host-api"))
    api(project(":settings-api"))
    testImplementation("junit:junit:4.13.2")
}

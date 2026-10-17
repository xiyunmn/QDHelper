plugins {
    id("qdhelper.android.feature")
}

android {
    namespace = "com.xiyunmn.qdhelper.feature.dialogs"
}

dependencies {
    implementation(project(":feature-api"))
}

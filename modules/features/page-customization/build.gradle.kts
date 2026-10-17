plugins {
    id("qdhelper.android.feature")
}

android {
    namespace = "com.xiyunmn.qdhelper.feature.pagecustomization"
}

dependencies {
    implementation(project(":feature-api"))
}

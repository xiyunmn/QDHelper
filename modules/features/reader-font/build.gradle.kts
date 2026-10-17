plugins {
    id("qdhelper.android.feature")
}

android {
    namespace = "com.xiyunmn.qdhelper.feature.readerfont"
}

dependencies {
    implementation(project(":feature-api"))
    implementation(project(":shared-io"))
    implementation(project(":shared-fonts"))
    implementation(project(":shared-ui"))
}

plugins {
    id("qdhelper.android.feature")
}

android {
    namespace = "com.xiyunmn.qdhelper.feature.reader"
}

dependencies {
    implementation(project(":feature-api"))
    implementation(project(":shared-ui"))
    implementation(project(":shared-media"))
    implementation(project(":capability:free-copy"))
    testImplementation("junit:junit:4.13.2")
}

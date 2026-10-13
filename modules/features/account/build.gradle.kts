plugins {
    id("qdhelper.android.feature")
}

android {
    namespace = "com.xiyunmn.qdhelper.feature.account"
}

dependencies {
    implementation(project(":feature-api"))
    testImplementation("junit:junit:4.13.2")
}

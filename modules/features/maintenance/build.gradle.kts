plugins {
    id("qdhelper.android.feature")
}

android {
    namespace = "com.xiyunmn.qdhelper.feature.maintenance"
}

dependencies {
    implementation(project(":feature-api"))
    implementation(project(":shared-io"))
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}

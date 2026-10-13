plugins {
    id("qdhelper.android.feature")
}

android {
    namespace = "com.xiyunmn.qdhelper.feature.pagecustomization"
}

dependencies {
    implementation(project(":feature-api"))
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}

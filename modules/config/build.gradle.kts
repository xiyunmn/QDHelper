plugins {
    id("qdhelper.android.library")
}

android {
    namespace = "com.xiyunmn.qdhelper.config"
}

dependencies {
    implementation("org.snakeyaml:snakeyaml-engine:3.1.1")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}

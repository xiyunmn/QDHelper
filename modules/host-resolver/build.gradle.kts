plugins {
    id("qdhelper.android.library")
}

val dexKitVersion = "2.3.0"

android {
    namespace = "com.xiyunmn.qdhelper.host.resolver"
    buildFeatures.buildConfig = true
    defaultConfig {
        buildConfigField("String", "DEXKIT_VERSION", "\"$dexKitVersion\"")
        testInstrumentationRunner = "com.xiyunmn.qdhelper.host.resolver.audit.HostContractAuditInstrumentation"
    }
}

dependencies {
    implementation(project(":foundation"))
    implementation(project(":hook-runtime"))
    implementation(project(":config"))
    implementation(project(":host-api"))
    implementation("org.luckypray:dexkit:$dexKitVersion")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}

tasks.withType<Test>().configureEach {
    val manifest = layout.buildDirectory.file("reports/host-contract/$name-catalog.properties")
    outputs.file(manifest)
    systemProperty("qdhelper.hostContractManifest", manifest.get().asFile.absolutePath)
}

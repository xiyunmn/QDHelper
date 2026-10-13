plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        create("androidApplication") {
            id = "qdhelper.android.application"
            implementationClass = "com.xiyunmn.qdhelper.buildlogic.AndroidApplicationConventionPlugin"
        }
        create("androidLibrary") {
            id = "qdhelper.android.library"
            implementationClass = "com.xiyunmn.qdhelper.buildlogic.AndroidLibraryConventionPlugin"
        }
        create("androidResourceLibrary") {
            id = "qdhelper.android.resource-library"
            implementationClass = "com.xiyunmn.qdhelper.buildlogic.AndroidResourceLibraryConventionPlugin"
        }
        create("androidFeature") {
            id = "qdhelper.android.feature"
            implementationClass = "com.xiyunmn.qdhelper.buildlogic.AndroidFeatureConventionPlugin"
        }
        create("androidResourceFeature") {
            id = "qdhelper.android.resource-feature"
            implementationClass = "com.xiyunmn.qdhelper.buildlogic.AndroidResourceFeatureConventionPlugin"
        }
        create("architectureGuard") {
            id = "qdhelper.architecture-guard"
            implementationClass = "com.xiyunmn.qdhelper.buildlogic.ArchitectureGuardPlugin"
        }
    }
}

dependencies {
    implementation("com.android.tools.build:gradle:9.1.1")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    useJUnit()
}

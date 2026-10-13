package com.xiyunmn.qdhelper.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

class ArchitectureGuardPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        require(project == project.rootProject) { "qdhelper.architecture-guard must be applied to the root project" }

        project.tasks.register("verifyRepositoryHygiene", VerifyRepositoryHygieneTask::class.java).configure {
            group = "verification"
            description = "Rejects tracked local files and nested build/release artifacts."
            repositoryDirectory.set(project.layout.projectDirectory)
        }

        val verifyArchitectureFast = project.tasks.register(
            "verifyArchitectureFast",
            VerifyArchitectureFastTask::class.java,
        )
        verifyArchitectureFast.configure {
            group = "verification"
            description = "Checks module graph, packages and controlled APIs, and reports source-size warnings."
            repositoryDirectory.set(project.layout.projectDirectory)
            moduleGraphFile.set(project.layout.projectDirectory.file("architecture/module-graph.toml"))
            reportFile.set(project.layout.buildDirectory.file("reports/architecture/fast.txt"))
            repositoryInputs.from(project.fileTree(project.rootDir) {
                include("settings.gradle.kts", "build.gradle.kts")
                include("app/build.gradle.kts", "modules/**/build.gradle.kts")
                include("app/src/**", "modules/**/src/main/**")
                include("modules/**/src/*/res/**")
                exclude("**/build/**")
            })
        }

        val allDebugUnitTests = project.tasks.register("allDebugUnitTests") {
            group = "verification"
            description = "Runs every included Android module's Debug unit tests."
        }
        project.tasks.register("verifyFast") {
            group = "verification"
            description = "Runs repository hygiene and current source guards without tests, lint or APK assembly."
            dependsOn(
                "verifyRepositoryHygiene",
                "verifyArchitectureFast",
            )
        }
        project.tasks.register("verifyFull") {
            group = "verification"
            description = "Explicitly runs all local tests, lint, source guards, packaging and ABI checks."
            dependsOn(
                "verifyRepositoryHygiene",
                "verifyArchitecture",
                "verifyBuildLogic",
                "allDebugUnitTests",
                "allDebugLint",
                "verifyPackagedDebugModule",
                "verifyArm64Artifacts",
            )
        }
        project.gradle.projectsEvaluated {
            val runtimeModules = project.rootProject.allprojects
                .filter { module -> module == project.rootProject || module.buildFile.isFile }
            verifyArchitectureFast.configure {
                moduleDescriptors.set(runtimeModules.map { module ->
                    val plugin = when {
                        module.plugins.hasPlugin("com.android.application") -> "application"
                        module.plugins.hasPlugin("com.android.library") -> "library"
                        module == project.rootProject -> "root"
                        else -> "unknown"
                    }
                    "${module.path}\t$plugin"
                }.sorted())
                dependencyDescriptors.set(runtimeModules.flatMap { module ->
                    module.configurations.flatMap { configuration ->
                        configuration.dependencies.withType(ProjectDependency::class.java)
                            .filter { dependency -> dependency.path != module.path }
                            .map { dependency -> "${module.path}\t${configuration.name}\t${dependency.path}" }
                    }
                }.distinct().sorted())
            }
            allDebugUnitTests.configure {
                dependsOn(project.rootProject.allprojects.filter { module ->
                    module.fileTree(module.file("src/test")) {
                        include("**/*.kt", "**/*.java")
                    }.files.isNotEmpty()
                }.mapNotNull { module ->
                    module.tasks.findByName("testDebugUnitTest")
                })
            }
        }

        project.tasks.register("verifyPackagedDebugModule", VerifyPackagedModuleTask::class.java).configure {
            group = "verification"
            description = "Assembles and verifies the packaged Debug Xposed module contract."
            dependsOn(":app:assembleDebug")
            apkFile.set(project.layout.projectDirectory.file("app/build/outputs/apk/debug/app-debug.apk"))
            reportFile.set(project.layout.buildDirectory.file("reports/architecture/packaged-debug.txt"))
        }

        project.tasks.register("verifyArm64Artifacts", VerifyArm64ArtifactsTask::class.java).configure {
            group = "verification"
            description = "Verifies QDHelper Debug/Release APKs and resolver instrumentation are arm64-v8a only."
            dependsOn(":app:assembleDebug", ":host-resolver:assembleDebugAndroidTest")
            repositoryDirectory.set(project.layout.projectDirectory)
            reportFile.set(project.layout.buildDirectory.file("reports/architecture/arm64-artifacts.txt"))
        }

        project.tasks.register("verifyHostContracts", VerifyHostContractsTask::class.java).configure {
            group = "verification"
            description = "Runs the active HostTarget archive audit on one explicit Android device."
            dependsOn(":host-resolver:assembleDebugAndroidTest", ":host-resolver:testDebugUnitTest")
            repositoryDirectory.set(project.layout.projectDirectory)
            deviceSerial.set(project.providers.gradleProperty("qdhelper.audit.deviceSerial"))
            hostApksPath.set(project.providers.gradleProperty("qdhelper.audit.apksDir"))
            auditOutputPath.set(project.providers.gradleProperty("qdhelper.audit.outputDir"))
            catalogManifest.set(project.layout.projectDirectory.file("modules/host-resolver/build/reports/host-contract/testDebugUnitTest-catalog.properties"))
            testApk.set(
                project.layout.projectDirectory.file(
                    "modules/host-resolver/build/outputs/apk/androidTest/debug/host-resolver-debug-androidTest.apk",
                ),
            )
            project.file("local.properties").takeIf { file -> file.isFile }?.let { file ->
                localPropertiesFiles.from(file)
            }
            androidHome.set(project.providers.environmentVariable("ANDROID_HOME"))
        }
    }
}

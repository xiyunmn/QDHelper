package com.xiyunmn.qdhelper.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

private const val COMPILE_SDK = 37
private const val MIN_SDK = 28
private const val TARGET_SDK = 37
private val MODULE_ABIS = setOf("arm64-v8a")

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        pluginManager.apply("com.android.application")
        extensions.configure(ApplicationExtension::class.java) {
            compileSdk = COMPILE_SDK
            defaultConfig {
                minSdk = MIN_SDK
                targetSdk = TARGET_SDK
                ndk {
                    abiFilters.clear()
                    abiFilters.addAll(MODULE_ABIS)
                }
            }
            splits.abi {
                // Keep both supported native architectures in the same module APK.
                isEnable = false
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            lint {
                // Dependency/test-source lint is duplicated by the module that owns the code and
                // can trigger expensive transitive scans. Keep source lint and hard failures.
                checkDependencies = false
                checkTestSources = false
                disable.add("NewerVersionAvailable")
            }
        }
        tasks.withType(KotlinJvmCompile::class.java).configureEach {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) = project.configureAndroidLibrary(resourcesEnabled = false)
}

class AndroidResourceLibraryConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) = project.configureAndroidLibrary(resourcesEnabled = true)
}

class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("qdhelper.android.library")
    }
}

class AndroidResourceFeatureConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("qdhelper.android.resource-library")
    }
}

private fun Project.configureAndroidLibrary(resourcesEnabled: Boolean) {
    pluginManager.apply("com.android.library")
    extensions.configure(LibraryExtension::class.java) {
        compileSdk = COMPILE_SDK
        defaultConfig {
            minSdk = MIN_SDK
            ndk {
                abiFilters.clear()
                abiFilters.addAll(MODULE_ABIS)
            }
        }
        splits.abi {
            isEnable = false
        }
        androidResources.enable = resourcesEnabled
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        lint {
            // Keep lint local to the module and avoid the network-backed dependency version check.
            checkDependencies = false
            checkTestSources = false
            disable.add("NewerVersionAvailable")
        }
    }
    tasks.withType(KotlinJvmCompile::class.java).configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
}

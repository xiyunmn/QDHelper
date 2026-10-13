plugins {
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("qdhelper.architecture-guard")
}

val verifyBuildLogic by tasks.registering {
    group = "verification"
    description = "Runs the included build's unit tests explicitly."
    dependsOn(gradle.includedBuild("build-logic").task(":test"))
}

val verifyArchitecture by tasks.registering {
    group = "verification"
    description = "Runs current source architecture guards without unit tests."
    dependsOn("verifyArchitectureFast")
}

val allDebugLint by tasks.registering {
    group = "verification"
    description = "Runs lintDebug for every Android application and library module."
}

gradle.projectsEvaluated {
    allDebugLint.configure {
        dependsOn(subprojects.mapNotNull { project -> project.tasks.findByName("lintDebug") })
    }
    tasks.findByPath(":app:preBuild")?.dependsOn(
        tasks.named("verifyRepositoryHygiene"),
        tasks.named("verifyArchitectureFast"),
    )
}

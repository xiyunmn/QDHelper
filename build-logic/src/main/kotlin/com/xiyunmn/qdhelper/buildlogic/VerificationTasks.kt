package com.xiyunmn.qdhelper.buildlogic

import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

private fun DefaultTask.writeReportAndEnforce(reportFile: File, result: CheckResult) {
    reportFile.parentFile.mkdirs()
    reportFile.writeText(
        buildList {
            add(if (result.errors.isEmpty()) "OK" else "FAILED")
            addAll(result.errors.map { "ERROR: $it" })
            addAll(result.warnings.map { "WARNING: $it" })
        }.joinToString(separator = "\n", postfix = "\n"),
        Charsets.UTF_8,
    )
    result.warnings.forEach { warning -> logger.warn(warning) }
    if (result.errors.isNotEmpty()) {
        throw GradleException(result.errors.joinToString("\n"))
    }
}

private fun DefaultTask.enforce(result: CheckResult) {
    result.warnings.forEach { warning -> logger.warn(warning) }
    if (result.errors.isNotEmpty()) {
        throw GradleException(result.errors.joinToString("\n"))
    }
}

@UntrackedTask(because = "The Git index is external repository state and cannot be declared as a regular file input.")
abstract class VerifyRepositoryHygieneTask : DefaultTask() {
    @get:Internal
    abstract val repositoryDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val repository = repositoryDirectory.get().asFile
        if (!repository.resolve(".git").exists()) return

        val process = ProcessBuilder("git", "ls-files", "-z")
            .directory(repository)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("git ls-files failed:\n$output")
        }
        enforce(RepositoryHygieneChecker.check(output.split('\u0000').filter(String::isNotBlank)))
    }
}

@CacheableTask
abstract class VerifyArchitectureFastTask : DefaultTask() {
    @get:Internal
    abstract val repositoryDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val moduleGraphFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val repositoryInputs: ConfigurableFileCollection

    @get:Input
    abstract val moduleDescriptors: ListProperty<String>

    @get:Input
    abstract val dependencyDescriptors: ListProperty<String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val root = repositoryDirectory.get().asFile
        val graph = ModuleGraphConfig.parse(moduleGraphFile.get().asFile)
        val dependenciesByModule = dependencyDescriptors.get().map { descriptor ->
            descriptor.split('\t').also { fields ->
                require(fields.size == 3) { "Invalid architecture dependency descriptor: $descriptor" }
            }
        }.groupBy(
            keySelector = { fields -> fields[0] },
            valueTransform = { fields -> ProjectEdge(target = fields[2], configuration = fields[1]) },
        )
        val actualModules = moduleDescriptors.get().map { descriptor ->
            val fields = descriptor.split('\t')
            require(fields.size == 2) { "Invalid architecture module descriptor: $descriptor" }
            ActualModule(
                path = fields[0],
                plugin = fields[1],
                dependencies = dependenciesByModule[fields[0]].orEmpty(),
            )
        }

        val result = ModuleGraphChecker.check(graph, actualModules) +
            ProductContractChecker.check(root) +
            PackagePathChecker.check(root, graph) +
            SourceBudgetChecker.check(root, graph) +
            AndroidResourceConventionChecker.check(root, graph) +
            SourcePolicyChecker.check(root, graph)
        writeReportAndEnforce(reportFile.get().asFile, result)
    }
}

@CacheableTask
abstract class VerifyPackagedModuleTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkFile: RegularFileProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        writeReportAndEnforce(reportFile.get().asFile, PackagedModuleChecker.check(apkFile.get().asFile))
    }
}

/** Checks that every produced QDHelper APK contains only the supported native ABI. */
@UntrackedTask(because = "The task inspects APKs produced by several Android projects.")
abstract class VerifyArm64ArtifactsTask : DefaultTask() {
    @get:Internal
    abstract val repositoryDirectory: DirectoryProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val root = repositoryDirectory.get().asFile
        val required = listOf(
            root.resolve("app/build/outputs/apk/debug/app-debug.apk"),
            root.resolve("modules/host-resolver/build/outputs/apk/androidTest/debug/host-resolver-debug-androidTest.apk"),
        )
        val releaseDirectory = root.resolve("app/build/outputs/apk/release")
        val release = releaseDirectory.takeIf(File::isDirectory)
            ?.listFiles { file -> file.isFile && file.extension.equals("apk", ignoreCase = true) }
            .orEmpty()
            .sortedBy(File::getName)
        val artifacts = required + release
        val missing = required.filterNot(File::isFile).map { file ->
            "ARCH-ABI: missing ${file.invariantSeparatorsPath}; assemble the required Debug artifact first"
        }
        val result = artifacts
            .filter(File::isFile)
            .fold(CheckResult(errors = missing)) { current, apk ->
                current + NativeAbiChecker.check(apk)
            }
        writeReportAndEnforce(reportFile.get().asFile, result)
    }
}

@UntrackedTask(because = "The audit reads a connected Android device and writes evidence outside the build directory.")
abstract class VerifyHostContractsTask : DefaultTask() {
    @get:Internal
    abstract val repositoryDirectory: DirectoryProperty

    @get:Optional
    @get:Input
    abstract val deviceSerial: Property<String>

    @get:Optional
    @get:Input
    abstract val hostApksPath: Property<String>

    @get:Optional
    @get:Input
    abstract val auditOutputPath: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val testApk: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val catalogManifest: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val localPropertiesFiles: ConfigurableFileCollection

    @get:Optional
    @get:Input
    abstract val androidHome: Property<String>

    @TaskAction
    fun verify() {
        val root = repositoryDirectory.get().asFile
        val serial = deviceSerial.orNull?.takeIf(String::isNotBlank)
            ?: throw GradleException("Missing -Pqdhelper.audit.deviceSerial=<adb-serial>")
        val apksDirectory = hostApksPath.orNull?.takeIf(String::isNotBlank)?.let { root.resolveUserPath(it) }
            ?: throw GradleException("Missing -Pqdhelper.audit.apksDir=<directory>")
        val outputDirectory = auditOutputPath.orNull?.takeIf(String::isNotBlank)?.let { root.resolveUserPath(it) }
            ?: throw GradleException("Missing -Pqdhelper.audit.outputDir=<directory>")
        require(apksDirectory.isDirectory) { "Host APK directory is missing: $apksDirectory" }
        val expectedCatalog = ExpectedHostCatalog.from(Properties().apply {
            catalogManifest.get().asFile.inputStream().use(::load)
        })
        val failedVersions = mutableListOf<String>()

        val localProperties = Properties().apply {
            localPropertiesFiles.files.firstOrNull(File::isFile)?.inputStream()?.use(::load)
        }
        val sdkDirectory = localProperties.getProperty("sdk.dir")
            ?.replace("\\:", ":")
            ?.let(::File)
            ?: androidHome.orNull?.takeIf(String::isNotBlank)?.let(::File)
            ?: throw GradleException("Android SDK is not configured")
        val adb = sdkDirectory.resolve("platform-tools/adb.exe").takeIf(File::isFile)
            ?: sdkDirectory.resolve("platform-tools/adb").takeIf(File::isFile)
            ?: throw GradleException("adb is missing under $sdkDirectory")
        val apkAnalyzer = sdkDirectory.resolve("cmdline-tools").walkTopDown()
            .firstOrNull { file -> file.isFile && file.nameWithoutExtension == "apkanalyzer" }
            ?: throw GradleException("apkanalyzer is missing under $sdkDirectory/cmdline-tools")
        val instrumentationApk = testApk.get().asFile
        require(instrumentationApk.isFile) { "Host resolver androidTest APK is missing: $instrumentationApk" }

        val devices = runCommand(adb.absolutePath, "devices").lineSequence()
            .drop(1)
            .map { line -> line.trim().split(Regex("\\s+")) }
            .filter { fields -> fields.size >= 2 && fields[1] == "device" }
            .map { fields -> fields[0] }
            .toList()
        require(devices == listOf(serial)) { "Expected exactly device $serial, found ${devices.joinToString()}" }
        runCommand(adb.absolutePath, "-s", serial, "install", "-r", instrumentationApk.absolutePath)

        try {
            listOf("7.9.420", "7.9.464", "7.9.466").forEach { version ->
                writeNoScanReport(outputDirectory, version)
            }
            listOf("7.9.469", "7.9.470", "7.9.472", "7.9.474", "7.9.476", "7.9.480").forEach { version ->
                val apk = apksDirectory.resolve("起点读书_$version.apk")
                require(apk.isFile) { "Missing host APK: $apk" }
                val packageName = runCommand(apkAnalyzer.absolutePath, "manifest", "application-id", apk.absolutePath)
                val versionName = runCommand(apkAnalyzer.absolutePath, "manifest", "version-name", apk.absolutePath)
                val versionCode = runCommand(apkAnalyzer.absolutePath, "manifest", "version-code", apk.absolutePath)
                require(packageName == "com.qidian.QDReader") { "Unexpected package in $apk: $packageName" }
                require(versionName == version) { "Unexpected version in $apk: $versionName" }
                val apkHash = apk.contentSha256()
                val scanId = UUID.randomUUID().toString()

                val remoteApk = "/data/local/tmp/qdhelper-host-contract-$version.apk"
                runCommand(adb.absolutePath, "-s", serial, "push", apk.absolutePath, remoteApk)
                try {
                    // DexClassLoader rejects writable APKs on recent ART releases.  adb push
                    // creates a world-writable file on this device, so make the audit input
                    // immutable before handing it to the instrumentation process.
                    runCommand(adb.absolutePath, "-s", serial, "shell", "chmod", "444", remoteApk)
                    val instrumentationOutput = runCommand(
                        adb.absolutePath,
                        "-s",
                        serial,
                        "shell",
                        "am",
                        "instrument",
                        "-w",
                        "-e",
                        "targetApk",
                        remoteApk,
                        "-e",
                        "targetVersion",
                        version,
                        "-e",
                        "targetVersionCode",
                        versionCode,
                        "-e",
                        "scanId",
                        scanId,
                        "com.xiyunmn.qdhelper.host.resolver.test/com.xiyunmn.qdhelper.host.resolver.audit.HostContractAuditInstrumentation",
                    )
                    check("qdhelperHostContractError=" !in instrumentationOutput) {
                        "Host contract instrumentation failed for $version:\n$instrumentationOutput"
                    }
                    val report = runCommand(
                        adb.absolutePath,
                        "-s",
                        serial,
                        "exec-out",
                        "run-as",
                        "com.xiyunmn.qdhelper.host.resolver.test",
                        "cat",
                        "cache/host-contract-$version.json",
                    )
                    val validation = HostContractReportValidator.validate(
                        report,
                        expectedVersion = version,
                        expectedVersionCode = versionCode,
                        expectedApkSha256 = apkHash,
                        expectedScanId = scanId,
                        expectedCatalog = expectedCatalog,
                    )
                    val target = outputDirectory.resolve(version).resolve(validation.catalogHash).resolve(scanId).resolve("audit.json")
                    target.parentFile.mkdirs()
                    target.writeText(report + "\n", Charsets.UTF_8)
                    if (validation.verdict != "PASS") failedVersions += "$version:${validation.verdict}"
                    if (validation.verifierErrors > 0) {
                        logger.warn("Host audit {} has {} verifier/materialization errors; verdict={}", version, validation.verifierErrors, validation.verdict)
                    }
                } finally {
                    runCommand(adb.absolutePath, "-s", serial, "shell", "rm", "-f", remoteApk)
                }
            }
        } finally {
            runCommand(adb.absolutePath, "-s", serial, "uninstall", "com.xiyunmn.qdhelper.host.resolver.test")
        }
        check(failedVersions.isEmpty()) { "Host contracts did not pass: ${failedVersions.joinToString()}" }
    }

    private fun File.contentSha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun writeNoScanReport(outputDirectory: File, version: String) {
        val report = """
            {
              "schemaVersion": 3,
              "toolVersion": 3,
              "hostPackage": "com.qidian.QDReader",
              "hostVersion": "$version",
              "scanPerformed": false,
              "verdict": "NO_SCAN",
              "overallVerdict": "NO_SCAN",
              "reason": "historical_static_reference"
            }
        """.trimIndent() + "\n"
        val target = outputDirectory.resolve(version).resolve("no-scan").resolve("audit.json")
        if (target.exists()) return
        target.parentFile.mkdirs()
        target.writeText(report, Charsets.UTF_8)
    }

    private fun runCommand(vararg command: String): String {
        val actualCommand = if (command.first().endsWith(".bat", ignoreCase = true)) {
            listOf("cmd", "/d", "/q", "/c") + command
        } else {
            command.toList()
        }
        val process = ProcessBuilder(actualCommand).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }.trim()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("Command failed ($exitCode): ${actualCommand.joinToString(" ")}\n$output")
        }
        return output
    }

    private fun File.resolveUserPath(path: String): File = File(path).let { file ->
        if (file.isAbsolute) file else resolve(file)
    }
}

package com.xiyunmn.qdhelper.buildlogic

import java.io.File
import java.util.zip.ZipFile

internal data class CheckResult(
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    operator fun plus(other: CheckResult): CheckResult =
        CheckResult(errors + other.errors, warnings + other.warnings)
}

internal data class ModuleSpec(
    val path: String,
    val packagePrefix: String,
    val plugin: String,
    val allowedDependencies: Set<String>,
    val allowApiProjectDependencies: Boolean,
)

internal data class ModuleGraphConfig(val modules: Map<String, ModuleSpec>) {
    companion object {
        fun parse(file: File): ModuleGraphConfig {
            require(file.isFile) { "ARCH-MODULE-GRAPH: missing ${file.invariantSeparatorsPath}" }
            val records = mutableListOf<MutableMap<String, String>>()
            var current: MutableMap<String, String>? = null
            file.readLines(Charsets.UTF_8).forEachIndexed { index, rawLine ->
                val line = rawLine.trim()
                when {
                    line.isEmpty() || line.startsWith('#') || line.startsWith("schema_version") -> Unit
                    line == "[[modules]]" -> {
                        current = linkedMapOf()
                        records += current
                    }
                    '=' in line -> {
                        requireNotNull(current) { "ARCH-MODULE-GRAPH: ${file.name}:${index + 1}: property outside [[modules]]" }
                        val key = line.substringBefore('=').trim()
                        current[key] = line.substringAfter('=').trim()
                    }
                    else -> error("ARCH-MODULE-GRAPH: ${file.name}:${index + 1}: invalid TOML line")
                }
            }
            fun scalar(record: Map<String, String>, key: String): String =
                record[key]?.removeSurrounding("\"")
                    ?: error("ARCH-MODULE-GRAPH: module is missing $key")
            fun array(record: Map<String, String>, key: String): Set<String> {
                val value = record[key] ?: error("ARCH-MODULE-GRAPH: module is missing $key")
                require(value.startsWith('[') && value.endsWith(']')) {
                    "ARCH-MODULE-GRAPH: $key must be an array"
                }
                return Regex("\"([^\"]+)\"").findAll(value).map { it.groupValues[1] }.toSet()
            }
            val specs = records.map { record ->
                ModuleSpec(
                    path = scalar(record, "path"),
                    packagePrefix = scalar(record, "package_prefix"),
                    plugin = scalar(record, "plugin"),
                    allowedDependencies = array(record, "allowed_dependencies"),
                    allowApiProjectDependencies = record["allow_api_project_dependencies"] == "true",
                )
            }
            require(specs.isNotEmpty()) { "ARCH-MODULE-GRAPH: at least one runtime module is required" }
            require(specs.map { it.path }.toSet().size == specs.size) { "ARCH-MODULE-GRAPH: duplicate module path" }
            require(specs.count { it.plugin == "application" } == 1 && specs.single { it.plugin == "application" }.path == ":app") {
                "ARCH-MODULE-GRAPH: :app must be the only application module"
            }
            val known = specs.map { it.path }.toSet()
            specs.forEach { spec ->
                require(spec.allowedDependencies.all { it in known }) {
                    "ARCH-MODULE-GRAPH: ${spec.path} references an unknown dependency"
                }
                require(spec.path !in spec.allowedDependencies) {
                    "ARCH-MODULE-GRAPH: ${spec.path} depends on itself"
                }
            }
            return ModuleGraphConfig(specs.associateBy { it.path })
        }
    }
}

internal data class ProjectEdge(val target: String, val configuration: String)
internal data class ActualModule(val path: String, val plugin: String, val dependencies: List<ProjectEdge>)

internal object ModuleGraphChecker {
    fun check(graph: ModuleGraphConfig, actual: Collection<ActualModule>): CheckResult {
        val errors = mutableListOf<String>()
        val runtime = actual.filter { it.path != ":" }
        val actualRuntimePaths = runtime.mapTo(linkedSetOf()) { it.path }
        runtime.forEach { module ->
            val spec = graph.modules[module.path]
            if (spec == null) {
                errors += "ARCH-MODULE-GRAPH: ${module.path}: module is absent from architecture/module-graph.toml; declare its target boundary first"
                return@forEach
            }
            if (module.plugin != spec.plugin) {
                errors += "ARCH-MODULE-GRAPH: ${module.path}: actual plugin ${module.plugin}, expected ${spec.plugin}; use the matching qdhelper convention plugin"
            }
            module.dependencies.forEach { edge ->
                if (edge.target !in spec.allowedDependencies) {
                    val rule = if (module.path.startsWith(":features:") && edge.target.startsWith(":features:")) {
                        "ARCH-FEATURE-CROSS"
                    } else {
                        "ARCH-MODULE-GRAPH"
                    }
                    errors += "$rule: ${module.path} -> ${edge.target} via ${edge.configuration} is forbidden; move shared mechanics to shared/capability or expose a host gateway"
                }
                if (edge.configuration == "api" && !spec.allowApiProjectDependencies) {
                    errors += "ARCH-MODULE-GRAPH: ${module.path} exposes ${edge.target} through api; use implementation or document an API-module exception"
                }
            }
        }
        (graph.modules.keys - actualRuntimePaths).sorted().forEach { path ->
            errors += "ARCH-MODULE-GRAPH: $path: graph entry has no matching Gradle runtime module; include it in settings.gradle.kts or remove the stale declaration"
        }
        errors += cycleErrors(runtime.associate { module ->
            module.path to module.dependencies.map { it.target }.filter { target -> runtime.any { it.path == target } }.toSet()
        })
        errors += cycleErrors(graph.modules.mapValues { it.value.allowedDependencies })
        return CheckResult(errors.distinct())
    }

    private fun cycleErrors(edges: Map<String, Set<String>>): List<String> {
        val visiting = linkedSetOf<String>()
        val visited = mutableSetOf<String>()
        val errors = mutableListOf<String>()
        fun visit(node: String) {
            if (node in visited) return
            if (!visiting.add(node)) {
                val cycle = visiting.dropWhile { it != node } + node
                errors += "ARCH-MODULE-CYCLE: ${cycle.joinToString(" -> ")}; remove the reverse dependency"
                return
            }
            edges[node].orEmpty().forEach(::visit)
            visiting.remove(node)
            visited += node
        }
        edges.keys.forEach(::visit)
        return errors.distinct()
    }
}

internal object RepositoryHygieneChecker {
    private val forbiddenSegments = setOf(".gradle", ".kotlin", "build", "release")
    private val forbiddenRoots = setOf("docs", ".local", "target_app", "tmp", "org", ".claude", ".idea")
    private val forbiddenRootArtifact = Regex(
        pattern = """^[^/]+\.(png|jpe?g|webp|xml|log|apk|ap_|jks|keystore)$""",
        option = RegexOption.IGNORE_CASE,
    )

    fun check(trackedPaths: Collection<String>): CheckResult {
        val errors = trackedPaths.map { it.replace('\\', '/') }.filter { path ->
            val segments = path.split('/')
            path == "local.properties" ||
                path.endsWith("/output-metadata.json") ||
                forbiddenRootArtifact.matches(path) ||
                segments.firstOrNull() in forbiddenRoots ||
                segments.any { it in forbiddenSegments }
        }.sorted()
        return CheckResult(errors = errors.map { path -> "ARCH-REPO-OUTPUT: tracked local/build artifact: $path" })
    }
}

internal object ProductContractChecker {
    fun check(root: File): CheckResult {
        val errors = mutableListOf<String>()
        fun entries(path: String): List<String> {
            val file = root.resolve(path)
            if (!file.isFile) {
                errors += "ARCH-XPOSED-METADATA: missing $path; restore the API 102 product metadata"
                return emptyList()
            }
            return file.readLines(Charsets.UTF_8).map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith('#') }
        }
        val expectedEntry = "com.xiyunmn.qdhelper.entry.QDHelperModule"
        if (entries("app/src/main/resources/META-INF/xposed/java_init.list") != listOf(expectedEntry)) {
            errors += "ARCH-XPOSED-METADATA: java_init.list must contain only $expectedEntry"
        }
        if (entries("app/src/main/resources/META-INF/xposed/scope.list") != listOf("com.qidian.QDReader")) {
            errors += "ARCH-XPOSED-METADATA: scope.list must contain only com.qidian.QDReader"
        }
        val moduleProp = root.resolve("app/src/main/resources/META-INF/xposed/module.prop")
            .takeIf(File::isFile)?.readText(Charsets.UTF_8).orEmpty()
        if ("minApiVersion=102" !in moduleProp || "targetApiVersion=102" !in moduleProp || "autoHotReload=true" !in moduleProp) {
            errors += "ARCH-XPOSED-METADATA: module.prop must keep min/target API 102 and autoHotReload=true"
        }
        listOf(
            "app/src/main/assets/xposed_init",
            "app/src/main/resources/META-INF/xposed/native_init.list",
        ).filter { root.resolve(it).exists() }.forEach { path ->
            errors += "ARCH-XPOSED-METADATA: $path is forbidden; the module has no legacy/native Xposed entry"
        }

        val banned = Regex(
            listOf(
                "de\\.robv\\.android\\.xposed",
                "IXposedHookLoadPackage",
                "XposedBridge",
                "XposedHelpers",
                "XSharedPreferences",
                "assets/xposed_init",
                "\\bxposed_init\\b",
                "io\\.github\\.libxposed:service",
                "getRemotePreferences",
            ).joinToString("|"),
        )
        val mojibake = Regex("\uFFFD|\u00C3.|\u00C2.|\u951B|\u9286|\u9225|\u6D93|\u7ED7|\u9359|\u9428|\u74A7|\u6434|\u6D63|\u95C8|\u59AF|\u93B5|\u6748|\u6FC2|\u93C2|\u608A|\u9354|\u7ECB|\u7F03|\u93C3|\u93C4|\u741B|\u9422|\u701B|\u947E|\u95AB")
        val inspectable = root.resolve("app/src").walkTopDown().filter { file ->
            file.isFile && file.extension in setOf("kt", "kts", "java", "xml", "list", "prop", "md") &&
                !file.invariantSeparatorsPath.contains("/build/")
        }
        inspectable.forEach { file ->
            file.readLines(Charsets.UTF_8).forEachIndexed { index, line ->
                val path = file.relativeTo(root).invariantSeparatorsPath
                if (banned.containsMatchIn(line)) {
                    errors += "ARCH-XPOSED-LEGACY: $path:${index + 1}: ${line.trim()}; use API 102 contracts only"
                }
                if (mojibake.containsMatchIn(line)) {
                    errors += "ARCH-ENCODING: $path:${index + 1}: possible mojibake; keep repository text UTF-8"
                }
            }
        }

        val entryPath = "app/src/main/kotlin/com/xiyunmn/qdhelper/entry/QDHelperModule.kt"
        val entry = root.resolve(entryPath).takeIf(File::isFile)?.readText(Charsets.UTF_8).orEmpty()
        val entryTokens = listOf("override fun onHotReloading", "override fun onHotReloaded")
        entryTokens.filterNot(entry::contains).forEach { token ->
            errors += "ARCH-HOT-RELOAD: $entryPath is missing $token; preserve API 102 replacement and cleanup behavior"
        }
        val coordinatorPath = "app/src/main/kotlin/com/xiyunmn/qdhelper/runtime/ModuleCoordinator.kt"
        val coordinator = root.resolve(coordinatorPath).takeIf(File::isFile)?.readText(Charsets.UTF_8).orEmpty()
        listOf(
            "HookRuntime.prepareHotReloadReplacement",
            "RuntimeCleanupRegistry.runAndClear",
            "HookRuntime.detach(module)",
        ).filterNot(coordinator::contains).forEach { token ->
            errors += "ARCH-HOT-RELOAD: $coordinatorPath is missing $token; preserve API 102 replacement and cleanup behavior"
        }
        val hookRuntimePath = "modules/hook-runtime/src/main/kotlin/com/xiyunmn/qdhelper/hook/runtime/HookRuntime.kt"
        val hookRuntime = root.resolve(hookRuntimePath).takeIf(File::isFile)?.readText(Charsets.UTF_8).orEmpty()
        if ("Hook hot-replaced" !in hookRuntime) {
            errors += "ARCH-HOT-RELOAD: $hookRuntimePath is missing Hook hot-replaced; preserve replacement reporting"
        }
        val adapterPath = "modules/hook-runtime/src/main/kotlin/com/xiyunmn/qdhelper/hook/adapter/LibXposedHookInstaller.kt"
        val adapter = root.resolve(adapterPath).takeIf(File::isFile)?.readText(Charsets.UTF_8).orEmpty()
        if ("builder.setId(id)" !in adapter) {
            errors += "ARCH-HOT-RELOAD: $adapterPath is missing builder.setId(id); preserve stable ID replacement"
        }
        return CheckResult(errors = errors)
    }
}

internal object PackagedModuleChecker {
    fun check(apk: File): CheckResult {
        if (!apk.isFile) {
            return CheckResult(errors = listOf("ARCH-PACKAGED-MODULE: missing ${apk.invariantSeparatorsPath}; run :app:assembleDebug first"))
        }
        val errors = mutableListOf<String>()
        errors += NativeAbiChecker.check(apk).errors
        ZipFile(apk).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            val javaInitPath = "META-INF/xposed/java_init.list"
            val scopePath = "META-INF/xposed/scope.list"
            val modulePropPath = "META-INF/xposed/module.prop"
            listOf(javaInitPath, scopePath, modulePropPath).filterNot(names::contains).forEach { path ->
                errors += "ARCH-PACKAGED-MODULE: ${apk.name} is missing $path; preserve Xposed API 102 metadata"
            }
            fun entryText(path: String): String = zip.getEntry(path)?.let { entry ->
                zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.orEmpty()
            if (javaInitPath in names && entryText(javaInitPath).trim() != "com.xiyunmn.qdhelper.entry.QDHelperModule") {
                errors += "ARCH-PACKAGED-MODULE: java_init.list has the wrong entry FQCN"
            }
            if (scopePath in names && entryText(scopePath).trim() != "com.qidian.QDReader") {
                errors += "ARCH-PACKAGED-MODULE: scope.list has the wrong host package"
            }
            if (modulePropPath in names) {
                val prop = entryText(modulePropPath)
                if ("minApiVersion=102" !in prop || "targetApiVersion=102" !in prop || "autoHotReload=true" !in prop) {
                    errors += "ARCH-PACKAGED-MODULE: module.prop lost API 102/hot-reload metadata"
                }
            }
            names.filter { it.startsWith("io/github/libxposed/") }.forEach { entry ->
                errors += "ARCH-PACKAGED-MODULE: libxposed API implementation entry must not be packaged: $entry"
            }
        }
        return CheckResult(errors = errors)
    }
}

/** Verifies the native ABI contract of every QDHelper-produced APK. */
internal object NativeAbiChecker {
    const val REQUIRED_ABI = "arm64-v8a"
    private val libraryPath = Regex("^lib/([^/]+)/.+")

    fun check(apk: File): CheckResult {
        if (!apk.isFile) {
            return CheckResult(
                errors = listOf("ARCH-ABI: missing ${apk.invariantSeparatorsPath}"),
            )
        }
        val errors = mutableListOf<String>()
        ZipFile(apk).use { zip ->
            val names = zip.entries().asSequence().map { it.name.replace('\\', '/') }.toSet()
            val libraries = names
                .mapNotNull { libraryPath.matchEntire(it)?.groupValues?.get(1) }
                .toSet()
            libraries.filter { it != REQUIRED_ABI }.sorted().forEach { abi ->
                errors += "ARCH-ABI: ${apk.name} contains unsupported ABI $abi; only $REQUIRED_ABI is allowed"
            }
            val required = "lib/$REQUIRED_ABI/libdexkit.so"
            if (required !in names) {
                errors += "ARCH-ABI: ${apk.name} is missing $required"
            }
        }
        return CheckResult(errors = errors)
    }
}

internal object SourceBudgetChecker {
    private const val WARNING_LIMIT = 1_500

    fun check(root: File, graph: ModuleGraphConfig): CheckResult {
        val warnings = mutableListOf<String>()
        productionSources(root, graph).forEach { source ->
            val path = source.relativeTo(root).invariantSeparatorsPath
            val lines = source.readLines(Charsets.UTF_8).size
            if (lines >= WARNING_LIMIT) {
                warnings +=
                    "ARCH-FILE-BUDGET: $path has $lines lines (warning limit $WARNING_LIMIT)"
            }
        }
        return CheckResult(warnings = warnings)
    }
}

internal object AndroidResourceConventionChecker {
    fun check(root: File, graph: ModuleGraphConfig): CheckResult {
        val errors = graph.modules.values.asSequence()
            .filter { it.plugin == "library" }
            .filter { module -> hasAndroidResources(root.resolve(moduleDirectory(module.path))) }
            .mapNotNull { module ->
                val requiredPlugin = if (module.path.startsWith(":features:")) {
                    "qdhelper.android.resource-feature"
                } else {
                    "qdhelper.android.resource-library"
                }
                val buildFile = root.resolve(moduleDirectory(module.path)).resolve("build.gradle.kts")
                val buildScript = buildFile.takeIf(File::isFile)?.readText(Charsets.UTF_8).orEmpty()
                if (appliesConventionPlugin(buildScript, requiredPlugin)) {
                    null
                } else {
                    "ARCH-ANDROID-RESOURCES: ${module.path}: src/*/res contains files but $requiredPlugin is not applied; opt in to Android resource processing explicitly"
                }
            }
            .sorted()
            .toList()
        return CheckResult(errors = errors)
    }

    private fun hasAndroidResources(moduleRoot: File): Boolean {
        val sourceRoot = moduleRoot.resolve("src")
        if (!sourceRoot.isDirectory) return false
        return sourceRoot.walkTopDown().any { file ->
            if (!file.isFile) return@any false
            val segments = file.relativeTo(sourceRoot).invariantSeparatorsPath.split('/')
            segments.size >= 3 && segments[1] == "res"
        }
    }

    private fun appliesConventionPlugin(buildScript: String, pluginId: String): Boolean =
        Regex("""(?m)^\s*id\(\s*"${Regex.escape(pluginId)}"\s*\)""").containsMatchIn(buildScript)
}

internal object PackagePathChecker {
    fun check(root: File, graph: ModuleGraphConfig): CheckResult {
        val errors = mutableListOf<String>()
        graph.modules.values.forEach { module ->
            val moduleRoot = root.resolve(moduleDirectory(module.path))
            listOf("src/main/kotlin", "src/main/java").forEach { relativeSourceRoot ->
                val sourceRoot = moduleRoot.resolve(relativeSourceRoot)
                if (!sourceRoot.exists()) return@forEach
                sourceRoot.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.forEach { source ->
                    val relative = source.relativeTo(sourceRoot).invariantSeparatorsPath
                    val expected = relative.substringBeforeLast('/', "").replace('/', '.')
                    val actual = source.useLines(Charsets.UTF_8) { lines ->
                        lines.firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.removeSuffix(";")
                    }
                    val path = source.relativeTo(root).invariantSeparatorsPath
                    if (actual != expected) {
                        errors += "ARCH-PACKAGE-MAP: $path declares $actual, expected $expected; move the file or fix its package"
                    } else if (module.path != ":app" &&
                        actual != module.packagePrefix && !actual.startsWith("${module.packagePrefix}.")
                    ) {
                        errors += "ARCH-PACKAGE-MAP: $path declares $actual outside ${module.packagePrefix}; use the canonical module prefix"
                    }
                }
            }
        }
        return CheckResult(errors = errors)
    }
}

internal data class SourceViolation(
    val ruleId: String,
    val path: String,
    val line: Int,
    val token: String,
) {
    fun message(): String = when (ruleId) {
        "ARCH-RESOLVER-FALLBACK" ->
            "$ruleId: $path:$line: $token was removed; use the canonical resolver or the audited early-target API"
        "ARCH-EARLY-RESOLVER" ->
            "$ruleId: $path:$line: $token is reserved for the audited startup/dialog target boundary"
        "ARCH-WINDOW-SUBTYPE" ->
            "$ruleId: $path:$line: $token puts the window inside [FIRST_SUB_WINDOW, LAST_SUB_WINDOW]; " +
                "the framework then disables text selection in every EditText it hosts"
        else -> "$ruleId: $path:$line: $token is outside its target boundary; route through the owning API"
    }
}

internal object SourcePolicyChecker {
    private data class LexedLine(val code: String, val strings: List<String>)

    fun check(root: File, graph: ModuleGraphConfig): CheckResult =
        CheckResult(errors = inventory(root, graph).map(SourceViolation::message))

    fun inventory(root: File, graph: ModuleGraphConfig): List<SourceViolation> {
        val violations = mutableListOf<SourceViolation>()
        productionSources(root, graph).forEach { source ->
            val path = source.relativeTo(root).invariantSeparatorsPath
            val module = moduleForPath(path, graph) ?: return@forEach
            val lexed = lex(source.readText(Charsets.UTF_8))
            if (source.name.endsWith("Queries.kt")) {
                val codeText = lexed.joinToString("\n", transform = LexedLine::code)
                parameterizedQueryFactory.findAll(codeText).forEach { match ->
                    val line = codeText.take(match.range.first).count { it == '\n' } + 1
                    violations += SourceViolation(
                        ruleId = "ARCH-QUERY-FACTORY",
                        path = path,
                        line = line,
                        token = "parameterized query factory",
                    )
                }
            }
            lexed.forEachIndexed { index, line ->
                val number = index + 1
                val tokens = linkedSetOf<Pair<String, String>>()
                fun code(rule: String, token: String, regex: Regex) {
                    if (regex.containsMatchIn(line.code)) tokens += rule to token
                }
                code("ARCH-HOOK-RAW", ".hook(", Regex("""\.hook\s*\("""))
                code("ARCH-XPOSED", "io.github.libxposed", Regex("""\bio\.github\.libxposed\b"""))
                code("ARCH-DEXKIT", "LuckyPray DexKit", Regex("""\borg\.luckypray\.dexkit\b|\bDexKitBridge\b"""))
                code("ARCH-DEXKIT", "legacy dexkit import", Regex("""^\s*import\s+com\.xiyunmn\.qdhelper\.dexkit\."""))
                reflectionPatterns.forEach { (token, regex) -> code("ARCH-REFLECT", token, regex) }
                code("ARCH-PREFS", "raw SharedPreferences", Regex("""\bSharedPreferences\b|\bgetSharedPreferences\s*\("""))
                if (isLifecycleHookContext(line.code)) {
                    code("ARCH-ACTIVITY-HOOK", "Activity lifecycle hook", Regex("""\bonCreate\b|\bonResume\b|\bonPause\b|\bonDestroy\b|\bdispatchTouchEvent\b|\bonWindowFocusChanged\b|\bonActivityResult\b"""))
                }
                code("ARCH-LONGPRESS-HOOK", "performLongClick", Regex("""\bperformLongClick\b"""))
                code("ARCH-THREAD", "unmanaged thread", Regex("""\bExecutors\.new\w*\s*\(|\bThread\s*\(|\bScheduledThreadPoolExecutor\s*\("""))
                code("ARCH-HTTP", "HttpURLConnection", Regex("""\bHttpURLConnection\b"""))
                code("ARCH-LOG", "direct Android log", Regex("""\bandroid\.util\.Log\b|\bLog\.(?:v|d|i|w|e|wtf)\s*\("""))
                code(
                    "ARCH-LEGACY",
                    "removed architecture facade",
                    Regex(
                        """^\s*import\s+com\.xiyunmn\.qdhelper\.(?:""" +
                            """core\.(?:HookApi|HookRegistry|HostReflection|HotReloadCleanupRegistry|SafeLog)|""" +
                            """settings\.ModuleSettings|runtime\.HostRuntime|feature\.api\.compat\.|host\.api\.compat\.)""",
                    ),
                )
                code("ARCH-LEGACY", "DexKitTargets", Regex("""^\s*import\s+com\.xiyunmn\.qdhelper\.dexkit\.DexKitTargets\b"""))
                code(
                    "ARCH-LEGACY",
                    "legacy resolver",
                    Regex("""^\s*import\s+com\.xiyunmn\.qdhelper\.host\.resolver\.dexkit\.legacy\."""),
                )
                code("ARCH-TARGET-ID", "dynamic hashCode target ID", Regex("""\bid\s*=.*\.hashCode\s*\("""))
                code("ARCH-CATALOG-DEDUP", "silent distinctBy", Regex("""\.distinctBy\b"""))
                code(
                    "ARCH-RESOLVER-FALLBACK",
                    "feature-owned resolver fallback",
                    Regex("""\bresolveMethods?WithFallback\b"""),
                )
                code("ARCH-EARLY-RESOLVER", "resolveEarlyMethod", Regex("""\bresolveEarlyMethod\b"""))
                code(
                    "ARCH-WINDOW-SUBTYPE",
                    "sub-window window type",
                    Regex(
                        """\.setType\s*\(\s*(?:1[0-9]{3}\b|""" +
                            """(?:WindowManager\.LayoutParams\.)?TYPE_APPLICATION_""" +
                            """(?:PANEL|MEDIA|SUB_PANEL|ATTACHED_DIALOG|MEDIA_OVERLAY|ABOVE_SUB_PANEL)\b)""",
                    ),
                )
                code(
                    "ARCH-WINDOW-SUBTYPE",
                    "sub-window type constant",
                    Regex("""\bval\s+\w*(?:SUB_PANEL|WINDOW_TYPE)\w*\s*(?::\s*Int\s*)?=\s*1[0-9]{3}\b"""),
                )
                if ("/feature/" in path) {
                    code("ARCH-FEATURE-CROSS", "feature imports legacy ui", Regex("""^\s*import\s+com\.xiyunmn\.qdhelper\.ui\."""))
                }
                if (module.path.startsWith(":features:")) {
                    val ownPrefix = graph.modules.getValue(module.path).packagePrefix
                    Regex("""^\s*import\s+(com\.xiyunmn\.qdhelper\.feature\.[A-Za-z0-9_.]+)""")
                        .find(line.code)?.groupValues?.get(1)?.let { imported ->
                            if (imported != ownPrefix && !imported.startsWith("$ownPrefix.") &&
                                !imported.startsWith("com.xiyunmn.qdhelper.feature.api.")
                            ) {
                                tokens += "ARCH-FEATURE-CROSS" to "feature sibling import"
                            }
                        }
                }
                if (path.contains("/settings-ui/") || path.contains("/qdhelper/ui/Settings")) {
                    code(
                        "ARCH-SETTINGS-CROSS",
                        "settings imports feature",
                        Regex("""^\s*import\s+com\.xiyunmn\.qdhelper\.feature\.(?!api(?:\.|$))"""),
                    )
                }
                if (hostPrefix.containsMatchIn(line.code)) tokens += "ARCH-HOST-LITERAL" to "host type reference"
                line.strings.forEach { literal ->
                    if (literal in setOf("onCreate", "onResume", "onPause", "onDestroy", "dispatchTouchEvent", "onWindowFocusChanged", "onActivityResult") &&
                        isLifecycleHookContext(line.code)
                    ) {
                        tokens += "ARCH-ACTIVITY-HOOK" to "Activity lifecycle hook"
                    }
                    if (literal == "performLongClick") {
                        tokens += "ARCH-LONGPRESS-HOOK" to "performLongClick"
                    }
                    val shortReflective = classLike.matches(literal) &&
                        (source.name == "QDReaderHost.kt" || reflectionPatterns.any { it.second.containsMatchIn(line.code) })
                    if (hostPrefix.containsMatchIn(literal) || shortReflective) {
                        tokens += "ARCH-HOST-LITERAL" to literal
                    }
                }
                tokens.forEach { (rule, token) ->
                    if (!isCanonical(rule, module.path, path)) {
                        violations += SourceViolation(rule, path, number, token)
                    }
                }
            }
        }
        return violations.distinct().sortedWith(compareBy(SourceViolation::path, SourceViolation::line, SourceViolation::ruleId, SourceViolation::token))
    }

    private val reflectionPatterns = listOf(
        "Class.forName" to Regex("""\bClass\.forName\s*\("""),
        "loadClass" to Regex("""\.loadClass\s*\("""),
        "getDeclared" to Regex("""\.getDeclared(?:Method|Field|Constructor|Methods|Fields|Constructors)\s*\("""),
        "declaredMethods" to Regex("""\.declaredMethods\b"""),
        "declaredFields" to Regex("""\.declaredFields\b"""),
        "isAccessible" to Regex("""\.isAccessible\b"""),
        "setAccessible" to Regex("""\.setAccessible\s*\("""),
    )
    private val hostPrefix = Regex("""\bcom\.(?:qidian|qd|qq|tencent)\.""")
    private val classLike = Regex("""^[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+$""")
    private val parameterizedQueryFactory = Regex(
        """\bfun\s+(?:<[^>]+>\s*)?[A-Za-z_]\w*\s*\(\s*+(?!\))""",
    )
    private val lifecycleHookContext = Regex(
        """\b(?:findDeclaredMethodOrNull|declaredMethod|\.getDeclaredMethod|FrameworkReflection|hookAfter|hookAround|\.hook\s*\()""",
    )

    private fun isLifecycleHookContext(code: String): Boolean = lifecycleHookContext.containsMatchIn(code)

    private fun isCanonical(rule: String, module: String, path: String): Boolean = when (rule) {
        "ARCH-RESOLVER-FALLBACK" -> false
        "ARCH-EARLY-RESOLVER" ->
            module == ":host-api" || module == ":host-resolver" ||
                (module == ":features:startup" && (
                    path.endsWith("/StartupFeature.kt") ||
                        path.endsWith("/StartupImageStore.kt") ||
                        path.endsWith("/OfficialSplashLocalUnlock.kt")
                    )) ||
                (module == ":features:dialogs" && path.endsWith("/DialogSuppressFeature.kt"))
        "ARCH-HOOK-RAW" -> module == ":hook-runtime" && "/adapter/" in path
        "ARCH-XPOSED" -> module == ":hook-runtime" || module == ":app"
        "ARCH-DEXKIT" -> module == ":host-resolver" && "/dexkit/" in path
        "ARCH-REFLECT" ->
            module == ":host-resolver" && "/reflection/" in path ||
                module == ":hook-runtime" && "/framework/" in path ||
                module == ":app" && path.endsWith("/runtime/RuntimeCallbackBridges.kt") ||
                module == ":app" && path.endsWith("/runtime/NativeNetBridgeRuntime.kt") ||
                module == ":app" && path.endsWith("/ui/HostUiThemeResolver.kt")
        "ARCH-HOST-LITERAL" ->
            module == ":host-api" || module == ":host-resolver" ||
                module == ":hook-runtime" && "/framework/" in path ||
                module == ":app" && path.endsWith("/runtime/NativeNetBridgeRuntime.kt") ||
                module == ":app" && path.endsWith("/ui/HostUiThemeResolver.kt")
        "ARCH-PREFS" -> module == ":config" || (module == ":host-resolver" && "/cache/" in path)
        "ARCH-ACTIVITY-HOOK" ->
            module == ":hook-runtime" && "/activity/" in path ||
                module == ":app" && path.endsWith("/runtime/RuntimeCallbackBridges.kt") ||
                module == ":app" && path.endsWith("/runtime/HostLifecycleProbe.kt") ||
                module == ":app" && path.endsWith("/entry/restart/HostRestartTrampolineActivity.kt")
        "ARCH-LONGPRESS-HOOK" ->
            module == ":hook-runtime" && "/longpress/" in path ||
                module == ":app" && path.endsWith("/runtime/RuntimeCallbackBridges.kt")
        "ARCH-THREAD" ->
            module == ":hook-runtime" && "/concurrency/" in path ||
                module == ":host-resolver" && "/worker/" in path
        "ARCH-HTTP" -> module == ":shared-io"
        "ARCH-LOG" -> module == ":foundation" || module == ":hook-runtime"
        "ARCH-LEGACY",
        "ARCH-FEATURE-CROSS",
        "ARCH-SETTINGS-CROSS",
        "ARCH-TARGET-ID",
        "ARCH-CATALOG-DEDUP",
        "ARCH-WINDOW-SUBTYPE",
        "ARCH-QUERY-FACTORY" -> false
        else -> false
    }

    private fun lex(text: String): List<LexedLine> {
        var blockDepth = 0
        var inTriple = false
        return text.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { sourceLine ->
            val code = StringBuilder(sourceLine.length)
            val strings = mutableListOf<String>()
            var index = 0
            fun blank(count: Int) = repeat(count) { code.append(' ') }
            while (index < sourceLine.length) {
                when {
                    inTriple -> {
                        val end = sourceLine.indexOf("\"\"\"", index)
                        if (end < 0) {
                            blank(sourceLine.length - index)
                            index = sourceLine.length
                        } else {
                            blank(end + 3 - index)
                            index = end + 3
                            inTriple = false
                        }
                    }
                    blockDepth > 0 -> {
                        when {
                            sourceLine.startsWith("/*", index) -> { blockDepth++; blank(2); index += 2 }
                            sourceLine.startsWith("*/", index) -> { blockDepth--; blank(2); index += 2 }
                            else -> { code.append(' '); index++ }
                        }
                    }
                    sourceLine.startsWith("//", index) -> {
                        blank(sourceLine.length - index)
                        index = sourceLine.length
                    }
                    sourceLine.startsWith("/*", index) -> { blockDepth = 1; blank(2); index += 2 }
                    sourceLine.startsWith("\"\"\"", index) -> { inTriple = true; blank(3); index += 3 }
                    sourceLine[index] == '"' -> {
                        val value = StringBuilder()
                        val start = index
                        index++
                        var escaped = false
                        while (index < sourceLine.length) {
                            val char = sourceLine[index++]
                            when {
                                escaped -> { value.append(char); escaped = false }
                                char == '\\' -> escaped = true
                                char == '"' -> break
                                else -> value.append(char)
                            }
                        }
                        strings += value.toString()
                        blank(index - start)
                    }
                    sourceLine[index] == '\'' -> {
                        val start = index++
                        var escaped = false
                        while (index < sourceLine.length) {
                            val char = sourceLine[index++]
                            when {
                                escaped -> escaped = false
                                char == '\\' -> escaped = true
                                char == '\'' -> break
                            }
                        }
                        blank(index - start)
                    }
                    else -> code.append(sourceLine[index++])
                }
            }
            LexedLine(code.toString(), strings)
        }
    }
}

private fun moduleForPath(path: String, graph: ModuleGraphConfig): ModuleSpec? = graph.modules.values
    .sortedByDescending { moduleDirectory(it.path).length }
    .firstOrNull { module ->
        val prefix = moduleDirectory(module.path) + "/"
        path.startsWith(prefix)
    }

private fun moduleDirectory(path: String): String = when {
    path == ":app" -> "app"
    path.startsWith(":features:") -> "modules/features/${path.substringAfterLast(':')}"
    path.startsWith(":capability:") -> "modules/capability/${path.substringAfterLast(':')}"
    else -> "modules/${path.removePrefix(":")}"
}

private fun productionSources(root: File, graph: ModuleGraphConfig): List<File> = graph.modules.keys.asSequence()
    .flatMap { module ->
        val moduleRoot = root.resolve(moduleDirectory(module))
        sequenceOf(moduleRoot.resolve("src/main/kotlin"), moduleRoot.resolve("src/main/java"))
    }
    .filter { it.isDirectory }
    .flatMap { sourceRoot -> sourceRoot.walkTopDown() }
    .filter { file -> file.isFile && file.extension in setOf("kt", "java") }
    .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
    .toList()

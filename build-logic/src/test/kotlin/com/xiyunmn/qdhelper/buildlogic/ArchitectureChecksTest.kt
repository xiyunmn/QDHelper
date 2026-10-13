package com.xiyunmn.qdhelper.buildlogic

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitectureChecksTest {
    @Test
    fun glassFeatureCanUseSharedRenderingButCannotDependOnNavigation() {
        val graph = graph(
            spec(":app", setOf(":features:glass-bars", ":features:navigation"), plugin = "application"),
            spec(":features:glass-bars", setOf(":feature-api", ":shared-ui")),
            spec(":features:navigation", setOf(":feature-api")),
            spec(":feature-api"), spec(":shared-ui"),
        )
        val modules = listOf(
            ActualModule(":app", "application", listOf(ProjectEdge(":features:glass-bars", "implementation"))),
            ActualModule(":features:glass-bars", "library", listOf(ProjectEdge(":feature-api", "implementation"), ProjectEdge(":shared-ui", "implementation"))),
            ActualModule(":features:navigation", "library", listOf(ProjectEdge(":feature-api", "implementation"))),
            ActualModule(":feature-api", "library", emptyList()), ActualModule(":shared-ui", "library", emptyList()),
        )
        assertTrue(ModuleGraphChecker.check(graph, modules).errors.isEmpty())
        val crossed = modules.map {
            if (it.path == ":features:glass-bars") it.copy(dependencies = it.dependencies + ProjectEdge(":features:navigation", "implementation")) else it
        }
        assertTrue(ModuleGraphChecker.check(graph, crossed).errors.any { it.startsWith("ARCH-FEATURE-CROSS") })
    }

    @Test
    fun touchPauseAndDestroyHooksBelongToTheCentralActivityBridge() = withTempProject { root ->
        val callbacks = listOf("dispatchTouchEvent", "onPause", "onDestroy")
        val body = callbacks.joinToString("\n") { "fun hook$it() = FeatureHooks.findDeclaredMethodOrNull(type, \"$it\")" }
        root.resolve("modules/features/glass-bars/src/main/kotlin/com/example/Bad.kt").also { it.parentFile.mkdirs() }
            .writeText("package com.example\n$body\n", Charsets.UTF_8)
        root.resolve("app/src/main/kotlin/com/example/runtime/RuntimeCallbackBridges.kt").also { it.parentFile.mkdirs() }
            .writeText("package com.example.runtime\n$body\n", Charsets.UTF_8)
        val violations = SourcePolicyChecker.inventory(root, graph(spec(":app", plugin = "application"), spec(":features:glass-bars")))
        assertEquals(3, violations.count { it.ruleId == "ARCH-ACTIVITY-HOOK" })
        assertTrue(violations.filter { it.ruleId == "ARCH-ACTIVITY-HOOK" }.all { it.path.contains("features/glass-bars") })
    }

    @Test
    fun repositoryHygieneRejectsNestedOutputsAcrossPathSeparators() {
        val result = RepositoryHygieneChecker.check(
            listOf(
                "app/src/main/Fine.kt",
                "modules/features/reader/build/output.apk",
                "modules\\config\\release\\output-metadata.json",
            ),
        )

        assertEquals(2, result.errors.size)
        assertTrue(result.errors.all { it.startsWith("ARCH-REPO-OUTPUT") })
    }

    @Test
    fun productContractAcceptsApi102FixtureAndReportsMetadataDrift() = withTempProject { root ->
        writeProductContractFixture(root)
        assertTrue(ProductContractChecker.check(root).errors.isEmpty())

        root.resolve("app/src/main/resources/META-INF/xposed/module.prop")
            .writeText("minApiVersion=101\n", Charsets.UTF_8)
        val result = ProductContractChecker.check(root)

        assertTrue(result.errors.any { it.startsWith("ARCH-XPOSED-METADATA") && it.contains("API 102") })
    }

    @Test
    fun packagedModuleRequiresMetadataAndDexKitButRejectsBundledLibxposedEntries() = withTempProject { root ->
        val good = root.resolve("good.apk")
        writeZip(
            good,
            mapOf(
                "META-INF/xposed/java_init.list" to "com.xiyunmn.qdhelper.entry.QDHelperModule\n",
                "META-INF/xposed/scope.list" to "com.qidian.QDReader\n",
                "META-INF/xposed/module.prop" to "minApiVersion=102\ntargetApiVersion=102\nautoHotReload=true\n",
                "lib/arm64-v8a/libdexkit.so" to "fixture",
            ),
        )
        assertTrue(PackagedModuleChecker.check(good).errors.isEmpty())

        val bad = root.resolve("bad.apk")
        writeZip(bad, mapOf("io/github/libxposed/api/XposedModule.class" to "fixture"))
        val errors = PackagedModuleChecker.check(bad).errors
        assertTrue(errors.any { it.contains("libdexkit.so") })
        assertTrue(errors.any { it.contains("must not be packaged") })
    }

    @Test
    fun nativeAbiCheckerAcceptsOnlyArm64WithDexKit() = withTempProject { root ->
        val good = root.resolve("arm64.apk")
        writeZip(good, mapOf("lib/arm64-v8a/libdexkit.so" to "fixture"))
        assertTrue(NativeAbiChecker.check(good).errors.isEmpty())

        val bad = root.resolve("mixed.apk")
        writeZip(
            bad,
            mapOf(
                "lib/arm64-v8a/libdexkit.so" to "fixture",
                "lib/armeabi-v7a/libdexkit.so" to "fixture",
                "lib/x86/libother.so" to "fixture",
                "lib/x86_64/libother.so" to "fixture",
            ),
        )
        val errors = NativeAbiChecker.check(bad).errors
        assertTrue(errors.any { it.contains("armeabi-v7a") })
        assertTrue(errors.any { it.contains("x86") && !it.contains("x86_64") })
        assertTrue(errors.any { it.contains("x86_64") })
    }

    @Test
    fun nativeAbiCheckerReportsMissingDexKitArm64Library() = withTempProject { root ->
        val bad = root.resolve("missing.apk")
        writeZip(bad, mapOf("lib/arm64-v8a/libother.so" to "fixture"))
        assertTrue(NativeAbiChecker.check(bad).errors.any { it.contains("libdexkit.so") })
    }

    @Test
    fun moduleGraphAllowsDeclaredEdgeAndRejectsFeatureSibling() {
        val graph = graph(
            spec(":app", setOf(":features:a", ":features:b"), plugin = "application"),
            spec(":features:a", setOf(":feature-api")),
            spec(":features:b", setOf(":feature-api")),
            spec(":feature-api"),
        )
        val result = ModuleGraphChecker.check(
            graph,
            listOf(
                ActualModule(":app", "application", listOf(ProjectEdge(":features:a", "implementation"))),
                ActualModule(":features:a", "library", listOf(ProjectEdge(":features:b", "implementation"))),
                ActualModule(":features:b", "library", emptyList()),
                ActualModule(":feature-api", "library", emptyList()),
            ),
        )

        assertEquals(1, result.errors.count { it.startsWith("ARCH-FEATURE-CROSS") })
        assertTrue(result.errors.single { it.startsWith("ARCH-FEATURE-CROSS") }.contains(":features:a -> :features:b"))
    }

    @Test
    fun moduleGraphReportsCycleWithFullPath() {
        val graph = graph(
            spec(":app", setOf(":foundation"), plugin = "application"),
            spec(":foundation", setOf(":app")),
        )

        val result = ModuleGraphChecker.check(graph, emptyList())

        assertTrue(result.errors.any { it.startsWith("ARCH-MODULE-CYCLE") && it.contains(":app -> :foundation -> :app") })
    }

    @Test
    fun moduleGraphRejectsRuntimeModuleMissingFromGraph() {
        val graph = graph(spec(":app", plugin = "application"))

        val result = ModuleGraphChecker.check(
            graph,
            listOf(
                ActualModule(":app", "application", emptyList()),
                ActualModule(":features:undeclared", "library", emptyList()),
            ),
        )

        assertTrue(result.errors.any { it.contains(":features:undeclared") && it.contains("absent from architecture/module-graph.toml") })
    }

    @Test
    fun moduleGraphRejectsStaleGraphEntryMissingFromRuntime() {
        val graph = graph(
            spec(":app", plugin = "application"),
            spec(":foundation"),
        )

        val result = ModuleGraphChecker.check(
            graph,
            listOf(ActualModule(":app", "application", emptyList())),
        )

        assertTrue(result.errors.any { it.contains(":foundation") && it.contains("no matching Gradle runtime module") })
    }

    @Test
    fun moduleGraphParserDoesNotHardcodeRuntimeModuleCount() = withTempProject { root ->
        val file = root.resolve("module-graph.toml")
        file.writeText(
            """
            schema_version = 1

            [[modules]]
            path = ":app"
            package_prefix = "com.example"
            plugin = "application"
            allowed_dependencies = [":foundation"]

            [[modules]]
            path = ":foundation"
            package_prefix = "com.example.foundation"
            plugin = "library"
            allowed_dependencies = []
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )

        val graph = ModuleGraphConfig.parse(file)

        assertEquals(setOf(":app", ":foundation"), graph.modules.keys)
    }

    @Test
    fun packagePathCheckerReportsExactMismatch() = withTempProject { root ->
        val source = root.resolve("app/src/main/kotlin/com/example/Foo.kt")
        source.parentFile.mkdirs()
        source.writeText("package com.wrong\n", Charsets.UTF_8)

        val result = PackagePathChecker.check(
            root,
            graph(spec(":app", plugin = "application", packagePrefix = "com.example")),
        )

        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().contains("expected com.example"))
    }

    @Test
    fun sourceBudgetWarnsFromFifteenHundredLinesWithoutAHardLimit() = withTempProject { root ->
        val source = root.resolve("app/src/main/kotlin/com/example/Foo.kt")
        source.parentFile.mkdirs()
        fun writeLines(count: Int) {
            source.writeText((1..count).joinToString("\n") { "// $it" } + "\n", Charsets.UTF_8)
        }

        val graph = graph(spec(":app", plugin = "application"))
        writeLines(1_499)
        assertEquals(CheckResult(), SourceBudgetChecker.check(root, graph))

        for (lines in listOf(1_500, 1_501, 10_000)) {
            writeLines(lines)
            val result = SourceBudgetChecker.check(root, graph)
            assertTrue(result.errors.isEmpty())
            assertEquals(1, result.warnings.size)
            assertTrue(result.warnings.single().contains("app/src/main/kotlin/com/example/Foo.kt has $lines lines"))
        }
    }

    @Test
    fun androidResourcesRequireExplicitLibraryOrFeatureConvention() = withTempProject { root ->
        fun write(path: String, content: String = "fixture") {
            root.resolve(path).also { it.parentFile.mkdirs() }.writeText(content, Charsets.UTF_8)
        }
        val graph = graph(
            spec(":shared-ui"),
            spec(":features:sample"),
        )
        write("modules/shared-ui/src/main/res/values/colors.xml")
        write(
            "modules/shared-ui/build.gradle.kts",
            "// id(\"qdhelper.android.resource-library\") is documentation only\nplugins { id(\"qdhelper.android.library\") }",
        )
        write("modules/features/sample/src/debug/res/layout/sample.xml")
        write(
            "modules/features/sample/build.gradle.kts",
            "plugins {\n    id(\"qdhelper.android.resource-feature\")\n}",
        )

        val rejected = AndroidResourceConventionChecker.check(root, graph)
        assertEquals(1, rejected.errors.size)
        assertTrue(rejected.errors.single().contains("qdhelper.android.resource-library"))

        write(
            "modules/shared-ui/build.gradle.kts",
            "plugins {\n    id(\"qdhelper.android.resource-library\")\n}",
        )
        assertTrue(AndroidResourceConventionChecker.check(root, graph).errors.isEmpty())
    }

    @Test
    fun sourcePolicyIgnoresCommentsButRejectsRawHookDexKitAndFeatureUiImport() = withTempProject { root ->
        val source = root.resolve("app/src/main/kotlin/com/xiyunmn/qdhelper/feature/sample/Sample.kt")
        source.parentFile.mkdirs()
        source.writeText(
            """
            package com.xiyunmn.qdhelper.feature.sample
            // module.hook(fake) and org.luckypray.dexkit are documentation only
            import com.xiyunmn.qdhelper.ui.ViewTools
            import com.xiyunmn.qdhelper.dexkit.DexKitTargets
            import com.xiyunmn.qdhelper.host.resolver.dexkit.legacy.DexKitLocator
            fun install() = module.hook(target)
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )
        val graph = graph(spec(":app", plugin = "application"))

        val result = SourcePolicyChecker.check(root, graph)

        assertTrue(result.errors.any { it.startsWith("ARCH-HOOK-RAW") })
        assertTrue(result.errors.any { it.startsWith("ARCH-DEXKIT") })
        assertTrue(result.errors.any { it.contains("legacy resolver") })
        assertTrue(result.errors.any { it.startsWith("ARCH-FEATURE-CROSS") })
        assertFalse(result.errors.any { it.contains(":2:") })
    }

    @Test
    fun sourcePolicyForbidsRemovedFallbackAndRestrictsEarlyResolver() = withTempProject { root ->
        fun write(module: String, name: String, body: String) {
            root.resolve("modules/features/$module/src/main/kotlin/com/example/$module/$name")
                .also { it.parentFile.mkdirs() }
                .writeText("package com.example.$module\n$body\n", Charsets.UTF_8)
        }
        write("account", "Bad.kt", "fun bad() = resolveMethodWithFallback()")
        write("account", "Early.kt", "fun badEarly() = resolveEarlyMethod()")
        write("startup", "StartupFeature.kt", "fun allowedEarly() = resolveEarlyMethod()")
        write("startup", "Other.kt", "fun badStartupEarly() = resolveEarlyMethod()")
        write("dialogs", "DialogSuppressFeature.kt", "fun allowedDialogEarly() = resolveEarlyMethod()")
        write("dialogs", "Other.kt", "fun badDialogEarly() = resolveEarlyMethod()")

        val violations = SourcePolicyChecker.inventory(
            root,
            graph(
                spec(":features:account", packagePrefix = "com.example.account"),
                spec(":features:startup", packagePrefix = "com.example.startup"),
                spec(":features:dialogs", packagePrefix = "com.example.dialogs"),
            ),
        )

        assertTrue(violations.any { it.ruleId == "ARCH-RESOLVER-FALLBACK" && it.path.endsWith("Bad.kt") })
        assertTrue(violations.any { it.ruleId == "ARCH-EARLY-RESOLVER" && it.path.endsWith("Early.kt") })
        assertTrue(violations.any { it.ruleId == "ARCH-EARLY-RESOLVER" && it.path.endsWith("Other.kt") })
        assertTrue(
            violations.any {
                it.ruleId == "ARCH-EARLY-RESOLVER" && it.path.endsWith("startup/Other.kt")
            },
        )
        assertFalse(
            violations.any {
                it.ruleId == "ARCH-EARLY-RESOLVER" && it.path.endsWith("StartupFeature.kt")
            },
        )
        assertFalse(
            violations.any {
                it.ruleId == "ARCH-EARLY-RESOLVER" && it.path.endsWith("DialogSuppressFeature.kt")
            },
        )
    }

    @Test
    fun sourcePolicyRejectsTrailingDistinctByAndParameterizedQueryFactoryButAllowsDslHelper() = withTempProject { root ->
        fun write(name: String, content: String) {
            root.resolve("modules/host-resolver/src/main/kotlin/com/example/query/$name")
                .also { it.parentFile.mkdirs() }
                .writeText(content.trimIndent() + "\n", Charsets.UTF_8)
        }
        write(
            "BadQueries.kt",
            """
            package com.example.query
            val unique = listOf(1, 1).distinctBy { it }
            fun createQuery(
                id: String,
            ) = id
            """,
        )
        write(
            "DexKitQueryDsl.kt",
            """
            package com.example.query
            fun createQuery(id: String) = id
            """,
        )

        val violations = SourcePolicyChecker.inventory(
            root,
            graph(spec(":host-resolver", packagePrefix = "com.example")),
        )

        assertTrue(violations.any { it.ruleId == "ARCH-CATALOG-DEDUP" && it.path.endsWith("BadQueries.kt") })
        assertTrue(violations.any { it.ruleId == "ARCH-QUERY-FACTORY" && it.path.endsWith("BadQueries.kt") })
        assertFalse(violations.any { it.ruleId == "ARCH-QUERY-FACTORY" && it.path.endsWith("DexKitQueryDsl.kt") })
    }

    @Test
    fun settingsUiMayUseFeatureContractsButNotFeatureImplementations() = withTempProject { root ->
        val source = root.resolve("modules/settings-ui/src/main/kotlin/com/example/settings/ui/Bridge.kt")
        source.parentFile.mkdirs()
        source.writeText(
            """
            package com.example.settings.ui
            import com.xiyunmn.qdhelper.feature.api.compat.LegacyModule
            import com.xiyunmn.qdhelper.feature.reader.ReaderFeature
            class Bridge
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )
        val graph = graph(
            spec(":settings-ui", packagePrefix = "com.example.settings.ui"),
        )

        val result = SourcePolicyChecker.inventory(root, graph)
        assertTrue(result.any { it.ruleId == "ARCH-SETTINGS-CROSS" && it.token == "settings imports feature" })
        assertTrue(result.any { it.ruleId == "ARCH-LEGACY" && it.token == "removed architecture facade" })
        assertFalse(result.any { it.path.endsWith("Bridge.kt") && it.ruleId == "ARCH-SETTINGS-CROSS" && it.line == 2 })
    }

    private fun graph(vararg specs: ModuleSpec) = ModuleGraphConfig(specs.associateBy { it.path })

    private fun spec(
        path: String,
        dependencies: Set<String> = emptySet(),
        plugin: String = "library",
        packagePrefix: String = "com.example",
    ) = ModuleSpec(path, packagePrefix, plugin, dependencies, allowApiProjectDependencies = false)

    private fun writeProductContractFixture(root: File) {
        fun write(path: String, content: String) {
            root.resolve(path).also { it.parentFile.mkdirs() }.writeText(content, Charsets.UTF_8)
        }
        write(
            "app/src/main/resources/META-INF/xposed/java_init.list",
            "com.xiyunmn.qdhelper.entry.QDHelperModule\n",
        )
        write("app/src/main/resources/META-INF/xposed/scope.list", "com.qidian.QDReader\n")
        write(
            "app/src/main/resources/META-INF/xposed/module.prop",
            "minApiVersion=102\ntargetApiVersion=102\nautoHotReload=true\n",
        )
        write(
            "app/src/main/kotlin/com/xiyunmn/qdhelper/entry/QDHelperModule.kt",
            "override fun onHotReloading override fun onHotReloaded",
        )
        write(
            "app/src/main/kotlin/com/xiyunmn/qdhelper/runtime/ModuleCoordinator.kt",
            "HookRuntime.prepareHotReloadReplacement RuntimeCleanupRegistry.runAndClear HookRuntime.detach(module)",
        )
        write(
            "modules/hook-runtime/src/main/kotlin/com/xiyunmn/qdhelper/hook/runtime/HookRuntime.kt",
            "Hook hot-replaced",
        )
        write(
            "modules/hook-runtime/src/main/kotlin/com/xiyunmn/qdhelper/hook/adapter/LibXposedHookInstaller.kt",
            "builder.setId(id)",
        )
    }

    private fun writeZip(file: File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun withTempProject(block: (File) -> Unit) {
        val root = Files.createTempDirectory("qdhelper-architecture-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}

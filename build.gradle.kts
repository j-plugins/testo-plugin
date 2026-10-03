import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java") // Java support
    alias(libs.plugins.kotlin) // Kotlin support
    alias(libs.plugins.intelliJPlatform) // IntelliJ Platform Gradle Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
    alias(libs.plugins.qodana) // Gradle Qodana Plugin
    alias(libs.plugins.kover) // Gradle Kover Plugin
    // The OpenIDE distribution and plugin store, for the `openide` variant only. Applying it downloads nothing: it only
    // registers `openide(...)`, `openideRepository()` and `openideMarketplace {}`, which the PhpStorm variants never call.
    alias(libs.plugins.openidePlatform)
}

group = providers.gradleProperty("pluginGroup").get()

// Which platform variant to build — see the `phpApi` comment in gradle.properties. Selects the platform version,
// since/until range and per-platform modules; the two artifacts no longer differ in source (coverage is now on public API).
val phpApi = providers.gradleProperty("phpApi").get()

// The OpenIDE variant: OpenIDE with the PHP for OpenIDE plugin in place of IDEA Ultimate with PhpStorm's PHP plugin.
val isOpenIde = phpApi == "openide"

fun apiProperty(name: String) = providers.gradleProperty("$name.$phpApi")

/** Where ru.openide.platform unpacks the OpenIDE distribution `openide(...)` asks for. */
val openIdeDistribution: Provider<Directory> =
    apiProperty("platformVersion").flatMap { layout.buildDirectory.dir("tmp/openide/openIDE/$it") }

// Both implementations compile into the same packages of the core, so the OpenIDE variant gets a build directory of its
// own: sharing build/ would mix one variant's classes into the other's jar.
if (isOpenIde) {
    layout.buildDirectory = layout.projectDirectory.dir("build/openide")
}

// The Marketplace keys uploads by version and rejects a second one carrying a version it already has, so the two
// variants cannot both be "2026.3.1". The target API becomes a fourth component: it keeps each variant unique, sorts
// above the plain version so existing installs still see an update, and — unlike a `-252` suffix — is not a SemVer
// pre-release, so `channels` below (which reads the bare pluginVersion) still resolves to the default channel.
// The OpenIDE variant goes to a store of its own, where the platform branch it targets serves as that component.
val versionTag = if (isOpenIde) apiProperty("pluginSinceBuild").get() else phpApi
val artifactVersion = providers.gradleProperty("pluginVersion").map { "$it.$versionTag" }

version = artifactVersion.get()

// Set the JVM language level used to build the project. OpenIDE's platform is compiled for Java 25, and its inline
// functions (BaseState's `enum()`, `list()`) can only be inlined into code of the same target.
kotlin {
    jvmToolchain(if (isOpenIde) 25 else 21)
}

// Everything that needs the PHP plugin's own classes lives in a separate set of sources, next to the core in src/main,
// which never references it: src/phpstorm on PhpStorm's PHP plugin, src/openide on PHP for OpenIDE.
val phpImpl = if (isOpenIde) "openide" else "phpstorm"

sourceSets {
    main {
        kotlin.srcDir("src/$phpImpl/kotlin")
        resources.srcDir("src/$phpImpl/resources")
    }
    test {
        kotlin.srcDir("src/${phpImpl}Test/kotlin")
    }
}

// Configure project's dependencies
repositories {
    mavenCentral()

    if (isOpenIde) {
        // The OpenIDE distribution, and PHP for OpenIDE from the OpenIDE plugin store — only from there, never from the
        // JetBrains Marketplace. The store is reached through a local proxy the plugin starts while configuring.
        openideRepository()
        openideMarketplace {
            plugin("ru.openide.openphp")
        }
    }

    // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
    intellijPlatform {
        defaultRepositories()
    }
}

// Dependencies are managed with Gradle version catalog - read more: https://docs.gradle.org/current/userguide/platforms.html#sub:version-catalog
dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        if (isOpenIde) {
            openide(apiProperty("platformVersion"))
        } else {
            create(providers.gradleProperty("platformType"), apiProperty("platformVersion"))
        }

        // Plugin Dependencies. Uses `platformBundledPlugins` property from the gradle.properties file for bundled IntelliJ Platform plugins.
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })

        // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file for plugin from JetBrains Marketplace.
        plugins(apiProperty("platformPlugins").map { it.split(',') })

        // Module Dependencies. Uses `platformBundledModules` property from the gradle.properties file for bundled IntelliJ Platform modules.
        bundledModules(apiProperty("platformBundledModules").map { it.split(',') })

        testFramework(TestFrameworkType.Platform)
    }
}

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = artifactVersion

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        ideaVersion {
            sinceBuild = apiProperty("pluginSinceBuild")
            untilBuild = apiProperty("pluginUntilBuild").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        // The OpenIDE variant is published to the OpenIDE plugin store, with a token of its own.
        if (isOpenIde) {
            host = "https://plugins.openide.ru"
            token = providers.environmentVariable("OPENIDE_PUBLISH_TOKEN")
        } else {
            token = providers.environmentVariable("PUBLISH_TOKEN")
        }
        // The pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/deployment.html#specifying-a-release-channel
        channels = providers.gradleProperty("pluginVersion").map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
    }

    pluginVerification {
        ides {
            if (isOpenIde) {
                // The recommended IDEs are the ones the JetBrains Marketplace lists for this plugin, and OpenIDE is not
                // among them; verify against the OpenIDE distribution the variant is built with instead.
                local(openIdeDistribution)
            } else {
                recommended()
            }
        }
    }
}

// Configure Gradle Kover Plugin - read more: https://github.com/Kotlin/kotlinx-kover#configuration
kover {
    // The OpenIDE tests render file templates, and Velocity reads its own constants by reflection: the counter field
    // Kover adds to an instrumented class breaks it ("DeprecatedRuntimeConstants.__$hits$__ field isn't properly named").
    if (isOpenIde) {
        currentProject {
            instrumentation {
                excludedClasses.add("org.apache.velocity.*")
            }
        }
    }
    reports {
        total {
            xml {
                onCheck = true
            }
        }
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }
    runIde {
        autoReload = false
    }
    buildPlugin {
        // The OpenIDE and 262 variants share a version, so the name tells their archives apart.
        if (isOpenIde) {
            archiveBaseName = "${project.name}-openide"
        }
    }
    clean {
        // Each variant cleans its own output: a PhpStorm variant leaves build/openide alone. The OpenIDE variant spares
        // the distribution ru.openide.platform unpacks into its build/tmp/openide while CONFIGURING, before `clean`
        // runs — deleting it in `clean compileKotlin` would take away the classpath the compilation is about to use,
        // and it is gigabytes to download and unpack again.
        val buildDirectory = layout.buildDirectory.get().asFile
        setDelete(provider {
            buildDirectory.listFiles().orEmpty().flatMap { child ->
                when {
                    isOpenIde && child.name == "tmp" -> child.listFiles().orEmpty().filter { it.name != "openide" }
                    !isOpenIde && child.name == "openide" -> emptyList()
                    else -> listOf(child)
                }
            }
        })
    }
    if (isOpenIde) {
        // Plugin Verifier cannot fetch PHP for OpenIDE from JetBrains Marketplace. Use the dependency
        // already resolved from the OpenIDE store, with the same version as the compilation classpath.
        val verifierHome = layout.buildDirectory.dir("pluginVerifierHome")
        val prepareVerifierPlugins = register<Sync>("prepareVerifierPlugins") {
            from(configurations.named("intellijPlatformPluginDependency"))
            into(verifierHome.map { it.dir("loaded-plugins") })
        }
        verifyPlugin {
            dependsOn(prepareVerifierPlugins)
            offline.set(true)
            systemProperty("plugin.verifier.home.dir", verifierHome.get().asFile.absolutePath)
        }
    }
    test {
        // VFS resolves macOS /var symlinks; fixtures and file lookups must use the same temporary path.
        systemProperty("java.io.tmpdir", file(System.getProperty("java.io.tmpdir")).canonicalPath)

        // The bundled Kotlin plugin's KotlinScriptDefinitionCodeVisionProvider cannot be instantiated in the 2026.2
        // test fixture (its message bundle is missing the provider's name key), and the error is logged from a
        // project startup activity — outside any window a test could guard — failing whichever test the project
        // came up under. Nothing here needs the Kotlin plugin, so it is kept out of the test IDE entirely.
        systemProperty("idea.suppressed.plugins.id", "org.jetbrains.kotlin")

        if (isOpenIde) {
            // OpenIDE bundles some fifty plugins, and a LOG.error from any of them while it starts fails the test that
            // happens to be running. Load only this plugin and PHP for OpenIDE into the test IDE.
            systemProperty("idea.load.plugins.id", "com.github.xepozz.testo,ru.openide.openphp")
            // The OpenIDE Pro promotion plugin shares a message bundle name with another bundled plugin; on the test
            // classpath, where every bundled plugin's jars end up together, the wrong bundle wins and the notification
            // groups fail to load. Nothing here needs it.
            classpath = classpath.filter { "openide-license-checker" !in it.name }
        }
    }
}

intellijPlatformTesting {
    runIde {
        // The build targets IU (see platformType), but the sandbox can be PhpStorm — closer to most users' setup.
        // Its own version property: PhpStorm publishes releases, not the IU build numbers `platformVersion` holds.
        // OpenIDE is what the OpenIDE variant runs in; `runIde` already starts it.
        if (!isOpenIde) {
            register("runPhpStorm") {
                type = IntelliJPlatformType.PhpStorm
                version = apiProperty("phpStormVersion")

                task {
                    autoReload = false
                }
            }
        }

        register("runIdeForUiTests") {
            task {
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Drobot-server.port=8082",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.consents.confirmation.enabled=false",
                    )
                }
            }

            plugins {
                robotServerPlugin()
            }
        }
    }
}

// A Gradle build resolves exactly one IntelliJ Platform, so one invocation can only ever build one variant. Make
// `publishPlugin` still release everything by re-entering Gradle once per remaining API; `phpApiSingle` marks those
// nested invocations so they do not fan out again. The OpenIDE variant is released on its own, so publishing it builds
// no PhpStorm variant.
if (!providers.gradleProperty("phpApiSingle").isPresent && !isOpenIde) {
    val gradlew = if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "./gradlew"
    val otherVariants = providers.gradleProperty("phpApis").get()
        .split(',')
        .map { it.trim() }
        .filter { it != phpApi }
        .map { api ->
            tasks.register<Exec>("publishPluginFor$api") {
                group = "intellij platform"
                description = "Builds and publishes the $api variant in a nested Gradle invocation."
                workingDir = rootDir
                commandLine(gradlew, "publishPlugin", "-PphpApi=$api", "-PphpApiSingle=true", "--console=plain")
            }
        }

    tasks.named("publishPlugin") { dependsOn(otherVariants) }
}

# CLAUDE.md

## Project Overview

IntelliJ IDEA / PhpStorm plugin for **Testo** — a PHP testing framework.
Provides full IDE integration: test discovery, run configurations, code generation, inspections, and navigation.

- **Plugin ID:** `com.github.xepozz.testo`
- **Plugin Name:** Testo PHP
- **Author:** Dmitrii Derepko (@xepozz)
- **Repository:** https://github.com/j-plugins/testo-plugin
- **Marketplace:** JetBrains Marketplace

## Tech Stack

| Component            | Version / Value           |
|----------------------|---------------------------|
| Language             | Kotlin 2.3.0              |
| JVM Toolchain        | Java 21                   |
| IntelliJ Platform    | 2024.3.4 (IU — Ultimate)  |
| Min platform build   | 243 (2024.3.x)            |
| Build system         | Gradle 9.3.0              |
| IntelliJ Plugin SDK  | `org.jetbrains.intellij.platform` 2.11.0 |
| Changelog plugin     | `org.jetbrains.changelog` 2.5.0 |
| Code quality         | Qodana 2025.3.1           |
| Coverage             | Kover 0.9.4               |
| Test framework       | JUnit 4.13.2, OpenTest4J 1.3.0 |

## Build & Run Commands

```bash
# Build the plugin
./gradlew buildPlugin

# Run tests
./gradlew check

# Run IDE with plugin loaded (for manual testing)
./gradlew runIde

# Verify plugin compatibility
./gradlew verifyPlugin

# Run UI tests (requires robot-server)
./gradlew runIdeForUiTests
```

## Project Structure

```
src/main/kotlin/com/github/xepozz/testo/
├── TestoBundle.kt              # i18n message bundle
├── TestoClasses.kt             # FQN constants for Testo PHP classes/attributes
├── TestoContext.kt             # Live template context
├── TestoIcons.kt               # Icon definitions
├── TestoUtil.kt                # Project-level Testo availability check
├── TestoComposerConfig.kt      # Composer package detection
├── mixin.kt                    # PSI extension functions (isTestoMethod, isTestoClass, etc.)
├── PsiUtil.kt                  # General PSI utilities
├── ExitStatementsVisitor.kt    # PHP exit statement analysis
├── SpellcheckingDictionaryProvider.kt
│
├── actions/                    # Code generation actions
│   ├── TestoGenerateTestMethodAction.kt
│   └── TestoGenerateMethodActionBase.kt
│
├── index/                      # File-based index for data providers
│   ├── TestoDataProvidersIndex.kt
│   └── TestoDataProviderUtils.kt
│
├── references/                 # Reference resolution & implicit usage
│   └── TestFunctionImplicitUsageProvider.kt
│
├── tests/                      # Core test framework integration
│   ├── TestoFrameworkType.kt       # PhpTestFrameworkType implementation
│   ├── TestoTestDescriptor.kt     # Test class/method discovery
│   ├── TestoTestLocator.kt        # Stack trace → source navigation
│   ├── TestoTestRunLineMarkerProvider.kt  # Gutter run icons
│   ├── TestoStackTraceParser.kt   # Test output parsing
│   ├── TestoConsoleProperties.kt  # Console configuration
│   ├── TestoVersionDetector.kt    # Testo version detection
│   │
│   ├── actions/                # Test-specific actions
│   │   ├── TestoNewTestFromClassAction.kt
│   │   ├── TestoTestActionProvider.kt
│   │   ├── TestoRerunFailedTestsAction.kt
│   │   └── TestoRunCommandAction.kt
│   │
│   ├── inspections/
│   │   └── TestoInspectionSuppressor.kt
│   │
│   ├── overrides/              # UI customization
│   │
│   ├── run/                    # Run configuration subsystem
│   │   ├── TestoRunConfigurationType.kt
│   │   ├── TestoRunConfiguration.kt
│   │   ├── TestoRunConfigurationFactory.kt
│   │   ├── TestoRunConfigurationProducer.kt  # Context-based config creation
│   │   ├── TestoRunConfigurationHandler.kt
│   │   ├── TestoRunConfigurationSettings.kt
│   │   ├── TestoRunTestConfigurationEditor.kt
│   │   ├── TestoTestRunnerSettingsValidator.kt
│   │   ├── TestoTestMethodFinder.kt
│   │   ├── TestoRunnerSettings.kt
│   │   └── TestoDebugRunner.kt
│   │
│   └── runAnything/
│       └── TestoRunAnythingProvider.kt
│
└── ui/                         # UI components
    ├── TestoIconProvider.kt
    ├── TestoStackTraceConsoleFolding.kt
    └── PhpRunInheritorsListCellRenderer.kt

src/main/resources/
├── META-INF/plugin.xml         # Plugin descriptor (extensions, actions)
├── fileTemplates/              # New file templates (Testo Test.php.ft)
├── icons/                      # SVG icons (light + dark variants)
├── liveTemplates/Testo.xml     # Live templates: `test`, `data`
├── messages/TestoBundle.properties  # i18n strings
└── testo.dic                   # Spellchecker dictionary

src/test/                       # Unit tests (JUnit 4 + BasePlatformTestCase)
```

## Architecture

### Plugin Extension Points

The plugin registers extensions in `plugin.xml` under two namespaces:

- **`com.intellij`** — standard IntelliJ extensions: `fileType`, `runLineMarkerContributor`, `configurationType`, `runConfigurationProducer`, `programRunner`, `implicitUsageProvider`, `iconProvider`, `fileBasedIndex`, `console.folding`, `lang.inspectionSuppressor`, `testActionProvider`, live templates, etc.
- **`com.jetbrains.php`** — PHP-specific: `testFrameworkType` (TestoFrameworkType), `composerConfigClient` (TestoComposerConfig).

### Required Plugin Dependencies

- `com.intellij.modules.platform` — IntelliJ Platform core
- `com.jetbrains.php` — PHP language support (makes this plugin work in PhpStorm / IDEA Ultimate with PHP plugin)

### Testo PHP Framework — Supported Attributes

The plugin recognizes PHP attributes defined in `TestoClasses.kt`. Constants are grouped into arrays for reuse across the codebase:

| Group (array)              | Attributes (FQN)                                                                  |
|----------------------------|-----------------------------------------------------------------------------------|
| `TEST_ATTRIBUTES`          | `\Testo\Test`, `\Testo\Inline\TestInline`                                        |
| `TEST_INLINE_ATTRIBUTES`  | `\Testo\Inline\TestInline`                                                        |
| `DATA_ATTRIBUTES`          | `\Testo\Data\DataProvider`, `\Testo\Data\DataSet`, `\Testo\Data\DataUnion`, `\Testo\Data\DataCross`, `\Testo\Data\DataZip` |
| `BENCH_ATTRIBUTES`         | `\Testo\Bench`                                                                    |

Other constants: `ASSERT` (`\Testo\Assert`), `EXPECT` (`\Testo\Expect`), `ASSERTION_EXCEPTION`.

These arrays are spread into `RUNNABLE_ATTRIBUTES` (line markers) and `MEANINGFUL_ATTRIBUTES` (PsiUtil) — adding a new attribute to the group array automatically propagates it everywhere.

### Attribute Group Numbering

Attributes on a function/method are numbered **within their own group**, not globally. Each group has independent 0-based indexing. The groups are defined in `PsiUtil.ATTRIBUTE_GROUPS`:

| Group             | Source array              | Used for                                      |
|-------------------|---------------------------|-----------------------------------------------|
| test + data       | `TEST_DATA_ATTRIBUTES`    | `#[Test]` + data providers, numbered together |
| inline            | `TEST_INLINE_ATTRIBUTES`  | Inline test cases (`#[TestInline]`)           |
| bench             | `BENCH_ATTRIBUTES`        | Benchmark data (`#[Bench]`)                   |

Example for a function `foo` with multiple attributes:
```
#[Test]                 → type=test, foo:0
#[DataProvider(...)]    → type=test, foo:1
#[DataSet([...])]       → type=test, foo:2
#[DataZip(...)]         → type=test, foo:3
#[DataCross(...)]       → type=test, foo:4
#[TestInline(...)]      → type=inline, foo:0
#[TestInline(...)]      → type=inline, foo:1
#[TestInline(...)]      → type=inline, foo:2
#[Bench(...)]           → type=bench, foo:0
#[Bench(...)]           → type=bench, foo:1
```

`RUNNABLE_ATTRIBUTES` (used for gutter line markers) contains `TEST_ATTRIBUTES + BENCH_ATTRIBUTES + DATA_ATTRIBUTES`.

### Test Detection Logic (mixin.kt)

A PHP element is recognized as a Testo test when:
- **Method:** public + name starts with `test`, OR has any `TEST_ATTRIBUTES`
- **Function:** has any `TEST_ATTRIBUTES` (standalone test functions)
- **Benchmark:** has any `BENCH_ATTRIBUTES`
- **Class:** name ends with `Test` or `TestBase`, OR contains test/bench methods
- **File:** filename matches test class pattern, OR contains test classes/functions/benchmarks

### Key Subsystems

1. **Run Configuration** (`tests/run/`) — creates and manages run/debug configurations for Testo tests. `TestoRunConfigurationProducer` is the largest file (~527 lines) handling context-based config creation for methods, classes, files, data providers, and datasets.

2. **Line Markers** (`TestoTestRunLineMarkerProvider`) — adds green play buttons in the gutter next to test methods, classes, and data providers.

3. **Data Provider Index** (`index/TestoDataProvidersIndex`) — file-based index that maps test methods to their data providers for quick lookup across the project.

4. **Code Generation** — "Create Test from Class" action and "Generate Test Method" action integrated into IDE menus.

5. **Stack Trace Navigation** (`TestoTestLocator`) — click-to-navigate from test output to source code.

## Constraints & Important Notes

- **Platform:** IntelliJ IDEA Ultimate or PhpStorm only (requires `com.jetbrains.php` plugin)
- **Min IDE version:** 2024.3 (build 243+)
- **Kotlin stdlib is NOT bundled** (`kotlin.stdlib.default.dependency = false`) — uses the one shipped with IntelliJ
- **Gradle Configuration Cache** and **Build Cache** are enabled
- **Code and comments language:** English
- **Plugin description** is extracted from `README.md` between `<!-- Plugin description -->` markers during build
- **Signing & publishing** require environment variables: `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`, `PUBLISH_TOKEN`

## CI/CD

- **build.yml** (on push to main / PRs): build → test (with Kover coverage → Codecov) → Qodana inspections → plugin verification → draft release
- **release.yml** (on GitHub release): publish to JetBrains Marketplace, update changelog
- **run-ui-tests.yml** (manual): UI tests on Ubuntu, Windows, macOS via robot-server

## Conventions

- All source code is in Kotlin
- Package root: `com.github.xepozz.testo`
- i18n strings go in `messages/TestoBundle.properties`, accessed via `TestoBundle`
- Icons follow IntelliJ conventions: SVG with `_dark` suffix variant
- New extension points must be registered in `plugin.xml`
- Version follows SemVer; `pluginVersion` in `gradle.properties` is the single source of truth

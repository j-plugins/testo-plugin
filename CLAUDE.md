# CLAUDE.md

## Project Overview

IntelliJ IDEA Ultimate / PhpStorm plugin for **Testo** — a PHP testing framework.
Provides full IDE integration: test discovery, run/debug/coverage configurations, a channel-aware test console,
run history, code generation, inspections, and navigation.

- **Plugin ID:** `com.github.xepozz.testo`
- **Plugin Name:** Testo PHP
- **Author:** Dmitrii Derepko (@xepozz)
- **Repository:** https://github.com/j-plugins/testo-plugin
- **Marketplace:** https://plugins.jetbrains.com/plugin/28842-testo
- **Testo itself:** https://github.com/testo/testo (composer package `testo/testo`, binary `bin/testo`)

## Tech Stack

Single source of truth: `gradle/libs.versions.toml` (plugins/libs) + `gradle.properties` (platform, plugin version).
Dependabot bumps these regularly — read the files rather than trusting this table if something looks off.

| Component            | Version / Value                          |
|----------------------|------------------------------------------|
| Language             | Kotlin 2.4.0                             |
| JVM Toolchain        | Java 21                                  |
| IntelliJ Platform    | 2025.2 / 2026.2 (IU — IDEA Ultimate)     |
| Build range          | 252–261.* and 262+ (two artifacts)       |
| Plugin version       | `2026.3.1` (`pluginVersion`)             |
| Build system         | Gradle wrapper 9.6.0                     |
| IntelliJ Plugin SDK  | `org.jetbrains.intellij.platform` 2.18.0 |
| Changelog plugin     | `org.jetbrains.changelog` 2.5.0          |
| Code quality         | Qodana 2026.2.0                          |
| Coverage             | Kover 0.9.9 (XML report on `check`)      |
| Test framework       | JUnit 4.13.2, OpenTest4J 1.3.0           |

`platformPlugins` (marketplace deps, pinned to builds matching the target platform): `com.jetbrains.php`,
`phpstorm-remote-interpreter`, `php.codeception`, `php.behat`, `gherkin`, `xepozz.ide.introspector`
(+ `hackathon.indices.viewer` on 252 only — it has no 262 build).
`platformBundledModules`: `intellij.platform.coverage`, `intellij.spellchecker` (+ `intellij.platform.smRunner`,
`intellij.platform.testRunner`, `intellij.platform.ui.jcef` on 262, which split them out of the monolith — asking for
the JCEF module by name on 252 fails to resolve).

### Two build variants (`phpApi`)

PHP moved its coverage classes from `com.jetbrains.php.phpunit.coverage` to `com.intellij.php.coverage` in 2026.2, and
no single artifact can reference both (`<idea-version>` inside an optional descriptor is ignored, and there is no module
that exists only on ≤261 to gate on). So every platform-dependent property in `gradle.properties` is declared twice with
an API suffix and selected by `phpApi`:

```bash
./gradlew buildPlugin                # 262: platform 2026.2, since 262, no untilBuild
./gradlew buildPlugin -PphpApi=252   # 252: platform 2025.2, since 252, until 261.*
```

The only source difference is `src/php252/kotlin` vs `src/php262/kotlin`, each holding one file of `typealias`es
(`PhpCoverageRunner`, `PhpCoverageSuite`, `PhpUnitCoverageEngine`, `PhpUnitCoverageRunner`) pointing at whichever package
is current. `src/main/kotlin/.../coverage/` imports none of them — the aliases live in its own package. The enum
`PhpUnitCoverageEngine.CoverageEngine` did **not** move and is still imported directly from `com.jetbrains.php`.

Each variant is published as `<pluginVersion>.<phpApi>` (e.g. `2026.3.1.252` / `2026.3.1.262`). The Marketplace keys
uploads by version and rejects a second upload carrying a version it already has, so the two builds *must not* share
`pluginVersion`. The API goes in as a fourth component rather than a `-252` suffix: it sorts above the bare version
(existing installs still see an update) and is not a SemVer pre-release, so `channels` — which reads the bare
`pluginVersion` — still resolves to the default channel.

`publishPlugin` releases **every** variant listed in `phpApis`: since one Gradle build resolves exactly one platform,
it re-enters Gradle (`Exec` on the wrapper) once per remaining API, passing `-PphpApiSingle=true` so the nested run does
not fan out again. `./gradlew publishPlugin` is therefore the whole release; `release.yml` just calls it. CI builds and
verifies both variants via a matrix, and the Marketplace serves each IDE the build matching its since/until range.

> Note: `gradleVersion` in `gradle.properties` (9.5.0) lags the wrapper (9.6.0) — the property only feeds the
> `wrapper` task, so running `./gradlew wrapper` would downgrade it. Bump the property when syncing.

## Build & Run Commands

```bash
./gradlew buildPlugin      # build the distributable ZIP
./gradlew check            # tests + Kover XML coverage report
./gradlew test             # tests only
./gradlew runIde           # sandbox IDE with the plugin (autoReload disabled)
./gradlew verifyPlugin     # plugin structure + compatibility (recommended IDEs)
./gradlew runIdeForUiTests # sandbox IDE with robot-server on port 8082
```

Ready-made IDE run configurations live in `.run/`: *Run Plugin*, *Run Tests*, *Run Verifications*.

## Project Structure

```
src/main/kotlin/com/github/xepozz/testo/
├── TestoBundle.kt                  # i18n message bundle (messages/TestoBundle.properties)
├── TestoClasses.kt                 # FQN constants for Testo PHP classes/attributes + group arrays
├── TestoContext.kt                 # live template context ("Testo", inside a Testo class body)
├── TestoIcons.kt                   # icons, incl. LayeredIcon variants for file/class/function
├── TestoUtil.kt                    # isEnabled(project): a Testo framework configuration exists
├── TestoComposerConfig.kt          # auto-configures the framework from composer (testo/testo → bin/testo)
├── mixin.kt                        # PSI extensions: isTestoMethod/Class/File/Bench/Function/…
├── SpellcheckingDictionaryProvider.kt  # testo.dic
│
├── util/
│   ├── PsiUtil.kt                  # MEANINGFUL_ATTRIBUTES, ATTRIBUTE_GROUPS, attribute/yield ordering
│   └── ExitStatementsVisitor.kt    # indexes yield/return statements inside a data provider
│
├── actions/                        # Generate menu
│   ├── TestoGenerateTestMethodAction.kt
│   └── TestoGenerateMethodActionBase.kt
│
├── coverage/                       # optional, enabled via META-INF/coverage.xml
│   ├── TestoCoverageEngine.kt      # PhpUnitCoverageEngine subclass + suite/enabled-configuration
│   └── TestoCoverageProgramRunner.kt  # --coverage-clover=<IDE-managed path>, Xdebug/PCOV toggling
│
├── index/
│   ├── TestoDataProvidersIndex.kt  # FileBasedIndex: provider name → {class, method, providerFqn}
│   └── TestoDataProviderUtils.kt   # isDataProvider / findDataProviderUsages / usage index
│
├── references/
│   └── TestFunctionImplicitUsageProvider.kt  # tests/classes are never "unused"
│
├── tests/
│   ├── TestoFrameworkType.kt       # PhpTestFrameworkType (ID "Testo", SCHEMA "php_qn")
│   ├── TestoTestDescriptor.kt      # test class naming (*Test / *TestBase), findTests
│   ├── TestoTestCreateInfo.kt      # "Create New Test" info (template "Testo Test")
│   ├── TestoTestLocator.kt         # locationHint → PSI (file / class / method / function)
│   ├── TestoTestRunLineMarkerProvider.kt      # gutter icons + canonical locationHint builders
│   ├── TestoTestRunLineMarkerProviderInfo.kt  # Info.shouldReplace = true (wins over PhpStorm's)
│   ├── TestoStackTraceParser.kt    # failed line/text extraction from a PHP backtrace
│   ├── TestoConsoleProperties.kt   # console wiring: converter, locator, id-based tree, toolbar
│   ├── TestoVersionDetector.kt     # `--version --no-ansi` → "Testo <version>"
│   │
│   ├── actions/
│   │   ├── TestoNewTestFromClassAction.kt   # PHP | New | Testo Test
│   │   ├── TestoRerunFailedTestsAction.kt   # failed leaves → explicit --filter list
│   │   ├── TestoRerunWithExecutorAction.kt  # rerun in Run/Debug/Coverage + split button
│   │   ├── TestoRerunStyle.kt               # MIRROR_AWARE vs SPLIT_BUTTON toolbar styles
│   │   └── TestoRunCommandAction.kt         # "Run Testo <command>" (Run Anything)
│   │
│   ├── console/                    # the channel console subsystem (largest area)
│   │   ├── TestoOutputToGeneralEventsConverter.kt  # reads channel/level/icon/color off SM messages
│   │   ├── TestoProtocolGate.kt    # nodeId-less messages ⇒ pre-0.10.39 Testo; banner → version
│   │   ├── ChannelOutputStore.kt   # per-test live buffers: all / output / per-channel
│   │   ├── ChannelIcons.kt         # channel name or icon= hint → platform icon
│   │   ├── LogLevelFilter.kt       # persisted display-time log-level filter
│   │   ├── TestoLogLevelFilterAction.kt     # toolbar dropdown for the filter
│   │   ├── TestoChannelsUi.kt      # the tabbed channel view (~1150 lines) + testoDisplayName()
│   │   ├── TestoConsoleAugmenter.kt         # ExecutionListener that installs the channel tabs
│   │   ├── TestoChannelHistory.kt  # channel output ⇄ SMTestProxy.metainfo (survives history export)
│   │   ├── TestoHistoryImport.kt   # "Show history": import a saved run onto our own console properties
│   │   ├── TestoHistoryIndex.kt    # which locationUrls exist in saved history XMLs (+ lens refresh)
│   │   ├── TestoTestStatus.kt      # the 8 cases of Testo\Core\Value\Status: wire name, icon, label
│   │   ├── TestoStatusStore.kt     # per-test status/assertions + the tally the toolbar summary renders
│   │   ├── TestoRunTimings.kt      # start/first test/last test/finish marks + summed test durations
│   │   ├── TestoRunTarget.kt       # a node's own rerun recipe: hint → --filter selector, testSuite/testType
│   │   ├── TestoTargetStore.kt     # rerun targets of the current run, keyed by node id
│   │   ├── TestoNodeIndex.kt       # SMTestProxy → nodeId, off the platform's own node events
│   │   ├── TestoProgressAction.kt  # right-aligned toolbar summary: ring, fraction, status counters, elapsed
│   │   ├── TestoReportStore.kt     # reports announced by `##teamcity[testoReport …]` + where to look for them
│   │   ├── TestoReportAction.kt    # right-aligned panel of hand-drawn report buttons (WebView / browser / copy)
│   │   ├── TestoTestTreeDecorator.kt        # wraps the tree's cell renderer: status icons + description tooltips
│   │   ├── TestoRepeatedFrameFolding.kt     # folds repeated `#N frame` lines
│   │   └── PhpBacktraceFileFilter.kt        # file(line) / file:line / "on line N" → hyperlinks
│   │
│   ├── inspections/
│   │   ├── TestoInspectionSuppressor.kt     # silences PhpUnhandledExceptionInspection for AssertionException
│   │   └── TestoGroupNameInspection.kt      # warns on unusable #[Group] names (blank, !-prefixed, comma, none)
│   │
│   ├── overrides/
│   │   └── PhpRunInheritorsListCellRenderer.kt   # chooser popup renderer
│   │
│   ├── run/
│   │   ├── TestoRunConfigurationType.kt     # id pinned to "TestoRunConfiguration"
│   │   ├── TestoRunConfigurationFactory.kt
│   │   ├── TestoRunConfiguration.kt         # builds the command line, console, rerun action
│   │   ├── TestoRunConfigurationHandler.kt  # maps scope/settings → CLI flags
│   │   ├── TestoRunConfigurationSettings.kt # persistence; default options "-q -n --teamcity"
│   │   ├── TestoRunnerSettings.kt           # Testo-specific persisted fields + transient rerunFilters
│   │   ├── TestoRunConfigurationProducer.kt # context → configuration (~615 lines, the trickiest file)
│   │   ├── TestoTestRunConfigurationEditor.kt  # "Testo Options" panel wrapping the PHP editor
│   │   ├── TestoTestRunnerSettingsValidator.kt # + the finder that switches the "Cannot find …" gate off
│   │   └── TestoDebugRunner.kt              # debug session + channel tabs + rerun buttons
│   │
│   └── runAnything/
│       └── TestoRunAnythingProvider.kt      # "testo <command>" in Run Anything
│
└── ui/
    ├── TestoIconProvider.kt                 # Testo-marked icons for PHP test files
    ├── TestoHistoryCodeVisionProvider.kt    # "Show history" lens above each test
    ├── TestoReportEditor.kt                 # JCEF editor tab for a generated report (light file + provider)
    └── TestoStackTraceConsoleFolding.kt     # folds `[internal function]` frame runs

src/main/resources/
├── META-INF/plugin.xml         # main descriptor
├── META-INF/coverage.xml       # optional descriptor, loaded with com.intellij.modules.coverage
├── META-INF/pluginIcon*.svg
├── fileTemplates/internal/     # "Testo Test.php.ft" (+ .html description)
├── fileTemplates/code/         # "Testo Test Method" template used by TestoTestCreateInfo
├── icons/testo, icons/php      # SVG with _dark variants
├── icons/status                # one per Testo status (5 shapes, 3 reused in a second colour) + success/failure
├── liveTemplates/Testo.xml     # `test`, `data`, `bench`
├── messages/TestoBundle.properties
└── testo.dic                   # spellchecker dictionary

src/test/kotlin/…               # ~30 JUnit 4 test classes (see "Testing")
src/test/testData/mixin, rename # PHP fixtures for PSI-backed tests
```

## Architecture

### Extension points registered in `plugin.xml`

`com.intellij` namespace: `fileType` (maps the `testo`/`testo.php`/`testo.bat` binaries onto PHP),
`runLineMarkerContributor` (order="first"), `configurationType`, `runConfigurationProducer`,
`runAnything.executionProvider`, `programRunner` (debug), `implicitUsageProvider`, `iconProvider`,
`codeInsight.daemonBoundCodeVisionProvider`, `notificationGroup` (id `Testo`), `internalFileTemplate`,
`defaultLiveTemplates` + `liveTemplateContext`, two `console.folding`s, `fileBasedIndex`,
`spellchecker.bundledDictionaryProvider`, `lang.inspectionSuppressor`, `localInspection` (`TestoGroupNameInspection`).

`com.jetbrains.php` namespace: `testFrameworkType` (`TestoFrameworkType`), `composerConfigClient`
(`TestoComposerConfig`).

`META-INF/coverage.xml` (optional, `com.intellij.modules.coverage`) adds `coverageEngine` + the coverage
`programRunner`.

`projectListeners`: `TestoConsoleAugmenter` on `ExecutionListener` — the only hook where the PHP-built test console
can be reached to install the channel tabs.

Actions: the Generate-menu entry, the rerun trio + split button on `RunTab.TopToolbar`, an `overrides="true"`
replacement for the platform `Rerun`, and a `Tools | Testo` menu (channel-icon preview + rerun-style toggles).

### Dependencies

`com.intellij.modules.platform`, `com.jetbrains.php` (hard), `com.intellij.modules.coverage` (optional).
Requires IDEA Ultimate or PhpStorm — the plugin cannot load without PHP support.

### The Testo CLI contract

`TestoRunConfigurationHandler` + `TestoRunConfiguration.createCommand` produce:

```
<executablePath> <command> [testRunnerOptions] [runner flags] [--config <file>] [scope flags]
```

- `command` — the subcommand, default `run` (editable in the editor's combo box).
- `testRunnerOptions` default to **`-q -n --teamcity`** (`TestoRunConfigurationSettings.createDefault`).
  The `--teamcity` flag is what makes Testo emit the SM service messages this plugin parses.
- Runner flags from `TestoRunnerSettings` (only emitted when non-empty / > 0): `--type`, `--suite`, `--group`,
  `--exclude-group`, `--repeat`, `--parallel`, plus one `--filter <selector>` per entry in `rerunFilters`.
  `group`/`excludeGroup` are single persisted strings holding comma-separated names; the handler splits them into one
  flag per name (Testo ORs repeated `--group`s, and a `!name` prefix excludes). `groups`/`excludeGroups` are
  persisted **lists** (`@XCollection`), so a name is opaque — whatever `#[Group]` spells reaches the CLI untouched.
- `--config <file>` when an alternative configuration file is set (`getConfigFileOption()`).
- Scope flags: `Type` → `--suite <type>`; `Directory`/`File` → `--path <relative path>`;
  `Method` → `--path <file> --filter <method> [--data-provider <name>]`; `ConfigurationFile` → nothing
  (the config file argument alone drives the run).
- Coverage adds `--coverage-clover=<IDE-managed path>` (or bare `--coverage` if no path), plus Xdebug or PCOV
  INI options depending on `coverageEngine`.
- Working directory is always `project.basePath`.

`methodName` is an encoded selector, not just a name:

| Form                        | Meaning                                                        |
|-----------------------------|----------------------------------------------------------------|
| `foo`                       | plain test method/function                                     |
| `foo:2`                     | attribute #2 within its group (data / inline / bench)          |
| `foo:1:3`                   | data-provider #1, dataset (yield/return) #3                    |
| `foo#provider`              | `parseMethodName` splits this into `--filter foo --data-provider provider` |

### Location hints (`php_qn://` URLs)

`TestoTestRunLineMarkerProvider.Companion` owns the canonical format; `TestoTestLocator` parses it back.
Everything that needs to identify a test (line markers, code vision, history index, rerun filters, channel
storage keys) goes through these:

```
php_qn://<file>                                    # a Testo config file
php_qn://<file>::\Ns\ClassName                     # class
php_qn://<file>::\Ns\ClassName::method             # method
php_qn://<file>::\Ns\functionName                  # standalone test function
… + "#<index>"                                     # inline test / numbered attribute / dataset yield
… + " with data set #N"                            # emitted by Testo for dataset nodes
```

Paths are deployment-aware (`getFilePathDeploymentAware`) so remote interpreters map correctly.
`TestoRerunFailedTestsAction.locationUrlToFilter` reduces such a URL back to `\Fqn::method`.

### Testo attributes (`TestoClasses.kt`)

| Group (array)            | Attributes (FQN)                                                                                       |
|--------------------------|--------------------------------------------------------------------------------------------------------|
| `TEST_ATTRIBUTES`        | `\Testo\Test`, `\Testo\Inline\TestInline`                                                              |
| `TEST_INLINE_ATTRIBUTES` | `\Testo\Inline\TestInline`                                                                             |
| `DATA_ATTRIBUTES`        | `\Testo\Data\DataProvider`, `DataSet`, `DataUnion`, `DataCross`, `DataZip`                              |
| `BENCH_ATTRIBUTES`       | `\Testo\Bench`                                                                                         |
| `TEST_CASE_ATTRIBUTES`   | `\Testo\Bridge\Rector\Testing\TestRectorFixtures`                                                       |

`TEST_CASE_ATTRIBUTES` are **class-level** attributes that make the class a case without making its methods tests — the
framework synthesizes the tests (a Rector rule's fixtures become data sets of one probe test). Never put such an
attribute in `TEST_ATTRIBUTES`: that array drives `isPublicMethodOfTestoMarkedClass`, which would turn `refactor()` and
friends into tests.

`\Testo\Filter\Group` (`TestoClasses.FILTER_GROUP`) is deliberately in no array: it is not a test attribute, it selects
tests. It has its own branch in the line-marker provider and in the producer, and running it emits `--group=<name>`
only — no path, no name filter, no `--type`.

Other constants: `ASSERT`, `EXPECT`, `ASSERTION_EXCEPTION`, and the config classes
`\Testo\Application\Config\ApplicationConfig` / `SuiteConfig` (used to make `testo.php` runnable and to pick up
suite names from `new SuiteConfig('name')`).

The group arrays are spread into `RUNNABLE_ATTRIBUTES` (line markers) and `PsiUtil.MEANINGFUL_ATTRIBUTES` —
adding an attribute to a group array propagates it everywhere.

### Attribute group numbering

Attributes are numbered **within their own group** (independent 0-based indexes), per `PsiUtil.ATTRIBUTE_GROUPS`:
`DATA_ATTRIBUTES`, `TEST_INLINE_ATTRIBUTES`, `BENCH_ATTRIBUTES`. `#[Test]` belongs to no group, so
`getAttributeOrder` returns `-1` — the producer treats that as "run the whole test, no `:index` suffix". The same is
true of `#[TestRectorFixtures]`: the whole case runs.

```
#[Test]                  → runnable, no index          (--type=test)
#[DataProvider(...)]     → foo:0                       (--type=test)
#[DataSet([...])]        → foo:1                       (--type=test)
#[DataZip(...)]          → foo:2                       (--type=test)
#[DataCross(...)]        → foo:3                       (--type=test)
#[TestInline(...)]       → foo:0, foo:1, …             (--type=inline)
#[Bench(...)]            → foo:0, foo:1, …             (--type=bench)
#[TestRectorFixtures]    → runnable on the class       (--type=rector-fixture, run through the file)
#[Group('db')]           → not a test; runs the group  (--group=db, no other filter)
```

**Where the run starts decides the type.** A class-level attribute runs its class narrowed to the attribute's own type
(`#[Test]` → `--type=test`, `#[TestRectorFixtures]` → `--type=rector-fixture`); running the class itself is untyped, so
it keeps everything the class holds (a `#[Test]` class typed as `test` would drop its `#[Bench]` methods). This is why
`findTestElement` accepts an attribute whose owner is a Testo class instead of letting it fall back to the class.

`--type` values are the constants in `TestoRunConfigurationProducer.Companion`: `test`, `inline`, `bench`,
`rector-fixture` (the last one mirrors `RectorFixtureInterceptor::TYPE` in the Testo bridge).

### Test detection (`mixin.kt`)

- **Method** — has any `TEST_ATTRIBUTES`, OR is public and starts with `test`, OR is a public non-abstract,
  non-magic method of a class that itself carries a test attribute.
- **Function** — has any `TEST_ATTRIBUTES` (standalone test functions are first-class in Testo).
- **Bench** — method with any `BENCH_ATTRIBUTES`.
- **Data-provider-like** — public static method, or any standalone function; whether it *is* a provider is
  answered by `TestoDataProvidersIndex` (`TestoDataProviderUtils.isDataProvider`).
- **Class** — name ends with `Test`/`TestBase`, OR has a test attribute, OR is a case class (`isTestoCaseClass()`:
  carries a `TEST_CASE_ATTRIBUTES` attribute), OR owns test/bench methods.
- **File** — filename looks like a test class, else (smart mode only) it contains a Testo class, a test function,
  a bench, or a `new ApplicationConfig(...)`. Guarded against excluded/ignored/out-of-content files and wraps PSI
  work in try/catch (rethrowing `ProcessCanceledException`).

### Key subsystems

1. **Run / debug / coverage** (`tests/run/`, `coverage/`). The producer turns any of these into a configuration:
   a `PhpAttribute`, a `PhpYield` inside a provider, a `Function`, a `PhpClass` (via its file), a `PhpFile`,
   a directory, `new ApplicationConfig(...)` and `new SuiteConfig('x')`. It shows a chooser popup for abstract
   test classes (subclasses) and for a provider used by several tests. `shouldReplace` returns `false` so it
   never fights other PHP producers.

2. **Channel console** (`tests/console/`). Testo tags `testStdOut`/`testStdErr` messages with `channel`, `level`,
   `icon` and `color`; the converter records them into `ChannelOutputStore`, and `TestoChannelsUi` renders a tabbed
   view (All + one tab per channel) in place of the platform console, with syntax highlighting, hyperlinks,
   copy buttons, log-level filtering and per-channel icons/colors.

3. **Toolbar run summary** (`TestoProgressAction`) — a progress ring, the finished/total count, a counter per Testo
   status and the elapsed time, pushed right by `RightAlignedToolbarAction`. Statuses and assertion counts come off
   the service messages into `TestoStatusStore`; each counter narrows the tree through
   `SMTestRunnerResultsForm.setFilter`, and `TestoTestTreeDecorator` draws the same statuses on the nodes.
   `TestoRunTimings` splits the run into startup / tests / post-processing for the hover and sums the `duration`
   attributes beside them, which concurrency pushes past the window the tests ran in.

4. **Run history** — three cooperating pieces: `TestoChannelHistory` round-trips channel output through
   `SMTestProxy.metainfo` (the only per-test datum the platform's history XML preserves), `TestoHistoryIndex` knows
   which tests appear in saved history files, and `TestoHistoryCodeVisionProvider` shows a clickable
   *Show history* lens that imports the newest run containing that specific test and selects its node.

5. **Rerun toolbar** — two user-selectable styles (`Tools | Testo`): `MIRROR_AWARE` (three executor-pinned buttons
   that hide whichever duplicates the platform Rerun) and `SPLIT_BUTTON` (default; one split button, platform Rerun
   steps aside). `TestoRerunFailedTestsAction` rebuilds a failed-only run as an explicit list of `--filter`s.

6. **Line markers** (`TestoTestRunLineMarkerProvider`) — gutter run icons on test methods/functions/classes,
   runnable attributes, config files, and each `yield`/`return` inside a data provider.

7. **Data provider index** (`index/`) — file-based index keyed by provider name, resolving both
   `#[DataProvider('name')]` and `#[DataProvider([Class::class, 'name'])]` (incl. `self::`/`static::`).
   Scoped to the project *test* scope on lookup.

8. **Code generation** — file template + `TestoTestCreateInfo` for *Create New Test*, `Generate | Test Method`,
   and live templates `test` / `data` / `bench`.

9. **Navigation & output cleanup** — `TestoTestLocator` (click a node → source), `TestoStackTraceParser`
   (failed line + text), two console foldings, and `PhpBacktraceFileFilter` for hyperlinks in raw output.

10. **Generated reports** — Testo announces each report with the non-standard `##teamcity[testoReport …]`;
    `TestoReportStore` keeps them, and `TestoReportsAction` draws one button per viewable report past the run summary,
    labelled with the announced name, opening it in a JCEF tab (`ui/TestoReportEditor.kt`) or the external browser. Its
    four states are: not announced (no button), announced without a file (disabled), file present (enabled), file gone
    (disabled again). The spec for the report itself lives in the Testo repository (`docs/spec/html-report.md`).

## Implementation notes & gotchas

Non-obvious constraints already paid for in blood — read before touching the relevant area.

- **The test tree is id-based.** `TestoConsoleProperties.isIdBasedTestTree() = true`, so the platform uses
  `GeneralIdBasedToSMTRunnerEventsConvertor` and the tree comes from Testo's `nodeId`/`parentNodeId`. Testo runs
  tests concurrently (fibers/event loop), so message *order* cannot be trusted — the name-based convertor nested a
  second `#[DataSet]` batch inside the first and never closed nodes.
- **That protocol starts at Testo 0.10.39** (`Teamcity\Formatter::placement()`). Older builds send the same messages
  without ids, and the id-based convertor logs an error per message — a run became hundreds of IDE internal errors.
  `TestoProtocolGate` detects it off the missing `nodeId` (not off a version comparison, so forks and nightlies are
  covered), and the converter then stops forwarding messages and notifies once. The version in that notification is
  scraped from Testo's banner line, which survives `-q`.
- **A node is identified by `nodeId`, never by name or hint.** A data set's name is its coordinates alone
  (`Dataset #0:0 [0]`), so every list-shaped provider opens one; a hint names *code*, and `TestIdentity` keeps the
  type beside the fqn, so one method announced under two types shares it. `TestoStatusStore` and `TestoTargetStore`
  key by the id.
- **`TestoNodeIndex` makes an id-keyed store readable from the tree.** `SMTestProxy` does not carry the id, but
  `SMTRunnerEventsListener.onTestStarted(proxy, nodeId, parentNodeId)` hands both out together. Hooked from
  `TestoOutputToGeneralEventsConverter.setProcessor`, before any output is read. Writes never consult it.
- **Channel storage keys still go through `ChannelOutputStore.keyFor(name)`** and so inherit the name collision.
  They cannot move to node ids: an imported history run has none, and `TestoChannelHistory` rebuilds its tabs from
  the saved XML.
- **`TestoChannelsUi` reaches `TestResultsPanel.myConsole` by reflection** — there is no public accessor. It
  degrades gracefully (logs a warning, no channel tabs) if the field disappears.
- **The tree has one filter slot, shared with *Show passed* / *Show ignored*.** `TestoProgressAction.applyFilter` is
  its single writer: a selected counter replaces the toggles rather than narrowing them (intersecting would answer
  "show me the passed ones" with an empty tree), and releasing it recomposes them via `hiddenByToggles` — off Testo's
  statuses, not `isPassed`/`isIgnored`, where flaky and risky look like a plain pass. A listener on both
  `BooleanProperty`s re-asserts this after the platform's own, which would otherwise drop a live counter.
- **The results tree is re-skinned from outside, not subclassed.** `SMTRunnerTestTreeViewProvider` and
  `TestTreeRenderer` are both `@ApiStatus.Internal` and fail the verifier's default `failureLevel`, so
  `TestoTestTreeDecorator` wraps the renderer the console already installed (`JTree.getCellRenderer` /
  `setCellRenderer`). Safe: `attachToModel` is the only installer and runs at form construction, and nothing in the
  test-framework packages reads the renderer back. The proxy comes off `NodeDescriptor.getElement()` for the same
  reason — same object, public class.
- **`TestoHistoryIndex.refreshLens` uses the internal `ModificationStampUtil`** to force code-vision recomputation
  after a run; a test run never touches PHP source, so neither `DaemonCodeAnalyzer.restart()` nor
  `invalidateProvider` alone re-runs `getHint`. Wrapped in `runCatching`.
- **Imported history needs our own console properties.** `ImportedTestConsoleProperties` does not delegate
  `createImportActions`, so `TestoHistoryImport` reconstructs the import on `TestoImportedConsoleProperties`
  to keep the log-level filter button. Import wiring polls for a stable node count instead of subscribing —
  a small import can finish replaying before the augmenter hands us the console.
- **The log-level filter is added via `createImportActions`, not `appendAdditionalActions`** — the latter is routed
  into the gear submenu and would not survive the RunTab toolbar snapshot.
- **`ConsoleFolding` instances are shared across consoles** and get no per-console reset; both foldings track
  state in a `ThreadLocal` and clear it on the first non-frame line.
- **Debug installs channel tabs itself** (`TestoDebugRunner`): the augmenter's descriptor lookup misses debug
  sessions. `TestoConsoleProperties.channelsInstalled` guards against a double install. The debug session also
  gets the `Testo.RerunSplit` action handed to it explicitly, since it does not use `RunTab.TopToolbar`.
- **Group names are a list in the model, a comma-separated string only in the editor.** `TestoRunnerSettings`
  persists `groups`/`excludeGroups` via `@XCollection`; the comma lives in the editor's text field (`parseNames`/
  `formatNames`) and in the pre-list persisted form. `migrateLegacyNames` folds an old `group="a,b"` attribute into
  the list and clears it, and `TestoRunConfigurationSettings.getTestoRunnerSettings` calls it — that is the first
  point after deserialization every reader goes through. `TestoRunnerSettingsSerializationTest` pins the XML shape.
- **`rerunFilters` is `@Transient`** — it lives only on the throwaway clone a "rerun failed" launch creates, and
  that clone's scope is reset to `ConfigurationFile` so no scope flag narrows the filters away.
- **`TestoRunConfigurationType.ID` is a pinned literal**, not `::class.simpleName`: renaming the class must not
  invalidate users' saved run configurations.
- **`TestoDataProvidersIndex.getVersion()`** must be bumped whenever indexing logic or the attribute FQN changes,
  or stale on-disk indexes silently stay empty.
- **The run-configuration editor calls the parent editor's `resetEditorFrom`/`applyEditorTo` reflectively**
  (they are not public on `PhpTestRunConfigurationEditor`) and swallows `ReadOnlyModificationException`.
- **`TestoFrameworkType.getComposerPackageNames()` currently returns `arrayOf("php")`**, not `testo/testo` —
  deliberate (the commented-out line records the intent); changing it affects framework auto-detection.
- **`TestoTestRunLineMarkerProviderInfo.shouldReplace = true`** so Testo's gutter icon wins over PhpStorm's
  PHPUnit contributor for the same element.
- **The Method field is a `--filter` selector, not a member name, so its validation gate is off.** Every shape Testo
  accepts (`med:1:0`, `\Ns\Case::med:1:0`, `\Ns\Case`, `\Ns\freeFunction`) is one PHP declares nothing under, so the
  platform's "Cannot find 'X' in 'Y.php'" fired on correct runs. `AnyMethodIsValid` disables that one check; the
  file-type and non-empty checks stay, and an empty selection is Testo's own `buildProblem`.
- **`TestoRunConfiguration.checkConfiguration` swallows one exact platform error.** A group-only run (scope
  `ConfigurationFile`, no config file, non-empty `group`) trips the platform's "Configuration file is not
  specified" `RuntimeConfigurationError`, though Testo needs no config file. The error is matched by message
  text (`PhpBundle`), so a platform rewording fails closed — the validation error merely comes back.
- **The report buttons are drawn by hand, inside one right-aligned action.** Every platform widget failed a requirement:
  a toolbar button shows the icon alone (text becomes a tooltip), `SplitButtonAction` paints its own component and drops
  the text, `ComboBoxAction` turns the first click into a dropdown, and an expanded `ActionGroup` loses
  `RightAlignedToolbarAction` — its children land among the buttons on the left. So `TestoReportsAction` owns a panel of
  cells, the way `TestoProgressAction` does; RunTab snapshots the toolbar's actions, so the panel must exist from the
  start and hide itself while it has no cells.
- **A report must be announced while the run is still going, not once it is written.** Everything after the root
  `testSuiteFinished` is past the point where the platform still feeds the converter: such a line reaches neither our
  branch nor the console, it simply vanishes. So Testo announces a report when it *starts* writing it, and each cell
  polls for the file twice a second — which is also how a deleted report turns its button off again.
- **JCEF is declared twice and still never trusted.** On 262 it is the bundled `com.intellij.modules.jcef` plugin
  (`<depends optional>` + `jcef.xml`), on 252 a module inside the monolith (v2 `<dependencies><module>`); compiling
  against it proves nothing about runtime visibility. `TestoReportViewer.isAvailable` therefore asks by **reflection**:
  a named reference to `JBCefApp` throws `NoClassDefFoundError` when the *enclosing* method's class is verified, before
  any `try` around the call can catch it — which is how one absent class took down the whole toolbar action group. No
  JCEF type may be mentioned outside a class that loads only after `isAvailable` answers true.
- **The report tab is our own `FileEditorProvider`, not the platform's `HTMLEditorProvider`** — that one is
  `@ApiStatus.Internal`. It accepts nothing but `TestoReportVirtualFile`, and `TestoReportViewer` keeps one such file
  per path so re-opening a report returns to its tab (the platform keys tabs by identity, not equality) and reloads it.
- **A report path is resolved with `pathMapper.getLocalPath`, never `getLocalFile`** — the file was written moments
  before the message arrived and the VFS need not know it yet. `relativePath` under the project root is the last
  candidate, and the only one that works when the run's filesystem shares nothing with the host's.

## Testing

JUnit 4, two flavours — prefer the first when the logic allows it:

- **Plain unit tests** (no IDE fixture): pure string/logic helpers — `testoDisplayName`, location-URL → filter,
  attribute ordering, display names, folding placeholders, bundle keys, runner settings, coverage arguments,
  channel icons, channel store.
- **`BasePlatformTestCase`** (7 classes: `MixinPsiTest`, `TestoLineMarkerPsiTest`,
  `TestoRunConfigurationProducerPsiTest`, `TestoTestLocatorTest`, `ExitStatementsVisitorTest`,
  `PhpBacktraceFileFilterTest`, `MyPluginTest`) — anything that needs PSI or the PHP plugin.
  Fixtures live in `src/test/testData/`.

When adding behaviour, pull the pure logic into a top-level function (as `testoDisplayName` was) so it can be
tested without the platform fixture.

## Constraints & Important Notes

- **Platform:** IntelliJ IDEA Ultimate or PhpStorm only (`com.jetbrains.php` is a hard dependency)
- **Min IDE version:** 2025.2 (build 252+), shipped as two artifacts — see "Two build variants (`phpApi`)"
- **Kotlin stdlib is NOT bundled** (`kotlin.stdlib.default.dependency = false`) — uses the IDE's own
- **Gradle Configuration Cache** and **Build Cache** are enabled
- **Code and comments language:** English. Comments should explain *why* (platform quirks, race conditions),
  matching the density already present in `tests/console/` and `tests/actions/`.
- **Plugin description** is extracted from `README.md` between `<!-- Plugin description -->` markers at build
  time — the build fails if the markers go missing
- **Release channel** is derived from the pre-release label in `pluginVersion` (e.g. `-alpha.3` → `alpha`)
- **Signing & publishing** need `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`, `PUBLISH_TOKEN`

## CI/CD

- **build.yml** (push to `main`, all PRs): `buildPlugin` → `check` (Kover XML → Codecov) → Qodana →
  `verifyPlugin` → release draft. Runs on `ubuntu-latest`, Java 21 (Zulu), free-disk-space step first.
- **release.yml** (on GitHub release): publish to JetBrains Marketplace, patch the changelog, open a PR back.
- **run-ui-tests.yml** (manual): UI tests on Ubuntu / Windows / macOS via robot-server.

## Conventions

- All source in Kotlin; package root `com.github.xepozz.testo`
- i18n strings in `messages/TestoBundle.properties`, accessed via `TestoBundle`
- Icons follow IntelliJ conventions: SVG with a `_dark` variant
- New extension points must be registered in `plugin.xml` (coverage-only ones in `coverage.xml`)
- Version follows SemVer; `pluginVersion` in `gradle.properties` is the single source of truth
- Notable user-visible changes go into `CHANGELOG.md` under `## [Unreleased]` (Keep a Changelog format) —
  the release workflow consumes that section

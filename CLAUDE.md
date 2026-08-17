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
`intellij.platform.testRunner`, `intellij.platform.ui.jcef` on 262, which split them out of the monolith).

### Two build variants (`phpApi`)

The plugin ships as two artifacts because the platform since/until ranges and a few bundled modules differ across
2025.2 and 2026.2 (jcef / smRunner / testRunner split out of the monolith on 262). So every platform-dependent property
in `gradle.properties` is declared twice with an API suffix and selected by `phpApi`:

```bash
./gradlew buildPlugin                # 262: platform 2026.2, since 262, no untilBuild
./gradlew buildPlugin -PphpApi=252   # 252: platform 2025.2, since 252, until 261.*
```

**The two artifacts no longer differ in source** — coverage runs on 100 % public platform API (`coverage/`, see
"Generated reports" / the `coverage/` tree), so the old `src/php252/kotlin` vs `src/php262/kotlin` typealias split is
gone. `phpApi` is now purely a build selector (platform version, since/until, per-platform modules). The enum
`PhpUnitCoverageEngine.CoverageEngine` (the Xdebug/PCOV driver) is the one PHP coverage symbol still used and did **not**
move — it is imported directly from `com.jetbrains.php`.

Source that is single but not version-agnostic is reached by reflection, never a direct symbol: `XDebuggerManager.newSessionBuilder` (`TestoDebugRunner`) exists only on 262, so a direct call compiles green locally on 262 and breaks the 252 build in CI. Guard any such 262-only platform symbol behind a reflective lookup with a 252 fallback, or compile both variants before calling it done.

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
│   ├── TestoCoverageProgramRunner.kt  # --coverage-* flags on the IDE-managed paths, Xdebug/PCOV toggling
│   ├── TestoCoverageRunner.kt      # loads a report into ProjectData + the per-test index
│   ├── TestoCoverageAnnotator.kt   # per-file/dir percentages behind the Coverage view's columns
│   ├── TestoCoverageViewExtension.kt  # the view's columns (Branches, Tests) and its extra toolbar
│   ├── TestoCoverageViewActions.kt    # those toolbar actions: highlight, gutters, run covering, badges
│   ├── TestoCoverageSelectOpenedFile.kt  # our own "select the opened file" (the platform's cannot work here)
│   ├── editor/                     # the editor side: stripes, the line popup, the covering-tests gutter
│   ├── format/                     # clover / cobertura / coverage-xml parsers → one model
│   └── perTest/                    # which test touched which line: index, keys, identity, launcher
│
├── index/
│   ├── TestoDataProvidersIndex.kt  # FileBasedIndex: provider name → {class, method, providerFqn}
│   ├── TestoGroupsIndex.kt         # FileBasedIndex: every name a #[Filter\Group] in the project spells
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
│   │   ├── LogLevelFilter.kt       # persisted minimum-log-level filter (a message shows at or above it)
│   │   ├── TestoLogLevelFilterAction.kt     # the `info +` combo box on the channel tabs row picking that minimum
│   │   ├── TestoChannelsUi.kt      # the tabbed channel view (~1150 lines) + testoDisplayName()
│   │   ├── TestoConsoleAugmenter.kt         # ExecutionListener that installs the channel tabs
│   │   ├── TestoReplaySelection.kt # selects a test's node once the replayed tree stops growing
│   │   ├── TestoHistoryIndex.kt    # which test locations the run archive holds (+ lens refresh)
│   │   ├── TestoTestStatus.kt      # the 8 cases of Testo\Core\Value\Status: wire name, icon, label
│   │   ├── TestoStatusStore.kt     # per-test status/assertions + the tally the toolbar summary renders
│   │   ├── TestoRunTimings.kt      # start/first test/last test/finish marks + summed test durations
│   │   ├── TestoRunTarget.kt       # a node's own rerun recipe: hint → --filter selector, testSuite/testType
│   │   ├── TestoTargetStore.kt     # rerun targets of the current run, keyed by node id
│   │   ├── TestoNodeIndex.kt       # SMTestProxy → nodeId, off the platform's own node events
│   │   ├── TestoProgressAction.kt  # right-aligned toolbar summary: ring, fraction, status counters, elapsed
│   │   ├── TestoReportStore.kt     # reports announced by `##teamcity[testoReport …]` + where to look for them
│   │   ├── TestoReportAutoOpen.kt  # when a report opens on its own: this-run arm / project / application scopes
│   │   ├── TestoReportAction.kt    # right-aligned panel of hand-drawn report buttons (WebView / browser / copy)
│   │   ├── TestoTreeToolbarActions.kt       # expand/collapse for the test tree and the Coverage view alike
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
│   │   ├── TestoTagsField.kt                # the Group / Exclude group fields: removable tags + an add popup
│   │   ├── TestoRunConfigurationProducer.kt # context → configuration (~615 lines, the trickiest file)
│   │   ├── TestoTestRunConfigurationEditor.kt  # "Testo Options" panel wrapping the PHP editor
│   │   ├── TestoTestRunnerSettingsValidator.kt # + the finder that switches the "Cannot find …" gate off
│   │   └── TestoDebugRunner.kt              # debug session + channel tabs + rerun buttons
│   │
│   └── runAnything/
│       └── TestoRunAnythingProvider.kt      # "testo <command>" in Run Anything
│
├── runs/                           # the run archive: every run replayable, reports kept beside it
│   ├── TestoRunStore.kt            # project service: archive root, listing, reading, retention
│   ├── TestoRunRecording.kt        # one live run being written (output.log framing, tests.txt, run.json)
│   ├── TestoRunManifest.kt         # run.json: executor, timings, per-status tally, captured reports
│   ├── TestoRunArchiver.kt         # finalizes a run: captures reports, writes the manifest, prunes
│   ├── TestoRunReplayProfile.kt    # replays an archive through the live console properties
│   ├── TestoRunArchive.kt          # a run as one zip: export, import (zip-slip guarded), export file name
│   ├── TestoReplayGroup.kt         # toolbar "Replay": keep-discard-lock + export/import/reveal, for this tab's run
│   ├── TestoRunHistoryGroup.kt     # the "Test History" toolbar button, replacing the platform's
│   └── TestoRunHistoryActions.kt   # run kind icons + summaries, the retention submenu, the lens's lookups
│
└── ui/
    ├── TestoIconProvider.kt                 # Testo-marked icons for PHP test files
    ├── TestoHistoryCodeVisionProvider.kt    # "Show history" lens above each test
    ├── TestoCodeVisionGroupSettings.kt      # the two lenses' name/description in Inlay Hints settings (else blank)
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

`META-INF/coverage.xml` (optional, `com.intellij.modules.coverage`) adds `coverageEngine`, `coverageRunner`, the
coverage `programRunner`, the annotator service and the *Run covering tests* `codeInsight.lineMarkerProvider`.

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

- `command` — the subcommand, always `run` for a test run (other subcommands live in Run Anything).
- `testRunnerOptions` default to **`-q -n --teamcity`** (`TestoRunConfigurationSettings.createDefault`).
  The `--teamcity` flag is what makes Testo emit the SM service messages this plugin parses.
- Runner flags from `TestoRunnerSettings` (only emitted when non-empty): `--type`, `--suite`, `--group`,
  plus one `--filter <selector>` per entry in `rerunFilters` (no `--parallel` — Testo's CLI does not take it yet).
  `suites`, `groups`/`excludeGroups` are persisted
  **lists** (`@XCollection`), one `--group` flag each — Testo ORs repeated `--group`s and reads a `!name` prefix as an
  exclusion, which is the only exclusion form its CLI has. A name is opaque: whatever `#[Group]` spells reaches the
  CLI untouched.
- `--log-html`/`--log-junit` at an IDE-managed path (`TestoReportFlags`, `logHtml` on / `logJunit` off by default),
  emitted from `createCommand` so every executor gets them — local interpreters only, and the archive copies the
  reports into history the same way it does coverage. Not in `prepareArguments`: that has no project/interpreter.
- `--config <file>` when an alternative configuration file is set (`getConfigFileOption()`).
- Scope flags: `Type` → `--suite <type>`; `Directory`/`File` → `--path <relative path>`;
  `Method` → `--path <file> --filter <method> [--data-provider <name>]`; `ConfigurationFile` → nothing
  (the config file argument alone drives the run).
- Coverage adds one `--coverage-<format>=<IDE-managed path>` per checked report (or bare `--coverage` if no path),
  `--coverage-level=<line|branch|path>` (`resolveCoverageLevel`: an explicit level, else *auto* → `branch` when the
  engine is Xdebug and a Cobertura report is on — branches need Xdebug and Cobertura carries them — else nothing),
  the configuration's own coverage-only options (`coverageOptions`, `--type=!bench` by default), plus Xdebug or PCOV
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

4. **Run history** (`runs/`) — every run is archived under the IDE system dir as its raw teamcity output
   (`output.log`), a manifest (`run.json`: executor, per-status tally, captured reports) and the report files
   themselves; `tests.txt` lists the test locations it holds. `TestoRunReplayProfile` feeds that stream back through
   the live console, so a replayed run has the whole Testo UI. `TestoHistoryIndex` answers which tests the archive
   holds, and `TestoHistoryCodeVisionProvider` shows a *Show history* lens that replays the newest run containing
   that specific test and selects its node. Retention is the plugin's own (`Tools | Testo`).

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
    `TestoReportStore` keeps them, `TestoReportsAction` draws one button per viewable report, opening it in a JCEF tab
    (`ui/TestoReportEditor.kt`) or the browser. A click before the report is delivered defers the open;
    `TestoReportAutoOpen` holds the auto-open choices (this run / project / application), keyed by format + name.
    The report spec lives in the Testo repository (`docs/spec/html-report.md`).

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
- **Channel storage keys still go through `ChannelOutputStore.keyFor(name)`** and so inherit the name collision:
  the channel UI is looked up by test name, which is all a tab has when the selection changes.
- **`TestoChannelsUi` reaches `TestResultsPanel.myConsole` by reflection** — there is no public accessor. It
  degrades gracefully (logs a warning, no channel tabs) if the field disappears.
- **The log-level filter is the channel tabs' `entryPointActionGroup`** (right edge of the tab row): a `protected open`
  val re-read by `updateEntryPointToolbar` on every tab change, so overriding it on the `JBEditorTabs` subclass suffices.
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
- **`TestoHistoryIndex` builds its location set synchronously in `contains`**, on the daemon's background thread, so
  the *first* code-vision pass over a freshly opened file already answers. An earlier async build left the first pass
  empty and leaned on a later repaint, but `refreshLens` does not reliably force a daemon-bound recompute in 2026.2 —
  so the *Show history* lens never appeared until the file was edited. The read is bounded (a few small `tests.txt`)
  and cached per archive generation.
- **Location hints from PSI and from Testo differ in path separators.** `getLocationHint` runs the file through the
  local `PhpCommandLinePathProcessor`, which yields the OS-native path (`D:\…` on Windows); Testo emits the same
  location with `/`. So any lookup of a PSI-built hint against an archived one (the history lens, *Show history*'s
  replay) must go through `runLocationKey`, which folds separators to `/` — otherwise it never matches on Windows.
- **`TestoHistoryIndex.refreshLens` uses the internal `ModificationStampUtil`** to force code-vision recomputation
  after a run — for editors already open when a run finishes; a test run never touches PHP source, so neither
  `DaemonCodeAnalyzer.restart()` nor `invalidateProvider` alone re-runs `getHint`. Wrapped in `runCatching`.
- **History is replayed, not imported.** The platform's import forces `ImportedTestConsoleProperties` and its own
  converter, so none of our stores fill — an imported tab is a PHPUnit-looking tree. `TestoRunReplayProfile` feeds
  the archived teamcity stream through the *live* properties instead. Three switches keep a replay from acting like
  a run: `replayMode`, `getConfiguration()` answering the replay profile, `reportStore.startedAtOverride`.
- **Whoever waits for a replayed tree polls for a stable node count** instead of subscribing to
  `SMTRunnerEventsListener`: a short run finishes replaying before the augmenter hands us the console, so the
  events are already fired and missed.
- **Whatever our own `createImportActions` returns must survive the RunTab toolbar snapshot** — `appendAdditionalActions`
  is routed into the gear submenu instead and would not.
- **`createImportActions` deliberately does not call `super`.** Super's entries (`ImportTestsGroup`,
  `ImportTestsFromFileAction`) open a saved XML through the platform import — a console with none of our UI; the
  history and *Replay* groups are our counterparts. The array is the only writable seam onto the visible toolbar row
  (actions land there without `PREFERRED_PLACE`) and is laid out right-to-left: listed first = furthest right, always
  after the platform's own actions. The platform's export is gone the same way — `ToolbarPanel` builds
  `ExportTestResultsAction` only for a real `RunConfiguration`, and `getConfiguration()` answers the replay profile.
- **What the platform put on the test toolbar cannot be moved or removed.** `ToolbarPanel` builds those actions
  inline (no ids, no extension point, no `CustomActionsSchema` entry) and copies its groups into the arrays `RunTab`
  rebuilds the toolbar from — mutating the live groups afterwards changes nothing the user sees.
- **Every executor but Run and Debug is hidden behind the "More Run/Debug" submenu** (`ExecutorRegistryImpl`; the
  `executor.actions.submenu` registry key is global). The actions are shared instances, so copying *Run with
  Coverage* into a popup only duplicates the submenu entry — tried in the test tree's popup and reverted.
- **The Coverage view's *Always select opened element* cannot work for a file-based view**: it matches the PSI *leaf*
  under the caret against nodes holding `PsiFile`/`PsiDirectory`, so nothing ever matches, and everything involved is
  `@ApiStatus.Internal`. Hence `TestoCoverageSelectOpenedFile`: our own toggle, following the editor off the message
  bus and selecting via `TreeUtil.promiseSelect` on the tree taken from the toolbar's target component.
- **The Coverage view's tree has no extensible context menu** — `CoverageView.createPopupGroup` is private, built
  inline, and holds `EditSource` alone. Anything acting on the selected row goes on the toolbar instead
  (`createExtraToolbarActions`, `@Experimental`) and reads the selection as `CommonDataKeys.NAVIGATABLE`.
- **A column's width comes from `getPercentage(column, rootNode)`** — a non-percentage column must still answer there
  (the *Tests* column returns its count) or the view sizes it for "100% (1234/1234)". The user's own width then sticks
  in `CoverageViewManager.StateBean.myColumnSize` whenever the column count matches.
- **`CoverageViewExtension` is instantiated three times per view** (`CoverageView`, `CoverageTableModel`,
  `CoverageViewTreeStructure`), so no instance sees another's fields — anything `getPercentage` needs must be derived
  from the bundle, not remembered from `createColumnInfos`.
- **The editor highlighter is installed from `applyTestoCoverage`, not from the annotator**: `onSuiteChosen` fires
  only on reload/close, never on the session's first `chooseSuitesBundle` — which left the first coverage run of an
  IDE session unpainted.
- **`ConsoleFolding` instances are shared across consoles** and get no per-console reset; both foldings track
  state in a `ThreadLocal` and clear it on the first non-frame line.
- **Debug installs channel tabs itself** (`TestoDebugRunner`): the augmenter's descriptor lookup misses debug
  sessions. `TestoConsoleProperties.channelsInstalled` guards against a double install.
- **The debug toolbar's restart button is the overridden platform `Rerun`** (`TestoAwareRerunAction`), and it must
  stay visible on debug tabs. The split button that normally replaces it in SPLIT_BUTTON mode lives on
  `RunTab.TopToolbar`, which the debug tab does not use — so the action's hide branch is gated on *not* the Debug
  executor. A descriptor's restart actions are constructor-only, so this cannot be fixed by handing the debug
  `RunContentDescriptor` its own.
- **Suite and group names are lists everywhere.** `TestoRunnerSettings` persists `suites`/`groups`/`excludeGroups`
  via `@XCollection`, the editor shows them as tags (`TestoTagsField`), and a name is never split on anything — the
  comma lives only in the legacy persisted form. `migrateLegacyNames` folds that form in, called from
  `TestoRunConfigurationSettings.getTestoRunnerSettings` — the first point every reader passes after deserialization.
  `TestoRunnerSettingsSerializationTest` pins the XML shape.
- **`rerunFilters` is `@Transient`** — it lives only on the throwaway clone a "rerun failed" launch creates, and
  that clone's scope is reset to `ConfigurationFile` so no scope flag narrows the filters away.
- **`TestoRunConfigurationType.ID` is a pinned literal**, not `::class.simpleName`: renaming the class must not
  invalidate users' saved run configurations.
- **`getVersion()` of both file-based indexes** (`TestoDataProvidersIndex`, `TestoGroupsIndex`) must be bumped whenever
  indexing logic changes — for the groups index that includes `TestoRunConfigurationProducer.extractGroupNames`, which
  it indexes through — or stale on-disk indexes silently stay empty.
- **The run-configuration editor calls the parent editor's `resetEditorFrom`/`applyEditorTo` reflectively**
  (they are not public on `PhpTestRunConfigurationEditor`) and swallows `ReadOnlyModificationException`.
- **Parallel is injected into the PHP editor's own form.** The *Test Runner options* row is a one-row
  `GridLayoutManager`, so `injectParallelRow` finds it by its label (the bundle string carries a `&` mnemonic the
  rendered label does not), rebuilds it with a second row and re-adds the children with their own constraints — that
  keeps the label column shared. Falls back to a row of our own below the panel if the PhpStorm form changes.
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
- **A report is announced when Testo *starts* writing it** — output after the root `testSuiteFinished` never reaches
  the converter — so the file is polled, no earlier than process exit and only accepting mtime no older than the run:
  the path is the same every run, and a stopped run leaves the previous report in place.
- **JCEF: only `<depends optional>` on `com.intellij.modules.jcef`.** The module form (`intellij.platform.ui.jcef` in
  `<dependencies>`) is mandatory and absent on 252 — that build would not load at all. `TestoReportViewer.isAvailable`
  asks by reflection: a named `JBCefApp` reference throws `NoClassDefFoundError` at class verification, before any
  `try`. No JCEF type outside classes that load after it answers true, and nothing from `org.cef` — it is not on the
  compile classpath.

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

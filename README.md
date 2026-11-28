<p align="center">
    <a href="#get-started"><img alt="TESTO"
         src="https://github.com/php-testo/.github/blob/1.x/resources/logo-full.svg?raw=true"
         style="width: 2in; display: block"
    /></a>
</p>

<p align="center">Testo PHP testing framework support for PhpStorm, IntelliJ IDEA Ultimate and OpenIDE</p>

<div align="center">

[![Documentation](https://img.shields.io/badge/Documentation-blue?style=for-the-badge&logo=gitbook&logoColor=white)](https://php-testo.github.io)
[![Sponsor](https://img.shields.io/static/v1?style=for-the-badge&label=&message=Sponsor&logo=githubsponsors&logoColor=white&color=%23EA4AAA)](https://github.com/xepozz/xepozz?tab=readme-ov-file#become-a-sponsor)

[![Build](https://github.com/j-plugins/testo-plugin/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/j-plugins/testo-plugin/actions/workflows/build.yml?query=branch%3Amain)
[![Codecov](https://codecov.io/gh/j-plugins/testo-plugin/branch/main/graph/badge.svg)](https://app.codecov.io/gh/j-plugins/testo-plugin/tree/main)

</div>

<br />

<!-- Plugin description -->

IDE integration for [Testo](https://php-testo.github.io) — the PHP testing framework you control.

- Run, debug and coverage configurations for tests, classes, files, directories, suites, groups and data sets
- Gutter run icons on tests, benchmarks, inline tests, data providers and each of their data sets
- Test console with a tab per output channel, log-level filter and per-status run summary
- Run history: every run is archived and can be replayed with the full test UI
- Coverage with branches and per-test data, plus mutation testing with Infection
- HTML and JUnit reports opened right in the IDE
- Test generation, live templates, inspections and navigation from results to code

Requires Testo 0.10.39 or newer.

[Telegram](https://t.me/jb_plugins) | [Donation](https://github.com/xepozz/xepozz?tab=readme-ov-file#become-a-sponsor)

<!-- Plugin description end -->

## Installation

### PhpStorm / IntelliJ IDEA Ultimate

[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/28842-testo.svg?label=JetBrains%20Marketplace)](https://plugins.jetbrains.com/plugin/28842-testo)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/28842-testo.svg)](https://plugins.jetbrains.com/plugin/28842-testo)
[![Rating](https://img.shields.io/jetbrains/plugin/r/rating/28842-testo.svg)](https://plugins.jetbrains.com/plugin/28842-testo/reviews)

<kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > search for <kbd>Testo</kbd> > <kbd>Install</kbd>,
or open the [JetBrains Marketplace page](https://plugins.jetbrains.com/plugin/28842-testo) and click
<kbd>Install to ...</kbd> while the IDE is running.

Supported: 2025.2 and newer, with the bundled PHP plugin.

### OpenIDE

[![OpenIDE Marketplace](https://img.shields.io/badge/OpenIDE-Marketplace-blue)](https://marketplace.openide.ru/plugin/testo/461/)

<kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > search for <kbd>Testo</kbd> > <kbd>Install</kbd>,
or download it from the [OpenIDE Marketplace](https://marketplace.openide.ru/plugin/testo/461/).

Supported: OpenIDE 2026.3 and newer, with PHP for OpenIDE 0.9.10 or newer.

### Manually

Download the ZIP for your IDE from the [latest release](../../releases/latest) and install it with
<kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install Plugin from Disk...</kbd>

## Mutation testing in OpenIDE

Infection runs on the interpreter selected for the original test run. On macOS, Linux, Docker/Compose and WSL that
interpreter needs the `pcntl` and `posix` extensions, so Testo can stop Infection and its child processes. PHP for
OpenIDE checks them before starting Infection; ordinary test runs do not need them.

If the connection to the interpreter is lost, Testo keeps the temporary reports and blocks rerun. Use **Stop** to retry
stopping the same process. After an IDE restart, check that interpreter's processes before starting a new run if the
previous one could not be confirmed as stopped.

## Building from source

```bash
./gradlew buildPlugin                   # PhpStorm 2026.2+
./gradlew buildPlugin -PphpApi=252      # PhpStorm 2025.2 – 2026.1
./gradlew buildPlugin -PphpApi=openide  # OpenIDE 2026.3+
```

The ZIP lands in `build/distributions/` (`build/openide/distributions/` for OpenIDE).

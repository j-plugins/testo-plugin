# IntelliJ Idea Plugin for Testo PHP Testing Framework

![Build](https://github.com/j-plugins/testo-plugin/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/28842-testo.svg)](https://plugins.jetbrains.com/plugin/28842-testo)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/28842-testo.svg)](https://plugins.jetbrains.com/plugin/28842-testo)

<!-- Plugin description -->

[Github](https://github.com/j-plugins/testo-plugin) | [Telegram](https://t.me/jb_plugins) | [Donation](https://github.com/xepozz/xepozz?tab=readme-ov-file#become-a-sponsor)

Testo PHP – is a modern PHP testing library

<!-- Plugin description end -->

## Installation

- Using the IDE built-in plugin system:
  
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "testo-plugin"</kbd> >
  <kbd>Install</kbd>
  
- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/28842-testo) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/28842-testo/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/j-plugins/testo-plugin/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>


## OpenIDE

Build the OpenIDE variant with `./gradlew buildPlugin -PphpApi=openide`. It uses PHP for OpenIDE 0.9.4 and
OpenIDE 2026.2.1. Install the ZIP from `build/openide/distributions/` through **Install Plugin from Disk**.
The PhpStorm builds remain selected by `-PphpApi=252` and `-PphpApi=262`.

Runs use the project's active PHP interpreter. Infection retains the interpreter selected for the original test run.
On macOS, Linux, Docker/Compose and WSL, that interpreter needs the `pcntl` and `posix` extensions so Testo can stop
Infection and its child processes. PHP for OpenIDE checks these requirements before starting Infection; ordinary
Testo test runs do not require those extensions.

If the connection to an interpreter is lost, Testo keeps the temporary reports and blocks rerun. Use **Stop** to
retry stopping the same process. After restarting the IDE, check the processes in that interpreter before starting
a new test run if the previous run could not be confirmed as stopped.

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template

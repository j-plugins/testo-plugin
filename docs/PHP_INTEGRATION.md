# PHP integration

The plugin keeps its shared logic in `src/main` and the PhpStorm implementation in `src/phpstorm`.
The latter is compiled into both existing builds, selected with `-PphpApi=252` or `-PphpApi=262`.

The shared code accesses PHP syntax, symbols and interpreter paths through `php/TestoPhp.kt`.
Run selections and command arguments are represented by the platform-neutral types in `launch/`.
Infection uses `TestoToolEnvironment` to prepare a process and manage its output, cancellation and resources.
Classes extending the PHP plugin's APIs, along with their registrations, live in the PhpStorm source set.

Shared tests belong in `src/test`; implementation-specific tests belong in `src/phpstormTest`.
`CoreIsolationTest` checks that shared sources do not depend on the PHP implementation.
The contract tests and snapshots cover run contexts, commands, navigation, saved configurations and reruns.

Run the checks for both supported platform variants:

```shell
./gradlew check buildPlugin verifyPlugin -PphpApi=252
./gradlew check buildPlugin verifyPlugin -PphpApi=262
```

## OpenIDE implementation

`-PphpApi=openide` selects `src/openide` and `src/openideTest` instead of the PhpStorm implementation.
It compiles against OpenIDE and PHP for OpenIDE, using Java 25. The shared contract and run-context tests execute
against both implementations, and the isolation checks also prevent dependencies between the two adapters.

```shell
./gradlew check buildPlugin verifyPlugin -PphpApi=openide
```

The OpenIDE build has its own output directory (`build/openide`) and ZIP name. It is excluded from the normal
`phpApis` publishing list and uses `OPENIDE_PUBLISH_TOKEN` when explicitly published to the OpenIDE plugin store.

The OpenIDE verification task supplies the resolved PHP plugin through a local dependency repository, so it checks
against the same artifact used for compilation. The dependency itself is resolved from the OpenIDE plugin store.

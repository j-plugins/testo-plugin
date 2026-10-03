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

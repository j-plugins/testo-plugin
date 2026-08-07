<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# testo-plugin Changelog

## [Unreleased]

### Added

- The test toolbar ends with a run summary: a progress ring that fills as tests finish and turns into a green check
  or a red cross when the process exits — grey when the run was stopped before it could reach a verdict of its own,
  the check or the cross still chosen by whatever its tests managed to report — the finished/total count, one counter
  per Testo status, and the elapsed time. Each counter names what it counts — `1234/2000 total`, `42 flaky`. Statuses come from the `status` attribute
  of Testo's service messages, so all eight cases of `Testo\Core\Value\Status` — including risky, flaky, cancelled
  and aborted — are told apart. A Testo too old to send that attribute still gets counters, from the three outcomes
  TeamCity has always carried: `testFailed`, `testIgnored` and a plain `testFinished`.
- Every counter in that summary filters the test tree: click one to see only tests with that status, click it again
  or click the total on the left to bring the whole tree back. Hovering the total shows how many assertions the run
  made, off the `assertions` attribute of `testFinished`.
- The clock shows the run from start to finish, and its hover breaks that down into what happened before the first
  test, the tests themselves, and what happened after the last one — so the cost of bootstrapping and of merging
  reports is visible separately. Beside them it sums the tests' own durations: with tests on fibers that sum runs
  past the window they fitted into, and the hover states by how much rather than pretending the difference is
  framework overhead.
- A problem Testo raises about the run itself rather than about a test — `##teamcity[buildProblem …]`, as an empty
  run reports — is now visible instead of parsed and dropped: as a red line in Output, as a run-level notice in All,
  and as a notification, since a run that executed nothing otherwise looks like a run that simply finished. Reported
  once per problem, keyed by the message's own `identity`.

- A node of the results tree can be announced with `testSuite` and `testType`, and rerunning it from the tree keeps
  them: `--suite` and `--type` are added to the command the way the node itself was narrowed. Optional, so a Testo
  that sends neither runs exactly as before.
- A test's `metainfo` — its PHPDoc summary — shows as the tooltip of its node in the results tree.

### Fixed

- Rerunning a data set from the results tree runs that data set again, not its whole file. The location hint Testo
  sends spells out the selector (`\Ns\Calculator::med:3:0`), but the PSI it resolves to cannot: a data set has no
  method of its own to find, so it landed on its class. The selector now goes to `--filter` verbatim, class and all,
  which also stops a bare method name from matching a namesake elsewhere in the same file.
- A Testo older than 0.10.39 no longer floods the IDE with internal errors. Such a build sends its service messages
  without the node ids the test tree is built from, and every one of them was answered with a logged error; the run
  now stops at the first such message and says what to update instead.

## [2026.3.1] - 2026-08-04

### Added

- Rector rules carrying `#[\Testo\Bridge\Rector\Testing\TestRectorFixtures]` are recognized as test cases: gutter run
  icon, test-file icon, no "unused" warnings, and a run from the attribute that narrows to `--type=rector-fixture`.
  The rule's own public methods stay ordinary methods — only its fixtures are tests.
- Running a class-level attribute now narrows the run to that attribute's type (`#[Test]` → `--type=test`), while
  running the class itself stays untyped and keeps everything the class holds.
- Gutter run icon on `#[\Testo\Filter\Group]`: runs every test of that group with `--group=<name>` and nothing else —
  no path or name filter is added. A variadic `#[Group('db', 'slow')]` emits one `--group` flag per name.
- The Group / Exclude group fields of the run configuration accept several comma-separated names. Names are stored
  as a list, so a group name is passed to the CLI verbatim; configurations saved in the previous format are migrated
  on load.
- Inspection: suspicious `#[Group]` names are highlighted — blank or whitespace-padded, `!`-prefixed (the CLI reads
  that as an exclusion), containing a comma, or no names at all.
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)

### Fixed

- Concurrent tests no longer nest inside one another in the test tree: the tree is built from the `nodeId`/`parentNodeId`
  Testo reports rather than from the order messages arrive in.

[Unreleased]: https://github.com/j-plugins/testo-plugin/compare/v2026.3.1...HEAD
[2026.3.1]: https://github.com/j-plugins/testo-plugin/commits/v2026.3.1

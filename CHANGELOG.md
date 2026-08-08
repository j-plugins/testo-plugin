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
- A selected counter owns the tree while it is selected, rather than narrowing the toolbar's standing *Show passed* /
  *Show ignored* toggles further — so asking for the passed tests answers with them even when passed ones are hidden.
  Releasing the counter puts the tree back under those toggles, including any change made to them meanwhile.
- *Show passed* and *Show ignored* now read Testo's statuses instead of the three the TeamCity protocol carries.
  Hiding the passed ones takes flaky along (the same check mark, only yellow) but leaves **risky** — which used to
  arrive as a plain pass and so could not be reached by any toggle at all. Hiding the ignored ones takes cancelled
  along with skipped. With both toggles off the tree holds failed, error, aborted and risky.
- The tree's root node carries the run's verdict — the same check or cross the summary's ring turns into, grey when
  the run was stopped before it could reach one. It is read from the summary rather than worked out again, so the
  two cannot come to disagree.
- The clock shows the run from start to finish, and its hover breaks that down into what happened before the first
  test, the tests themselves, and what happened after the last one — so the cost of bootstrapping and of merging
  reports is visible separately. Beside them it sums the tests' own durations, but only where that sum parts with
  the window they fitted into — level with it, it would be the same figure twice. With tests on fibers the sum runs
  past that window, and the difference is read as a concurrency boost on a line of its own rather than pretended to
  be framework overhead. Stated as a floor — `≥2.1x` — because the window it is measured against also holds the work
  between tests, which no test's own duration counts.
- A problem Testo raises about the run itself rather than about a test — `##teamcity[buildProblem …]`, as an empty
  run reports — is now visible instead of parsed and dropped: as a red line in Output, as a run-level notice in All,
  and as a notification, since a run that executed nothing otherwise looks like a run that simply finished. Reported
  once per problem, keyed by the message's own `identity`.

- A node of the results tree can be announced with `testSuite` and `testType`, and rerunning it from the tree keeps
  them: `--suite` and `--type` are added to the command the way the node itself was narrowed. Optional, so a Testo
  that sends neither runs exactly as before.
- The results tree carries one icon per Testo status, the same eight the toolbar summary counts — so a counter
  reading `42 flaky` now points at nodes that can be told apart on sight, instead of at tests the platform draws as
  plain passed ones. Group nodes — a case, a data-provider batch, a suite of the run — get one too, off the outcome
  Testo rolls up onto their `testSuiteFinished`. A test still running keeps its animation, and the root keeps the
  platform's icon: the toolbar summary already states the run's verdict.
- A test's `metainfo` — its PHPDoc summary — shows as the tooltip of its node in the results tree.

### Fixed

- Rerunning a data set from the results tree runs that data set again, not its whole file. The location hint Testo
  sends spells out the selector (`\Ns\Calculator::med:3:0`), but the PSI it resolves to cannot: a data set has no
  method of its own to find, so it landed on its class. The selector now goes to `--filter` verbatim, class and all,
  which also stops a bare method name from matching a namesake elsewhere in the same file.
- No more "Cannot find '…' in '….php'" stopping a run that Testo would have executed. That check assumed the Method
  field names a member the file declares, but it holds a `--filter` selector — `med:1:0`, `\Ns\Case::med:1:0`,
  `\Ns\Case`, `\Ns\freeFunction` — and PHP declares nothing under any of those spellings. The check is off; whether a
  filter matches anything is Testo's call, and it reports an empty selection itself. The file-type and non-empty
  checks on the field are unaffected.
- Rerunning a case from the results tree runs that case, not every case its file declares. The node's own hint names
  the class, and it now reaches `--filter` the same way a method selector does; before, only the file made it into the
  command, so a right-click on `TestLevelPipelineFailure` in `PipelineFailureSandbox.php` ran the file's other cases
  along with it.
- Jump to Source on a data set node opens the test it belongs to. The location hint names the data set by its
  position — `\Ns\Calculator::med:3:0` — and PHP declares no such member, so the platform missed the method and
  answered with the enclosing class instead, or with the file when the hint named a standalone test function. The
  coordinates are now taken off before the lookup, and a standalone function is looked for even in a file that also
  holds a class.
- The toolbar run summary follows the IDE's zoom. Its cells are painted by hand and took the label font once, when
  the toolbar was built, and nothing reinstalls a font on a component that has no UI delegate — so the counters kept
  the size the IDE had at the start of the run while everything around them grew.
- The spinner on a running test follows the IDE's zoom too. The platform's rasterizes its frames on first paint and
  caches them under the icon's colour alone, so a zoom never rebuilds them and a 16-pixel spinner is left among
  32-pixel icons for the rest of the session. Where the two sizes disagree the tree now draws a spinner of its own
  that is sized when it is painted; at the default zoom the platform's own, smoother one is kept.
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

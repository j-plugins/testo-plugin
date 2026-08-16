<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# testo-plugin Changelog

## [Unreleased]

### Added

- The run configuration chooses which coverage reports a Coverage run asks Testo for: Clover, Cobertura, coverage-xml.
- A Coverage run applies everything it produced on its own, one report per format, without a click.
- The Coverage button on the test toolbar gathers every announced report under one click, with a checkbox per report.
- The Coverage panel gained expand/collapse, a switch for the editor highlighting, badges naming the report formats
  behind the shown coverage (kept at the right end of the toolbar), and a narrow column counting the tests that cover
  each file.
- *Run Covering Tests* on the Coverage panel runs, with coverage, the tests that cover the selected row — a file's own
  tests, or every test under a directory.
- *Select Opened File* on the Coverage panel selects the file open in the editor, which the platform's *Always select
  opened element* never managed to do in a file-based coverage view.
- A gutter icon on every covered method, function and class lists the tests that cover it — all of them in one run
  from the top of the list, or one at a time — and the Coverage panel has a switch for those icons.
- The popup on a covered line highlights the row under the pointer and ends with a button running all of that line's
  covering tests.
- Every run is archived — its output, its reports and the parameters it ran with — and replays from the *Test History*
  button as a full Testo console: channels, statuses, report buttons and that run's own coverage.
- *Show history* above a test replays the newest archived run containing that test and selects its node.
- How many archived runs to keep is set in *Tools | Testo*; the history list clears itself from its own menu.
- *Expand All* / *Collapse All* now sit on the toolbar itself, next to *Show Passed* / *Show Ignored*.
- A tab opened from the history reruns with the executor the archived run used: a coverage run reruns with coverage.
- A *Replay* button on the test toolbar exports the run as a single archive, imports one back, shows the run's own
  folder in the file manager, and says what the history may do with it: keep it, drop it, or lock it so retention
  never touches it. The button wears the icon of what the run was — run, debug or coverage.
- Every report a run announces is archived with it — an HTML report travels with its assets — so a replayed run opens
  its own reports rather than whatever the latest run left behind.
- The history list marks the run the tab is showing in bold, and puts a lock on the locked ones, and each entry wears
  the icon of what the run was: run, debug or coverage.
- How many runs the history keeps is set from the history list itself, right above the button that clears it.
- An imported run comes in locked, so retention never deletes the one copy of a run carried in from elsewhere.
- The run configuration keeps its coverage settings in a group of their own, with the analysis level
  (`--coverage-level`, or *auto* to leave it to testo.php) and options only a Coverage run adds — benchmarks are kept
  out of coverage by default.
- Group and Exclude group are lists of tags, added from the `#[Group]` names the project declares rather than typed
  with commas.
- Suite is a list of tags too — typed and added with Enter — and a run may now narrow to several suites at once, one
  `--suite` flag each. A configuration saved with a single suite keeps it.
- The run configuration is laid out in groups: Parallel sits under Test Runner Options, then Run Options, Filter and
  Coverage.

### Removed

- The Repeat field: Testo's command line has no `--repeat`, so it never did anything.
- The Command field: a test run is always `testo run`, and other subcommands are what *Run Anything* is for.
- Parallel no longer sends a flag Testo does not have: the field is parked at 1 (no `--parallel`) until it does.

### Changed

- The *Log Levels* filter moved off the test toolbar onto the console's own toolbar, right of the channel tabs, and
  wears a filter icon.

### Fixed

- The Test Runner Options help button opens Testo's CLI reference.
- The channel console no longer throws an EDT-threading error while streaming live output into an aggregate tab.
- The report buttons no longer trigger a "slow operations on EDT" error: report paths now resolve off the UI thread.
- The elapsed time in the run summary no longer counts up forever when a run ends before the toolbar is wired.
- Clearing the history now shows on the tab of the run it spares: its *Replay* menu says *Do not keep in history*,
  where it used to keep claiming the run was being kept.
- The first coverage run of an IDE session paints the editor right away, instead of waiting for something else to
  refresh the highlighting.
- Lists of covering tests name the test class without its namespace, which is the same for every row anyway.
- Excluding a group runs again: it goes out as `--group=!name`, the only form Testo's command line has.

## [2026.5.262] - 2026-08-12

### Added

- Report buttons on the test toolbar — one per report Testo announces, opening it in a JCEF tab or the browser.
- A report can open on its own once the run delivers it: armed by a click during the run, or standing per project /
  every project, independently per way of opening.
- The report menu also shows the file in the file manager and copies its path.
- Reports written behind a remote interpreter or in a container are reached through the PHP path mapper.

### Fixed

- The toolbar run summary no longer jitters in width as its counters tick.

## [2026.4.262] - 2026-08-10

### Added

- The test toolbar ends with a run summary: a progress ring that turns into the run's verdict, the finished/total
  count, one counter per Testo status and the elapsed time. Statuses come from the `status` attribute of Testo's
  service messages, so all eight cases of `Testo\Core\Value\Status` are told apart; a Testo too old to send it still
  gets counters, from the three outcomes TeamCity has always carried.
- Every counter filters the test tree: click one to see only tests with that status, click it again or click the
  total to bring the whole tree back. Hovering the total shows how many assertions the run made.
- A selected counter owns the tree while it is selected rather than narrowing the standing *Show passed* /
  *Show ignored* toggles further; releasing it puts the tree back under them.
- *Show passed* and *Show ignored* now read Testo's statuses. Hiding the passed ones takes flaky along but leaves
  **risky**, which used to arrive as a plain pass; hiding the ignored ones takes cancelled along with skipped.
- The tree's root node carries the run's verdict, read from the summary rather than worked out again.
- The clock's hover breaks the run into startup, the tests themselves and post-processing, and sums the tests' own
  durations beside them — only where that sum parts with the window they fitted into. With tests on fibers it runs
  past that window, and the difference is read as a concurrency boost, stated as a floor (`≥2.1x`).
- A problem Testo raises about the run itself rather than about a test — `##teamcity[buildProblem …]`, as an empty
  run reports — is now shown: a red line in Output, a run-level notice in All, and a notification. Once per
  `identity`.
- A node announced with `testSuite` and `testType` keeps them on rerun: `--suite` and `--type` are added the way the
  node itself was narrowed. Optional, so a Testo that sends neither runs exactly as before.
- The results tree carries one icon per Testo status, group nodes included — off the outcome Testo rolls up onto
  their `testSuiteFinished`. A test still running keeps its animation.
- A test's `metainfo` — its PHPDoc summary — shows as the tooltip of its node in the results tree.

### Fixed

- Rerunning a data set or a case from the results tree runs that data set or case, not its whole file. The node's own
  hint spells out the selector (`\Ns\Calculator::med:3:0`), but PHP declares no such member, so it resolved to the
  class; the selector now goes to `--filter` verbatim, class and all.
- No more "Cannot find '…' in '….php'" stopping a run that Testo would have executed. That check assumed the Method
  field names a member the file declares, but it holds a `--filter` selector — `med:1:0`, `\Ns\Case`,
  `\Ns\freeFunction` — that PHP declares nothing under. Whether a filter matches anything is Testo's call.
- Jump to Source on a data set node opens the test it belongs to: the coordinates are taken off the hint before the
  lookup, and a standalone function is looked for even in a file that also holds a class.
- The toolbar run summary follows the IDE's zoom. Its cells are painted by hand and took the label font once, and
  nothing reinstalls a font on a component that has no UI delegate.
- The spinner on a running test follows the IDE's zoom too. The platform's caches its rasterized frames under the
  icon's colour alone, so the tree draws its own where the sizes disagree; the cache key is fixed upstream in
  [IJPL-252440](https://youtrack.jetbrains.com/issue/IJPL-252440).
- A Testo older than 0.10.39 no longer floods the IDE with internal errors. Such a build sends no node ids, so the
  run now stops at the first such message and says what to update instead.

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

[Unreleased]: https://github.com/j-plugins/testo-plugin/compare/v2026.5.262...HEAD
[2026.5.262]: https://github.com/j-plugins/testo-plugin/compare/v2026.4.262...v2026.5.262
[2026.4.262]: https://github.com/j-plugins/testo-plugin/compare/v2026.3.1...v2026.4.262
[2026.3.1]: https://github.com/j-plugins/testo-plugin/commits/v2026.3.1

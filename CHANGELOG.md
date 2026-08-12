<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# testo-plugin Changelog

## [Unreleased]

### Added

- Report buttons at the right end of the test toolbar, past the run summary — one per report Testo announces with
  `##teamcity[testoReport format='html' path='…' relativePath='…' name='…' schemaVersion='…']`, labelled with the name
  the announcement carries. A run that writes no report shows no button. The report counts as delivered once the
  process has exited with the file in place and no older than the run — deliberately not any earlier, since a report is
  announced as Testo starts writing it, over the path the previous run wrote to; so stopping a run never opens the
  previous run's report.
- Clicking a button opens the report in an editor tab rendered by JCEF; the arrow on it offers the external browser,
  showing the file in the file manager, and copying the report's path. Reopening an already-open report reloads it, so
  the tab shows the run that just finished rather than the one it was opened for.
- The button is live the whole time: a click before the report is delivered is kept and replayed once the run delivers
  the file, marked by the button's icon turning green and by the *Open When This Run Finishes* checkmark
  under the arrow's *Open in WebView* / *Open in Browser* entries. A second click un-presses it — every way of opening
  is silenced for this run alone, while the standing project- and application-wide checkmarks stay put; pressed again,
  they resume, and with none of them checked the press schedules the WebView for this run. The icon's colour tells
  the report's state — grey with nothing to open, blue with the run's report on disk, green with an open scheduled —
  and the tooltip spells it out: still being written, delivered, or never written in this run.
- Those entries also take a standing choice: *Always Open in This Project* or *Always Open in Every Project*, remembered
  per report format and name, opening the report the chosen way as each run delivers it. Every checkmark stands on its
  own — the WebView's and the browser's don't steal from each other, and both checked opens the report both ways.
  Checked after the report already arrived, it starts with the next run rather than popping the current one open.
- Only reports the button can show as a page are offered. Everything else Testo announces is kept, ready for formats the
  plugin will handle differently.
- The announced path is absolute inside the *execution* environment, so it is looked for through the PHP path mapper
  first, then as-is, then as `relativePath` under the project root — which is what makes a report written inside a
  container or behind a remote interpreter reachable.

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

[Unreleased]: https://github.com/j-plugins/testo-plugin/compare/v2026.4.262...HEAD
[2026.4.262]: https://github.com/j-plugins/testo-plugin/compare/v2026.3.1...v2026.4.262
[2026.3.1]: https://github.com/j-plugins/testo-plugin/commits/v2026.3.1

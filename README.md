# ktc-kover

A self-contained local Kotlin Toolchain plugin for JVM coverage with **Kover 0.9.11**.
It runs the project's tests with the Kover agent, generates HTML and JaCoCo-compatible
XML, and checks a minimum line coverage percentage.

Pinned to **Kotlin Toolchain 0.13.0** and its default Kotlin 2.4.20. Supported hosts:
macOS and Linux. Coverage uses the consuming project's own wrapper and test settings.
The sequential runner reuses the normal build directory and executes JVM tests once.

## Try it

```sh
./kotlin check koverCheck -m example
```

The example intentionally leaves two of five executable lines uncovered: a 50%
threshold passes, and 100% fails. Reports are printed in the command output and
written to `build/tasks/_example_koverReport@kover/coverage.xml` and `html/index.html`.
An outer `--build-dir` is supported; outputs follow that directory.

## Install into another project

For the sequential CI runner, install **JDK 25 and Kotlin 2.4.21+** with `kotlinr`
on `PATH`. This script runner is separate from the project's pinned `./kotlin`
Toolchain wrapper. The standalone command above uses only the wrapper.

From your consumer project, use the [ktc-plugins installer](https://github.com/Heapy/ktc-plugins):

```sh
./ktc-plugins add Heapy/ktc-kover --tag v0.2.0 --enable-in app
```

Replace `app` with your consumer module path. The root [`ktc-plugin.yaml`](ktc-plugin.yaml)
declares selector `kover`, module `plugins/kover`, and `LICENSE`; the installer registers
and enables the plugin automatically. Commit the generated manifest, lockfile, and vendored
sources. The lockfile pins the resolved commit. Use `--branch main` for development snapshots
or `--commit <full-40-character-SHA>` for a specific revision. Templates are copied separately.

For manual installation:

Copy `plugins/kover` into your project's `plugins/kover`. Add it to both lists in
`project.yaml` (preserve your existing modules and plugins):

```yaml
modules:
  - app
  - plugins/kover
plugins:
  - //plugins/kover
```

Enable it in `app/module.yaml`:

```yaml
plugins:
  kover:
    enabled: true
    minimumLineCoverage: 80
    includes: ["com.example.*"]
    excludes: ["com.example.generated.*"]
    includeTags: []
    excludeTags: []
    testTimeoutSeconds: 1800
```

All settings are optional. The minimum defaults to 0; an empty class selection or
zero executed tests still fails. Includes/excludes are Kover class-name wildcards,
not filesystem globs. Tag values are the toolchain's JUnit tag expressions.

The module contains literal Maven coordinates and no references outside its directory.
`ktc-plugin.yaml` is producer metadata for the
[ktc-plugins source installer](https://github.com/Heapy/ktc-plugins). It selects the
self-contained plugin module and includes the root license automatically.

`templates/coverage.module-template.yaml` is an optional reusable 80% policy. Copy it
separately if desired; the installer payload is the plugin module and license.

## Efficient CI execution

The runner ships inside the installed plugin module. From the consumer project root:

```sh
kotlinr plugins/kover/coverage.main.kts -m app
```

Repeat `-m` to select several coverage-enabled modules; omit it to cover all enabled
modules. The runner prepares all selected modules, executes each module's JVM tests
once with its own agent configuration, and then generates reports and checks thresholds.
All toolchain commands run sequentially in the **same build directory**. There is no
nested toolchain invocation or separate `test-build` compilation tree. Unchanged main
and test compilation outputs are reused between commands and subsequent invocations.

Use this command **in place of** the ordinary JVM test run for covered modules.
Other modules and platforms still need their normal tests. For example:

```sh
# Instrumented JVM tests and coverage check for app:
kotlinr plugins/kover/coverage.main.kts -m app
# JVM tests for modules not covered above (including plugin modules):
./kotlin test --platform jvm --exclude-module app
# Keep the project's existing non-JVM test matrix and named non-coverage checks.
```

Do not subsequently run `koverCheck` again or an unfiltered `./kotlin check`: the
standalone coverage task would run tests again. Run the remaining checks by name,
for example `./kotlin check apiCheck ktlintCheck`, with the checks enabled in your project.

The runner requires JDK 25 and Kotlin 2.4.21+ (`kotlinr` on `PATH`), like the verification
scripts. `--project-dir` defaults to the current directory. `--build-dir` defaults to
`KOTLIN_TOOLCHAIN_BUILD_DIR`, or `build` under the project root. Relative build paths
are resolved against the project root. For example:

```sh
kotlinr plugins/kover/coverage.main.kts -m app \
  --build-dir "build/ci coverage" \
  --build-timeout-seconds 1800 \
  --test-timeout-seconds 3600
```

`--build-timeout-seconds` is the budget for each preparation/reporting command (default
1800 seconds). Each test command uses the module's `testTimeoutSeconds`, now defaulting
to 1800 seconds; `--test-timeout-seconds` overrides it for this run. **The test-command
budget still includes any required test/dependency compilation.** The public plugin API
does not expose a separate test-compilation artifact, so these are command budgets,
not a promise of an execution-only test timeout. Module tag filters remain effective.

Preparation clears the old binary in its own output directory. Reporting reads that
binary from the session manifest and clears old XML/HTML reports in its own output
directory, including after test failures and timeouts. A fresh temporary session
records successful test commands and newly written test results. Reporting rejects a
missing success marker, zero executed tests, or missing/empty coverage. Test failures
and timeouts stop the pipeline; a threshold failure retains the new reports. The runner
serializes coverage sessions using a lock in the build directory. Avoid other builds
or source edits against that directory while a coverage session is in progress.

`koverPrepare` is an internal command used by the runner. It does not compile consumer
sources or launch tests. The runner passes its temporary session to the toolchain;
`koverReport` then reads that session instead of starting another build.

## Standalone tasks and scope

Existing commands remain compatible:

```sh
./kotlin check koverCheck -m example
./kotlin do koverReport -m example
```

Without a runner session, `koverReport` launches instrumented JVM tests in its isolated
`test-build` directory as before. These convenience commands retain the additional
compilation cost. A plain `./kotlin check` runs ordinary tests as well, duplicating the
covered JVM tests. Prefer the sequential runner in CI.

Reports retain their existing paths under `build/tasks/*_koverReport@kover/`, including
with a custom build directory. Only the selected module's JVM main classes are reported,
using original compiled classes and main source roots from the public plugin model.
The report task always executes; `koverCheck` compares the exact aggregate line ratio
without rounding.

Reports from separate modules are not merged. Android, Native/JS coverage, Windows hosts,
branch thresholds and cross-module aggregation remain unsupported. JVM tests of a KMP
module may work, but the maintained example is a JVM library. Tests use the debug/default
variant.

## Develop and verify

```sh
./kotlin test -m kover
kotlinr scripts/smoke.main.kts
```

Unit tests cover exact threshold arithmetic, aggregate XML selection, invalid counts,
external-DTD isolation, argument quoting and option injection rejection. The smoke
script uses a temporary consumer path containing spaces and checks real instrumentation,
non-empty HTML/XML, unmet thresholds, test failure propagation, empty tag selections,
stale-report removal and recovery. It also checks that the sequential runner executes
tests once per invocation, reuses unchanged main/test classes, honors custom build paths
and timeouts, and recovers after failures. CI runs these on Ubuntu and macOS.

## Upstream references

- [Kover agent configuration](https://github.com/Kotlin/kotlinx-kover/blob/v0.9.11/kover-jvm-agent/docs/index.md)
- [Kover reporting implementation](https://github.com/Kotlin/kotlinx-kover/blob/v0.9.11/kover-features-jvm/src/main/java/kotlinx/kover/features/jvm/KoverLegacyFeatures.kt)
- [Toolchain JVM argument parsing](https://github.com/JetBrains/kotlin-toolchain/blob/v0.13.0/sources/amper-cli/src/org/jetbrains/amper/cli/options/userJvmArgsOption.kt)

Apache-2.0; see [LICENSE](LICENSE). Kover and Kotlin Toolchain retain their respective
upstream licenses. This project is an independent integration.

## Real-project validation

On 2026-10-05, this plugin was copied into a detached worktree of
[Heapy/kotgent](https://github.com/Heapy/kotgent) at
`ac1f35a21af210c0579b3536f326da96dace0ebb`, registered in `project.yaml`, and enabled
on its existing `plugins/build-info` JVM plugin module. The Native application is
outside this plugin's JVM coverage scope. No application code or test filters were
changed for the successful coverage run.

With Kotlin Toolchain 0.13.0 on macOS ARM64, the 10 existing tests passed and the
report measured **58/87 executable lines (66.67%)**, across three compiled classes
from `Generate.kt` and `PrintKexePath.kt`. HTML includes actual source text and covered/
uncovered line highlighting. `koverCheck` passed with a 66% minimum. Raising the
minimum to 100% failed with `Line coverage 58/87 is below 100%`, retaining the
reports. Temporarily reversing one existing assertion produced 10 tests with one
failure, failed the coverage command, and removed the previous XML and HTML
reports; the test mutation was then restored. The recovery run passed all 10 tests
and reproduced 58/87 lines. No plugin implementation changes were required.

The commands, run from the consumer worktree, were:

```sh
kotgent mutex run kotlin-build --session <session-id> -- ./kotlin do koverReport \
  -m build-info --build-dir "$PWD/validation/build"
kotgent mutex run kotlin-build --session <session-id> -- ./kotlin check koverCheck \
  -m build-info --build-dir "$PWD/validation/build"
```

The Kotgent mutex serialized outer Toolchain invocations across worktrees. Kover's
internal child ran while that outer mutex was held, using the explicit, separate
`validation/build/tasks/_build-info_koverReport@kover/test-build` directory. It did
not reacquire the mutex. macOS sandbox process inspection blocked Toolchain cleanup
(`sysctl`); the successful checks ran outside that sandbox.

The retained local trial is
`../ktc-plugin-trials/2026-10-05/kotgent-kover/validation`: `result.json` records
commands and results, `02-report.log` through `06-recovery.log` retain execution
evidence, and `build/tasks/_build-info_koverReport@kover/html/index.html` is the final
report. The consumer keeps the plugin enabled with a 66% threshold; original source
and test files are unchanged. This trial did not cover the Native runtime or Linux.

## Running verification scripts

The `.main.kts` scripts require JDK 25 and Kotlin 2.4.21+ (`kotlinr` on `PATH`).
Run them with `kotlinr scripts/<name>.main.kts` from the repository root.
The Kotlin Toolchain `./kotlin` command is a separate executable. CI installs the script runner
through [Heapy/setup-main-kts](https://github.com/Heapy/setup-main-kts), pinned to v1.0.1's
commit SHA. The action caches the compiler, Maven dependencies, and compiled scripts between
eligible CI runs. The first script run compiles the script and resolves any pinned Maven
dependencies; later runs reuse the script cache.

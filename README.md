# ktc-kover

A self-contained local Kotlin Toolchain plugin for JVM coverage with **Kover 0.9.11**.
It runs the project's tests with the Kover agent, generates HTML and JaCoCo-compatible
XML, and checks a minimum line coverage percentage.

Initial implementation, pinned to **Kotlin Toolchain 0.13.0** and its default Kotlin
2.4.20. Supported hosts: macOS and Linux. Each coverage invocation performs a fresh
test run using the consuming project's own wrapper and test settings.

## Try it

```sh
./kotlin check koverCheck -m example
./kotlin do koverReport -m example
```

The example intentionally leaves two of five executable lines uncovered: a 50%
threshold passes, and 100% fails. Reports are printed in the command output and
written to `build/tasks/_example_koverReport@kover/coverage.xml` and `html/index.html`.
An outer `--build-dir` is supported; outputs follow that directory.

## Install into another project

From your consumer project, use the [ktc-plugins installer](https://github.com/Heapy/ktc-plugins):

```sh
./ktc-plugins add Heapy/ktc-kover --branch main --enable-in app
```

Replace `app` with your consumer module path. The root [`ktc-plugin.yaml`](ktc-plugin.yaml)
declares selector `kover`, module `plugins/kover`, and `LICENSE`; the installer registers
and enables the plugin automatically. Commit the generated manifest, lockfile, and vendored
sources. The lockfile pins the resolved commit; use `--commit <full-40-character-SHA>`
instead of `--branch main` to select a specific revision. Templates are copied separately.

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
    testTimeoutSeconds: 600
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

## Execution and scope

`koverReport` launches `./kotlin test -m <module> --platform jvm --jvm-args ...` with
Kover attached. The child uses its own `test-build` directory under the report task's
output. This preserves toolchain test discovery, test dependencies, JDK provisioning,
and test settings without depending on private test-classpath APIs or sharing build
locks with the parent. It costs an additional compilation on the first coverage run.
The original compiled classes and main source roots are taken from the public plugin
model for reporting. Only the selected module's JVM main classes are reported.

The report task always executes. It removes old binary/XML/HTML reports first, disables
agent report append, and fails if the test process fails, times out, or executes no tests.
`koverCheck` depends on that fresh report and compares the exact aggregate line ratio
without rounding. A failed threshold retains reports for inspection.

Use `./kotlin check koverCheck` to run coverage checks across enabled modules. A plain
`./kotlin check` also runs ordinary tests, so those tests run twice. Reports from
separate modules are not merged. This initial version does not support Android,
Native/JS coverage, Windows hosts, branch thresholds, or cross-module aggregation.
JVM tests of a KMP module may work, but the maintained example is a JVM library.
The nested test run uses the debug/default test variant.

## Develop and verify

```sh
./kotlin test -m kover
python3 scripts/smoke.py
```

Unit tests cover exact threshold arithmetic, aggregate XML selection, invalid counts,
external-DTD isolation, argument quoting and option injection rejection. The smoke
script uses a temporary consumer path containing spaces and checks real instrumentation,
non-empty HTML/XML, unmet thresholds, test failure propagation, empty tag selections,
stale-report removal and recovery. CI runs these on Ubuntu and macOS.

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

# Changelog

## 0.2.0 — 2026-10-22

- Add `koverPrepare` and the sequential `plugins/kover/coverage.main.kts` runner to execute JVM tests once and reuse compilation outputs.
- The CI runner requires JDK 25 and Kotlin 2.4.21+ with `kotlinr` on `PATH`; standalone wrapper commands remain supported.
- Raise the default `testTimeoutSeconds` from 600 to 1800 seconds, including test compilation.

## 0.1.1 — 2026-10-21

- Forward nested Kotlin Toolchain stdout and stderr through the parent task logger.
- Drain both streams concurrently and preserve final output, failures and timeouts.

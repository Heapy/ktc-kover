# Changelog

## 0.1.1 — 2026-10-21

- Forward nested Kotlin Toolchain stdout and stderr through the parent task logger.
- Drain both streams concurrently and preserve final output, failures and timeouts.

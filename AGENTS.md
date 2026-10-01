# Kestra Playwright Plugin

## What

- Provides browser checks under `io.kestra.plugin.playwright`.
- Exposes `io.kestra.plugin.playwright.Check`, which connects to a remote Playwright server and runs an ordered action list in one browser context.
- Stores named screenshots and Playwright traces in Kestra internal storage.

## Why

- Release and platform teams can gate deployments with browser smoke checks.
- Operations teams can schedule synthetic checks for critical paths such as login and checkout.
- Browser setup, timeouts, assertions, screenshots, and traces stay consistent across flows.

## How

### Architecture

This is a single-module plugin with one root package:

- `io.kestra.plugin.playwright`

The worker runs the Playwright Java client. Browsers run on a remote Playwright server connected over WebSocket. The Java client and server versions must match exactly.

### Key plugin class

- `io.kestra.plugin.playwright.Check`

### Runtime lifecycle

- One Playwright connection, browser, browser context, and page are created per task run.
- Actions execute sequentially in the same context.
- `kill()` closes the session and marks the run killed; `stop()` closes it for worker shutdown without marking it killed.
- Rendered `FILL` values must never be logged.

### Development server

Use the version pinned in `build.gradle`:

```bash
docker run --rm -p 3000:3000 mcr.microsoft.com/playwright:v1.63.0-noble \
  npx -y playwright@1.63.0 run-server --port 3000 --host 0.0.0.0
```

### Project structure

```text
plugin-playwright/
├── src/main/java/io/kestra/plugin/playwright/Check.java
├── src/main/resources/doc/io.kestra.plugin.playwright.md
├── src/test/java/io/kestra/plugin/playwright/CheckTest.java
├── src/test/java/io/kestra/plugin/playwright/CheckUnitTest.java
├── src/test/java/io/kestra/plugin/playwright/CheckValidationTest.java
├── build.gradle
└── README.md
```

## Local rules

- Keep the Playwright Java dependency, server command, Docker image, task description, examples, and documentation on the same version.
- Use Playwright assertions for checks so assertions retain auto-waiting behavior.
- Store failure artifacts before raising an actionable task error.
- Base wording on implemented packages and classes.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
- https://playwright.dev/java/docs/intro

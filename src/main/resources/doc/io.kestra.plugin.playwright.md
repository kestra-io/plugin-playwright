# Playwright

Use the Playwright plugin for browser checks that should fail a Kestra task when a page, element, text value, URL, or title does not meet an expectation. Common uses include post-deploy smoke checks and scheduled synthetic checks of login or checkout paths.

## Authentication

The plugin connects to a remote Playwright server over WebSocket. It starts a local Playwright Node driver for each run, but does not launch or download browsers on the Kestra worker. The shaded JAR includes Linux x64 and ARM64 drivers only, so this build requires a Linux worker.

Playwright requires the server and Java client versions to match exactly. This plugin uses Playwright `1.63.0`. Start the matching server with:

```bash
docker run --rm -p 3000:3000 mcr.microsoft.com/playwright:v1.63.0-noble \
  npx -y playwright@1.63.0 run-server --port 3000 --host 0.0.0.0
```

Set `serverUrl` to the resulting endpoint, such as `ws://playwright:3000/`, or use `wss://` when the server is behind TLS. Store the endpoint in a Kestra secret if it contains credentials or other sensitive connection data.

Use the worker's network egress policy to allow only trusted Playwright endpoints. Scheme validation does not restrict the destination host. Starting a local Node driver for every task run adds startup time.

When upgrading Playwright, update the Java dependency, Docker image tag, `npx` package version, examples, and documentation together.

## Tasks

`Check` runs every item in `actions` sequentially in one browser context:

| Action | Fields | Behavior |
| --- | --- | --- |
| `NAVIGATE` | `url` | Opens an HTTP, HTTPS, or data URL, or resolves a relative URL against `baseUrl`. Use a trusted Playwright server for pages with sensitive data. |
| `CLICK` | `selector` | Clicks the matching element. |
| `FILL` | `selector`, `value` | Replaces an input value; an empty string clears the input. The rendered value is never logged. |
| `PRESS` | `selector`, `key` | Sends a key or shortcut such as `Enter` or `Control+A`. |
| `WAIT_FOR` | `selector` | Waits until the matching element is visible. |
| `SCREENSHOT` | `name`, optional `fullPage` | Stores a PNG in Kestra internal storage. `name` cannot contain path separators or be `.` or `..`. |
| `ASSERT_VISIBLE` | `selector` | Waits for the matching element to be visible. |
| `ASSERT_TEXT` | `selector`, `text`, optional `regex` | Waits for the element text to equal a string (including empty text) or match a regular expression. |
| `ASSERT_URL` | `url`, optional `regex` | Waits for the page URL to equal a string or match a regular expression. |
| `ASSERT_TITLE` | `title` | Waits for the page title to equal a string, including an empty title. |

Every action can have an `id`. When an action fails, the task message includes its zero-based index and ID, the selector when present, expected and actual values, and artifact URIs.

All action fields support Pebble expressions through the `actions` property. The whole list is rendered once at list level, so action fields are plain values and not individual `Property` objects. The task renders and validates every action before the browser starts, so a bad template or missing field fails before earlier actions can change a page.

Expected and actual text and titles in assertion failures are limited to 200 characters, followed by `...` when truncated. Restrict access to execution logs and consider this page content when forwarding errors to another service. For runs containing `FILL` or `PRESS`, the actual text and title are hidden by default; set `includeActualValue: true` to show them.

With `regex: true`, patterns are validated with Java `Pattern` and evaluated by Playwright as JavaScript regular expressions. Use syntax supported by both engines.

`actionTimeout` applies to each action and assertion. It defaults to `PT30S` and accepts values from `PT0.001S` to `PT10M`. Kestra's standard `timeout` property limits the whole task run independently.

## Screenshots and traces

Named `SCREENSHOT` actions are returned in the `screenshots` output as a map of names to internal storage URIs.

Tracing supports three modes:

- `ON_FAILURE` records a trace and stores it only when an action fails. This is the default. It is skipped for runs containing `FILL` or `PRESS`.
- `ALWAYS` stores a trace for successful and failed checks. Successful trace URIs are returned in the `trace` output.
- `OFF` disables traces.

Quote `"OFF"` in YAML; otherwise the YAML parser may read it as a boolean.

Traces include network requests and headers (cookies, authorization headers), page snapshots, screenshots, and page content, so they can expose credentials and personal data. Use `OFF` for sensitive pages and limit access to stored traces.

With `ON_FAILURE`, tracing is skipped for a run containing `FILL` or `PRESS` because a trace can include the entered value, and the task logs a warning. Set `trace: ALWAYS` to opt in explicitly for such runs; the trace is then stored on success and on failure.

`NAVIGATE` URLs with credentials or query tokens are not detected as sensitive input. Those URLs may still appear in traces and screenshots even though failure messages redact their sensitive parts. For those flows, set `trace: "OFF"` and `failureScreenshot: false` and avoid named screenshots of sensitive pages.

`failureScreenshot` controls the automatic full-page screenshot after a failed action. It is enabled by default for every run, including those with `FILL` or `PRESS`, so failures stay debuggable. Set it to `false` for sensitive pages. Named `SCREENSHOT` actions always capture the page when reached. Screenshots may show entered non-password values; limit access to stored artifacts.

Open a downloaded trace with the [Playwright Trace Viewer](https://trace.playwright.dev/).

## Post-deploy smoke check

```yaml
id: post_deploy_smoke_check
namespace: company.team

inputs:
  - id: app_url
    type: STRING
    defaults: https://staging.example.com

tasks:
  - id: smoke_check
    type: io.kestra.plugin.playwright.Check
    serverUrl: "{{ secret('PLAYWRIGHT_SERVER_URL') }}"
    baseUrl: "{{ inputs.app_url }}"
    actions:
      - action: NAVIGATE
        url: /login
      - action: FILL
        selector: "#email"
        value: "{{ secret('SMOKE_TEST_USER') }}"
      - action: FILL
        selector: "#password"
        value: "{{ secret('SMOKE_TEST_PASSWORD') }}"
      - action: CLICK
        selector: "button[type='submit']"
      - action: ASSERT_URL
        url: ".*/dashboard"
        regex: true
      - action: ASSERT_VISIBLE
        id: dashboard_header
        selector: "h1.dashboard-title"
      - action: SCREENSHOT
        name: dashboard.png

errors:
  - id: alert
    type: io.kestra.plugin.slack.SlackIncomingWebhook
    url: "{{ secret('SLACK_WEBHOOK_URL') }}"
    messageText: "Post-deploy smoke check failed on {{ inputs.app_url }}: {{ errorLogs()[0]['message'] }}"
```

## Scheduled synthetic check

Browser checks are actions, so scheduled checks use Kestra's core `Schedule` trigger:

```yaml
id: synthetic_checkout_check
namespace: company.team

triggers:
  - id: every_15_minutes
    type: io.kestra.plugin.core.trigger.Schedule
    cron: "*/15 * * * *"

tasks:
  - id: checkout
    type: io.kestra.plugin.playwright.Check
    serverUrl: "{{ secret('PLAYWRIGHT_SERVER_URL') }}"
    baseUrl: https://shop.example.com
    actions:
      - action: NAVIGATE
        url: /products/demo-item
      - action: CLICK
        selector: "button.add-to-cart"
      - action: NAVIGATE
        url: /cart
      - action: ASSERT_TEXT
        selector: ".cart-count"
        text: "1"
      - action: ASSERT_VISIBLE
        selector: "a.checkout"
```

## Playwright and Selenium

Use this plugin when the flow expresses a browser check with assertions, automatic waiting, screenshots on failure, and diagnostic traces.

Use the Selenium plugin for broader browser automation such as scraping, form workflows, file downloads, and JavaScript execution against Selenium Grid.

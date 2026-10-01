package io.kestra.plugin.playwright;

import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.TimeoutError;
import io.kestra.core.models.property.Property;
import org.junit.jupiter.api.Test;

import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CheckUnitTest {
    @Test
    void shouldAllowEmptyAndWhitespaceActionContent() {
        for (var content : List.of("", "   ")) {
            var actions = List.of(
                Check.Action.builder().action(Check.ActionType.FILL).selector("#input").value(content).build(),
                Check.Action.builder().action(Check.ActionType.ASSERT_TEXT).selector("#result").text(content).build(),
                Check.Action.builder().action(Check.ActionType.ASSERT_TITLE).title(content).build()
            );

            assertDoesNotThrow(() -> Check.validateActions(actions, null));
        }
    }

    @Test
    void shouldRejectMissingContentAndBlankActionTargets() {
        var invalidActions = List.of(
            Check.Action.builder().action(Check.ActionType.FILL).selector("#input").build(),
            Check.Action.builder().action(Check.ActionType.ASSERT_TEXT).selector("#result").build(),
            Check.Action.builder().action(Check.ActionType.ASSERT_TITLE).build(),
            Check.Action.builder().action(Check.ActionType.CLICK).selector(" ").build(),
            Check.Action.builder().action(Check.ActionType.NAVIGATE).url("").build(),
            Check.Action.builder().action(Check.ActionType.ASSERT_URL).url("").build(),
            Check.Action.builder().action(Check.ActionType.SCREENSHOT).name("").build(),
            Check.Action.builder().action(Check.ActionType.PRESS).selector("#input").key("").build()
        );

        for (var action : invalidActions) {
            var exception = assertThrows(IllegalArgumentException.class,
                () -> Check.validateActions(List.of(action), null));
            assertThat(exception.getMessage(), containsString("is required"));
        }
    }

    @Test
    void shouldSummarizeFailuresWithoutExposingSdkMessages() {
        var secretUrl = "ws://user:private-password@example.com/?token=private-token";
        var reasons = Map.of(
            "getaddrinfo ENOTFOUND " + secretUrl, "DNS resolution failed",
            "getaddrinfo EAI_AGAIN " + secretUrl, "DNS resolution failed",
            "connect ECONNREFUSED " + secretUrl, "connection refused",
            "connect ETIMEDOUT " + secretUrl, "operation timed out",
            "Playwright version mismatch at " + secretUrl, "Playwright client/server version mismatch"
        );
        for (var entry : reasons.entrySet()) {
            var summary = Check.shortMessage(new RuntimeException(entry.getKey()));

            assertThat(summary, is("RuntimeException: " + entry.getValue()));
        }
        assertThat(Check.shortMessage(new RuntimeException("Filled value 'private-value'")), is("RuntimeException"));
        assertThat(Check.shortMessage(new RuntimeException()), is("RuntimeException"));
        assertThat(Check.shortMessage(new RuntimeException("Private endpoint", new UnknownHostException(secretUrl))),
            is("UnknownHostException: DNS resolution failed"));
        assertThat(Check.shortMessage(new TimeoutError("Private filled value")),
            is("TimeoutError: operation timed out"));
    }

    @Test
    void shouldResolveRelativeNavigationUrls() {
        assertThat(Check.resolveUrl("/login", "https://example.com/app/"), is("https://example.com/login"));
        assertThat(Check.resolveUrl("next", "https://example.com/app/"), is("https://example.com/app/next"));
        assertThat(Check.resolveUrl("data:text/html,hello", null), is("data:text/html,hello"));
    }

    @Test
    void shouldRejectRelativeUrlWithoutBaseUrl() {
        var exception = assertThrows(IllegalArgumentException.class, () -> Check.resolveUrl("/login", null));

        assertThat(exception.getMessage(), containsString("baseUrl is required"));
    }

    @Test
    void shouldRejectUnsafeNavigationSchemesAndNameInvalidUrls() {
        assertThat(assertThrows(IllegalArgumentException.class, () -> Check.resolveUrl("file:///etc/passwd", null)).getMessage(),
            containsString("http, https, or data"));
        assertThat(assertThrows(IllegalArgumentException.class, () -> Check.resolveUrl("http://bad host", null)).getMessage(),
            containsString("url for NAVIGATE must be a valid HTTP or HTTPS URL"));
        assertThrows(IllegalArgumentException.class, () -> Check.resolveUrl("ja\nvascript:alert(1)", "https://example.com/"));
        assertThrows(IllegalArgumentException.class, () -> Check.resolveUrl("  javascript:alert(1)", "https://example.com/"));
    }

    @Test
    void shouldAcceptBrowserUrlsWithBracketsSpacesAndUnicode() {
        assertThat(Check.resolveUrl("https://example.com/?filter[status]=open", null),
            is("https://example.com/?filter%5Bstatus%5D=open"));
        assertThat(Check.resolveUrl("/search?q=a b", "https://example.com/app/"),
            is("https://example.com/search?q=a%20b"));
        assertThat(Check.resolveUrl("https://example.com/café?value={a|b}", null),
            is("https://example.com/café?value=%7Ba%7Cb%7D"));
        assertThat(Check.resolveUrl("?q=a b", "https://example.com/app/page?old=1#old"),
            is("https://example.com/app/page?q=a%20b"));
        assertThat(Check.resolveUrl("http://[::1]:3000/a?b=1", null), is("http://[::1]:3000/a?b=1"));
        assertThat(Check.resolveUrl("login", "https://example.com"), is("https://example.com/login"));
    }

    @Test
    void shouldRedactSensitivePartsOfUrls() {
        assertThat(Check.safeUrl("https://user:password@example.com/path?code=secret#fragment"), is("https://example.com/path"));
        assertThat(Check.safeUrl("/login?token=secret#fragment"), is("/login"));
        assertThat(Check.safeUrl("data:text/html,secret"), is("data:<redacted>"));
    }

    @Test
    void shouldExcludeFillValuesFromGeneratedStrings() {
        var action = Check.Action.builder()
            .action(Check.ActionType.FILL)
            .selector("#password")
            .value("highly-sensitive-value")
            .build();
        var task = Check.builder()
            .id("secret-check")
            .type(Check.class.getName())
            .actions(Property.ofValue(List.of(action)))
            .build();

        assertThat(action.toString(), not(containsString("highly-sensitive-value")));
        assertThat(task.toString(), not(containsString("highly-sensitive-value")));
    }

    @Test
    void shouldMatchPlaywrightClientVersionOnClasspath() {
        assertThat(Check.PLAYWRIGHT_VERSION, is(Playwright.class.getPackage().getImplementationVersion()));
    }

    @Test
    void shouldRejectUnsafeScreenshotNames() {
        for (var name : List.of(".", "..", "../capture.png", "folder/capture.png", "folder\\capture.png")) {
            assertThrows(IllegalArgumentException.class, () -> Check.screenshotName(name));
        }
        assertThat(Check.screenshotName("capture.png"), is("capture.png"));
        assertThat(Check.screenshotName("a..png"), is("a..png"));
    }

    @Test
    void shouldKeepTaskTimeoutSeparateFromActionTimeout() {
        var task = Check.builder()
            .id("timeout-check")
            .type(Check.class.getName())
            .actions(Property.ofValue(List.of(Check.Action.builder().action(Check.ActionType.ASSERT_TITLE).title("Ready").build())))
            .build();

        assertThat(task.getTimeout(), nullValue());
        assertThat(task.getActionTimeout(), is(Property.ofValue(Check.DEFAULT_ACTION_TIMEOUT)));

        var boundedTask = Check.builder()
            .id("bounded-check")
            .type(Check.class.getName())
            .timeout(Property.ofValue(Duration.ofMinutes(5)))
            .actionTimeout(Property.ofValue(Duration.ofSeconds(2)))
            .build();

        assertThat(boundedTask.getTimeout(), is(Property.ofValue(Duration.ofMinutes(5))));
        assertThat(boundedTask.getActionTimeout(), is(Property.ofValue(Duration.ofSeconds(2))));
    }

    @Test
    void shouldRequireWebSocketServerUrlWithoutEchoingCredentials() {
        assertThat(Check.validateServerUrl("ws://localhost:3000/"), is("ws://localhost:3000/"));
        assertThat(Check.validateServerUrl("wss://example.com/playwright"), is("wss://example.com/playwright"));
        for (var url : List.of("http://example.com/", "file:///tmp/socket", "ws://user:secret@bad host/")) {
            var exception = assertThrows(IllegalArgumentException.class, () -> Check.validateServerUrl(url));
            assertThat(exception.getMessage(), containsString("ws:// or wss://"));
            assertThat(exception.getMessage(), not(containsString("secret")));
        }
    }
}

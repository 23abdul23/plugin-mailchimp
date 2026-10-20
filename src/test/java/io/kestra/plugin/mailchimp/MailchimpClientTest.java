package io.kestra.plugin.mailchimp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.runners.RunContextFactory;

import io.kestra.plugin.mailchimp.FakeMailchimpServer.Response;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class MailchimpClientTest {
    @Inject
    private RunContextFactory runContextFactory;

    private final List<Duration> sleeps = new ArrayList<>();

    private MailchimpClient client(FakeMailchimpServer fake, int maxRetries) throws Exception {
        return new MailchimpClient(runContextFactory.of(), fake.baseUrl(), "abc-us19", maxRetries, Duration.ofSeconds(10), sleeps::add);
    }

    // Kestra's HttpClient replays an idempotent request once, immediately, after a 429/503 (not configurable): the first
    // two scripted answers below are that replay's, so our own backoff only starts with the third request.
    @Test
    void retries429Once() throws Exception {
        try (var fake = new FakeMailchimpServer(); var client = client(fake, 3)) {
            fake.on("GET", "/3.0/ping", Response.empty(429), Response.empty(429), Response.json(200, "{\"health_status\":\"ok\"}"));

            var result = client.send("GET", "/ping", null, null);

            assertThat(result.get("health_status").asText(), equalTo("ok"));
            assertThat(fake.requests(), hasSize(3));
            assertThat(sleeps, hasSize(1));
            assertThat(sleeps.getFirst().toMillis(), greaterThanOrEqualTo(500L));
            assertThat(sleeps.getFirst().toMillis(), lessThanOrEqualTo(1000L));
        }
    }

    @Test
    void retries429ForPostToo() throws Exception {
        try (var fake = new FakeMailchimpServer(); var client = client(fake, 3)) {
            fake.on("POST", "/3.0/lists", Response.empty(429), Response.json(200, "{}"));

            client.send("POST", "/lists", null, java.util.Map.of("name", "x"));

            assertThat(fake.requests(), hasSize(2));
            assertThat(fake.requests().getLast().body(), containsString("\"name\":\"x\""));
        }
    }

    @Test
    void givesUpAfterMaxRetries() throws Exception {
        try (var fake = new FakeMailchimpServer(); var client = client(fake, 3)) {
            fake.on("GET", "/3.0/ping", Response.empty(429));

            var e = assertThrows(MailchimpException.class, () -> client.send("GET", "/ping", null, null));

            assertThat(e.getStatus(), equalTo(429));
            assertThat(fake.requests(), hasSize(8)); // 4 attempts, each followed by Kestra's single replay
            assertThat(sleeps, hasSize(3));
        }
    }

    @Test
    void honorsRetryAfterWhenPresent() throws Exception {
        try (var fake = new FakeMailchimpServer(); var client = client(fake, 3)) {
            fake.on("GET", "/3.0/ping", Response.empty(429).withHeader("Retry-After", "2"), Response.empty(429).withHeader("Retry-After", "2"), Response.json(200, "{}"));

            client.send("GET", "/ping", null, null);

            assertThat(sleeps.getFirst(), equalTo(Duration.ofSeconds(2)));
        }
    }

    @Test
    void retries503OnGet() throws Exception {
        try (var fake = new FakeMailchimpServer(); var client = client(fake, 3)) {
            fake.on("GET", "/3.0/ping", Response.json(503, "{\"title\":\"Service Unavailable\"}"), Response.json(200, "{}"));

            client.send("GET", "/ping", null, null);

            assertThat(fake.requests(), hasSize(2));
        }
    }

    @Test
    void doesNotRetry503OnPost() throws Exception {
        try (var fake = new FakeMailchimpServer(); var client = client(fake, 3)) {
            fake.on("POST", "/3.0/lists", Response.json(503, "{\"title\":\"Service Unavailable\"}"), Response.json(200, "{}"));

            var e = assertThrows(MailchimpException.class, () -> client.send("POST", "/lists", null, java.util.Map.of()));

            assertThat(e.getStatus(), equalTo(503));
            assertThat(fake.requests(), hasSize(1));
            assertThat(sleeps, empty());
        }
    }

    @Test
    void doesNotRetry401() throws Exception {
        try (var fake = new FakeMailchimpServer(); var client = client(fake, 3)) {
            fake.on("GET", "/3.0/ping", Response.json(401, "{\"title\":\"API Key Invalid\",\"status\":401,\"detail\":\"Your API key may be invalid\"}"));

            var e = assertThrows(MailchimpException.class, () -> client.send("GET", "/ping", null, null));

            assertThat(e.getStatus(), equalTo(401));
            assertThat(e.getMessage(), containsString("credentials"));
            assertThat(fake.requests(), hasSize(1));
        }
    }

    @Test
    void mapsFieldErrors() throws Exception {
        try (var fake = new FakeMailchimpServer(); var client = client(fake, 3)) {
            fake.on("POST", "/3.0/lists/a/members", Response.json(400, """
                {"title":"Invalid Resource","status":400,"detail":"The resource submitted could not be validated.",
                 "errors":[{"field":"email_address","message":"This value should be a valid email."}]}"""));

            var e = assertThrows(MailchimpException.class, () -> client.send("POST", "/lists/a/members", null, java.util.Map.of()));

            assertThat(e.getTitle(), equalTo("Invalid Resource"));
            assertThat(e.getFieldErrors().get("email_address"), equalTo("This value should be a valid email."));
            assertThat(e.getMessage(), equalTo("400 Invalid Resource: The resource submitted could not be validated. [email_address: This value should be a valid email.]"));
            assertThat(fake.requests(), hasSize(1));
        }
    }

    @Test
    void emptyBodyFallsBackToStatusText() throws Exception {
        try (var fake = new FakeMailchimpServer(); var client = client(fake, 3)) {
            fake.on("GET", "/3.0/ping", Response.empty(403));

            var e = assertThrows(MailchimpException.class, () -> client.send("GET", "/ping", null, null));

            assertThat(e.getStatus(), equalTo(403));
            assertThat(e.getMessage(), containsString("Forbidden"));
        }
    }

    @Test
    void zeroMaxRetriesNeverRetries() throws Exception {
        try (var fake = new FakeMailchimpServer(); var client = client(fake, 0)) {
            fake.on("GET", "/3.0/ping", Response.empty(429), Response.empty(429), Response.json(200, "{}"));

            var e = assertThrows(MailchimpException.class, () -> client.send("GET", "/ping", null, null));

            assertThat(e.getStatus(), equalTo(429));
            assertThat(fake.requests(), hasSize(2)); // our loop never retries; the 2nd request is Kestra's own single replay
            assertThat(sleeps, empty());
            assertThat(sleeps, empty());
        }
    }

    private MailchimpClient shortTimeoutClient(FakeMailchimpServer fake) throws Exception {
        return new MailchimpClient(runContextFactory.of(), fake.baseUrl(), "abc-us19", 3, Duration.ofMillis(250), sleeps::add);
    }

    @Test
    void retriesGetAfterTimeout() throws Exception {
        try (var fake = new FakeMailchimpServer(); var client = shortTimeoutClient(fake)) {
            fake.on("GET", "/3.0/ping", Response.json(200, "{}").delayed(1000), Response.json(200, "{\"health_status\":\"ok\"}"));

            var result = client.send("GET", "/ping", null, null);

            assertThat(result.get("health_status").asText(), equalTo("ok"));
            assertThat(fake.requests(), hasSize(2));
            assertThat(sleeps, hasSize(1));
        }
    }

    @Test
    void doesNotRetryPostAfterTimeout() throws Exception {
        try (var fake = new FakeMailchimpServer(); var client = shortTimeoutClient(fake)) {
            fake.on("POST", "/3.0/lists", Response.json(200, "{}").delayed(1000), Response.json(200, "{}"));

            assertThrows(java.io.IOException.class, () -> client.send("POST", "/lists", null, java.util.Map.of()));

            assertThat(fake.requests(), hasSize(1));
            assertThat(sleeps, empty());
        }
    }

    @Test
    void retries503OnPut() throws Exception {
        try (var fake = new FakeMailchimpServer(); var client = client(fake, 3)) {
            fake.on("PUT", "/3.0/lists/a/members/h", Response.json(503, "{}"), Response.json(200, "{}"));

            client.send("PUT", "/lists/a/members/h", null, java.util.Map.of());

            assertThat(fake.requests(), hasSize(2));
        }
    }

    @Test
    void negativeRetryAfterIsClamped() throws Exception {
        try (var fake = new FakeMailchimpServer(); var client = client(fake, 3)) {
            fake.on("GET", "/3.0/ping", Response.empty(429).withHeader("Retry-After", "-5"), Response.empty(429).withHeader("Retry-After", "-5"), Response.json(200, "{}"));

            client.send("GET", "/ping", null, null);

            assertThat(sleeps.getFirst(), equalTo(Duration.ZERO));
        }
    }

    @Test
    void emptyBodyPassesForPlainCallsButFailsWhereABodyIsRequired() throws Exception {
        try (var fake = new FakeMailchimpServer(); var client = client(fake, 0)) {
            fake.on("GET", "/3.0/ping", Response.empty(200));

            assertThat(client.send("GET", "/ping", null, null).isNull(), equalTo(true));
            var e = assertThrows(IllegalStateException.class, () -> client.sendForBody("GET", "/ping", null, null));
            assertThat(e.getMessage(), containsString("Empty response from Mailchimp API on GET /ping"));
        }
    }

    @Test
    void notFoundSaysWhatToCheck() throws Exception {
        try (var fake = new FakeMailchimpServer(); var client = client(fake, 0)) {
            fake.on("GET", "/3.0/lists/x", Response.json(404, "{\"title\":\"Resource Not Found\",\"status\":404}"));

            var e = assertThrows(MailchimpException.class, () -> client.send("GET", "/lists/x", null, null));
            assertThat(e.getMessage(), containsString("Check 'listId' / 'campaignId'"));
        }
    }

    @Test
    void invalidJsonOn2xxIsMailchimpException() throws Exception {
        try (var fake = new FakeMailchimpServer(); var client = client(fake, 0)) {
            fake.on("GET", "/3.0/ping", Response.json(200, "<html>secret-ish body</html>"));

            var e = assertThrows(MailchimpException.class, () -> client.send("GET", "/ping", null, null));

            assertThat(e.getStatus(), equalTo(200));
            assertThat(e.getMessage(), containsString("Invalid JSON response"));
            assertThat(e.getMessage().contains("secret-ish"), equalTo(false));
        }
    }
}

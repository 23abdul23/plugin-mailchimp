package io.kestra.plugin.mailchimp;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.mailchimp.account.Ping;

import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class MailchimpConnectionTest {
    @Inject
    private RunContextFactory runContextFactory;

    private Ping.PingBuilder<?, ?> task() {
        return Ping.builder();
    }

    @Test
    void apiKeyDerivesServer() throws Exception {
        var task = task().apiKey(Property.ofValue("abc-us19")).build();

        assertThat(MailchimpConnectionInterface.baseUrl(runContextFactory.of(), task), equalTo("https://us19.api.mailchimp.com/3.0"));
    }

    @Test
    void accessTokenNeedsServer() {
        var task = task().accessToken(Property.ofValue("tok")).build();

        var e = assertThrows(IllegalArgumentException.class, () -> MailchimpConnectionInterface.baseUrl(runContextFactory.of(), task));
        assertThat(e.getMessage(), containsString("server"));
    }

    @Test
    void apiKeyWithoutDashAndServerSaysSo() {
        var task = task().apiKey(Property.ofValue("nodash")).build();

        var e = assertThrows(IllegalArgumentException.class, () -> MailchimpConnectionInterface.baseUrl(runContextFactory.of(), task));
        assertThat(e.getMessage(), containsString("apiKey ending in"));
    }

    @Test
    void keyWithoutDataCenterSuffixNeverEchoesItsSecretPart() {
        var task = task().apiKey(Property.ofValue("md-TopSecretPart123")).build();

        var e = assertThrows(IllegalArgumentException.class, () -> MailchimpConnectionInterface.baseUrl(runContextFactory.of(), task));
        assertThat(e.getMessage(), containsString("data center suffix"));
        assertThat(e.getMessage(), not(containsString("TopSecretPart123")));
    }

    @Test
    void blankRequiredValueNamesTheProperty() {
        var e = assertThrows(IllegalArgumentException.class,
            () -> MailchimpConnectionInterface.required(runContextFactory.of(), Property.ofValue("  "), "listId"));
        assertThat(e.getMessage(), containsString("'listId' is required and must not be blank"));
    }

    @Test
    void outOfRangeRetriesOrTimeoutFailBeforeAnyRequest() {
        var tooMany = task().apiKey(Property.ofValue("abc-us19")).maxRetries(Property.ofValue(11)).build();
        var noTimeout = task().apiKey(Property.ofValue("abc-us19")).timeout(Property.ofValue(Duration.ZERO)).build();

        assertThat(assertThrows(IllegalArgumentException.class, () -> MailchimpConnectionInterface.client(runContextFactory.of(), tooMany)).getMessage(), containsString("maxRetries"));
        assertThat(assertThrows(IllegalArgumentException.class, () -> MailchimpConnectionInterface.client(runContextFactory.of(), noTimeout)).getMessage(), containsString("timeout"));
    }

    @Test
    void accessTokenWithServer() throws Exception {
        var task = task().accessToken(Property.ofValue("tok")).server(Property.ofValue("us6")).build();

        assertThat(MailchimpConnectionInterface.baseUrl(runContextFactory.of(), task), equalTo("https://us6.api.mailchimp.com/3.0"));
    }

    @Test
    void bothOrNeitherCredentialFails() {
        var both = task().apiKey(Property.ofValue("abc-us19")).accessToken(Property.ofValue("tok")).build();
        var neither = task().build();

        assertThat(assertThrows(IllegalArgumentException.class, () -> MailchimpConnectionInterface.client(runContextFactory.of(), both)).getMessage(), containsString("exactly one"));
        assertThat(assertThrows(IllegalArgumentException.class, () -> MailchimpConnectionInterface.client(runContextFactory.of(), neither)).getMessage(), containsString("exactly one"));
    }

    @Test
    void maliciousServerRejectedBeforeAnyRequest() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var task = task().apiKey(Property.ofValue("abc-us19")).server(Property.ofValue("evil.com/x#")).baseUrl(Property.ofValue(fake.baseUrl())).build();

            var e = assertThrows(IllegalArgumentException.class, () -> MailchimpConnectionInterface.client(runContextFactory.of(), task));
            assertThat(e.getMessage(), containsString("server"));
            assertThat(fake.requests(), empty());
        }
    }

    @Test
    void loopbackBaseUrlIsRefusedUnlessTheOperatorOptsIn() throws Exception {
        var loopback = task().apiKey(Property.ofValue("abc-us19")).baseUrl(Property.ofValue("http://localhost:1234")).build();
        var before = System.getProperty(MailchimpConnectionInterface.ALLOW_LOCAL_PROPERTY);
        try {
            System.clearProperty(MailchimpConnectionInterface.ALLOW_LOCAL_PROPERTY);
            assertThat(assertThrows(IllegalArgumentException.class, () -> MailchimpConnectionInterface.baseUrl(runContextFactory.of(), loopback)).getMessage(), containsString("baseUrl"));

            System.setProperty(MailchimpConnectionInterface.ALLOW_LOCAL_PROPERTY, "true");
            assertThat(MailchimpConnectionInterface.baseUrl(runContextFactory.of(), loopback), equalTo("http://localhost:1234"));
        } finally {
            if (before == null) {
                System.clearProperty(MailchimpConnectionInterface.ALLOW_LOCAL_PROPERTY);
            } else {
                System.setProperty(MailchimpConnectionInterface.ALLOW_LOCAL_PROPERTY, before);
            }
        }
    }

    @Test
    void baseUrlOutsideMailchimpIsRejected() {
        for (var url : new String[] {"https://evil.com", "https://api.mailchimp.com.evil.com", "https://user@evil.com/x", "https://evil.com/.api.mailchimp.com", "https://evilapi.mailchimp.com"}) {
            var task = task().apiKey(Property.ofValue("abc-us19")).baseUrl(Property.ofValue(url)).build();
            assertThat(url, assertThrows(IllegalArgumentException.class, () -> MailchimpConnectionInterface.baseUrl(runContextFactory.of(), task)).getMessage(), containsString("baseUrl"));
        }
    }

    @Test
    void baseUrlOnMailchimpIsAccepted() throws Exception {
        var task = task().apiKey(Property.ofValue("abc-us19")).baseUrl(Property.ofValue("https://us19.api.mailchimp.com/3.0/")).build();
        assertThat(MailchimpConnectionInterface.baseUrl(runContextFactory.of(), task), equalTo("https://us19.api.mailchimp.com/3.0"));
    }

    @Test
    void toStringDoesNotLeakSecrets() {
        var task = task().apiKey(Property.ofValue("abc-supersecret-us19")).accessToken(Property.ofValue("tok-supersecret")).build();

        assertThat(task.toString(), not(containsString("supersecret")));
    }

    @Test
    void sendsBearerAuthorization() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", "/3.0/ping", FakeMailchimpServer.Response.json(200, "{\"health_status\":\"ok\"}"));
            var task = task().apiKey(Property.ofValue("abc-us19")).baseUrl(Property.ofValue(fake.baseUrl())).build();

            try (var client = MailchimpConnectionInterface.client(runContextFactory.of(), task)) {
                client.send("GET", "/ping", null, null);
            }

            assertThat(fake.requests().getFirst().header("authorization"), equalTo("Bearer abc-us19"));
        }
    }

    @Test
    void crossHostRedirectIsNotFollowed() throws Exception {
        try (var fake = new FakeMailchimpServer(); var other = new FakeMailchimpServer()) {
            other.on("GET", "/3.0/ping", FakeMailchimpServer.Response.json(200, "{}"));
            fake.on("GET", "/3.0/ping", FakeMailchimpServer.Response.redirect(other.baseUrl() + "/ping"));
            var task = task().apiKey(Property.ofValue("abc-us19")).baseUrl(Property.ofValue(fake.baseUrl())).build();

            try (var client = MailchimpConnectionInterface.client(runContextFactory.of(), task)) {
                var e = assertThrows(MailchimpException.class, () -> client.send("GET", "/ping", null, null));
                assertThat(e.getStatus(), equalTo(302));
            }

            assertThat(other.requests(), empty());
        }
    }

    @Test
    void trailingWhitespaceOnApiKeyIsTrimmed() throws Exception {
        for (var suffix : new String[]{"\n", "\r\n", " "}) {
            try (var fake = new FakeMailchimpServer()) {
                fake.on("GET", "/3.0/ping", FakeMailchimpServer.Response.json(200, "{}"));
                var task = task().apiKey(Property.ofValue("abc-us19" + suffix)).baseUrl(Property.ofValue(fake.baseUrl())).build();

                MailchimpConnectionInterface.client(runContextFactory.of(), task).send("GET", "/ping", null, null);

                assertThat(fake.requests().getFirst().header("authorization"), equalTo("Bearer abc-us19"));
            }
        }
    }

    @Test
    void trailingNewlineOnAccessTokenAndServerIsTrimmed() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", "/3.0/ping", FakeMailchimpServer.Response.json(200, "{}"));
            var task = task().accessToken(Property.ofValue("tok\r\n")).server(Property.ofValue("us6\n")).baseUrl(Property.ofValue(fake.baseUrl() + "\n")).build();

            MailchimpConnectionInterface.client(runContextFactory.of(), task).send("GET", "/ping", null, null);

            assertThat(fake.requests().getFirst().header("authorization"), equalTo("Bearer tok"));
        }
        var noBase = task().accessToken(Property.ofValue("tok")).server(Property.ofValue("us6\n")).build();
        assertThat(MailchimpConnectionInterface.baseUrl(runContextFactory.of(), noBase), equalTo("https://us6.api.mailchimp.com/3.0"));
    }

    @Test
    void apiKeyWithTrailingNewlineDerivesServer() throws Exception {
        var task = task().apiKey(Property.ofValue("abc-us19\n")).build();

        assertThat(MailchimpConnectionInterface.baseUrl(runContextFactory.of(), task), equalTo("https://us19.api.mailchimp.com/3.0"));
    }

    @Test
    void embeddedNewlineFailsWithoutLeakingTheSecret() {
        var apiKey = task().apiKey(Property.ofValue("SECRETVALUE\nrest-us19")).build();
        var token = task().accessToken(Property.ofValue("SECRETVALUE\nrest")).server(Property.ofValue("us6")).build();

        var e1 = assertThrows(IllegalArgumentException.class, () -> MailchimpConnectionInterface.client(runContextFactory.of(), apiKey));
        var e2 = assertThrows(IllegalArgumentException.class, () -> MailchimpConnectionInterface.client(runContextFactory.of(), token));
        assertThat(e1.getMessage(), containsString("apiKey"));
        assertThat(e1.getMessage(), not(containsString("SECRETVALUE")));
        assertThat(e2.getMessage(), containsString("accessToken"));
        assertThat(e2.getMessage(), not(containsString("SECRETVALUE")));
    }

    @Test
    void clientNeverLeaksTokenInHeaderError() {
        var e = assertThrows(IllegalArgumentException.class,
            () -> new MailchimpClient(runContextFactory.of(), "http://localhost:1", "SECRETVALUE\nrest", 0, Duration.ofSeconds(1), ms -> { }));
        assertThat(e.getMessage(), not(containsString("SECRETVALUE")));
    }
}

package io.kestra.plugin.mailchimp;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;

/** A task and a trigger have different base classes, so both declare the connection fields and share the client building here. */
public interface MailchimpConnectionInterface {
    Pattern SERVER = Pattern.compile("^[a-z]{2,6}[0-9]{1,3}$");
    int MAX_RETRIES = 10;
    String ALLOW_LOCAL_PROPERTY = "kestra.plugin.mailchimp.allow-local-base-url";

    Property<String> getApiKey();

    Property<String> getAccessToken();

    Property<String> getServer();

    Property<String> getBaseUrl();

    Property<Integer> getMaxRetries();

    Property<Duration> getTimeout();

    /** The one credential of a connection, rendered once. */
    record Credentials(Optional<String> apiKey, Optional<String> accessToken) {
        String token() {
            return apiKey.orElseGet(accessToken::get);
        }
    }

    static MailchimpClient client(RunContext runContext, MailchimpConnectionInterface connection) throws Exception {
        var credentials = credentials(runContext, connection);
        var baseUrl = baseUrl(runContext, connection, credentials);
        var rMaxRetries = runContext.render(connection.getMaxRetries()).as(Integer.class).orElse(3);
        if (rMaxRetries < 0 || rMaxRetries > MAX_RETRIES) {
            throw new IllegalArgumentException("'maxRetries' must be between 0 and " + MAX_RETRIES);
        }
        var rTimeout = runContext.render(connection.getTimeout()).as(Duration.class).orElse(Duration.ofSeconds(120));
        if (rTimeout.isZero() || rTimeout.isNegative()) {
            throw new IllegalArgumentException("'timeout' must be positive");
        }
        return new MailchimpClient(runContext, baseUrl, credentials.token(), rMaxRetries, rTimeout, Thread::sleep);
    }

    /** Renders a required text property; a blank value fails with a message that names the property. */
    static String required(RunContext runContext, Property<String> property, String name) throws Exception {
        return runContext.render(property).as(String.class).filter(s -> !s.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("'" + name + "' is required and must not be blank"));
    }

    private static Credentials credentials(RunContext runContext, MailchimpConnectionInterface connection) throws Exception {
        var apiKey = rendered(runContext, connection.getApiKey(), "apiKey", true);
        var accessToken = rendered(runContext, connection.getAccessToken(), "accessToken", true);
        if (apiKey.isPresent() == accessToken.isPresent()) {
            throw new IllegalArgumentException("Set exactly one of 'apiKey' or 'accessToken'");
        }
        return new Credentials(apiKey, accessToken);
    }

    /** Renders and trims a connection property (a secret often ends with a newline). A credential that still holds whitespace or control characters is rejected; the message names the property, never the value. */
    private static Optional<String> rendered(RunContext runContext, Property<String> property, String name, boolean strict) throws Exception {
        var rValue = runContext.render(property).as(String.class).map(String::trim).filter(s -> !s.isEmpty());
        if (rValue.isPresent() && strict && rValue.get().chars().anyMatch(c -> c < 0x21 || c == 0x7f)) {
            throw new IllegalArgumentException("'" + name + "' contains whitespace or control characters; check for a stray newline in the secret");
        }
        return rValue;
    }

    /** API root, including the version segment, e.g. {@code https://us19.api.mailchimp.com/3.0}. */
    static String baseUrl(RunContext runContext, MailchimpConnectionInterface connection) throws Exception {
        return baseUrl(runContext, connection, credentials(runContext, connection));
    }

    private static String baseUrl(RunContext runContext, MailchimpConnectionInterface connection, Credentials credentials) throws Exception {
        var explicitServer = rendered(runContext, connection.getServer(), "server", false);
        if (explicitServer.isPresent() && !SERVER.matcher(explicitServer.get()).matches()) {
            throw new IllegalArgumentException("Invalid 'server' '" + explicitServer.get() + "': expected a Mailchimp data center such as 'us19'");
        }

        var rBaseUrl = rendered(runContext, connection.getBaseUrl(), "baseUrl", false);
        if (rBaseUrl.isPresent()) {
            var url = rBaseUrl.get().replaceAll("/+$", "");
            URI uri;
            try {
                uri = URI.create(url);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("'baseUrl' is not a valid URL", e);
            }
            var host = uri.getHost();
            // loopback http is for a local proxy or mock; only the operator can switch it on, never a flow author
            var local = Boolean.getBoolean(ALLOW_LOCAL_PROPERTY) && "http".equals(uri.getScheme()) && ("localhost".equals(host) || "127.0.0.1".equals(host));
            var mailchimp = "https".equals(uri.getScheme()) && uri.getUserInfo() == null && host != null && host.endsWith(".api.mailchimp.com");
            if (!local && !mailchimp) {
                throw new IllegalArgumentException("'baseUrl' must be an https '*.api.mailchimp.com' host: the credential is sent to it");
            }
            return url;
        }

        var server = explicitServer
            .or(() -> credentials.apiKey().filter(k -> k.contains("-")).map(k -> k.substring(k.lastIndexOf('-') + 1)))
            .orElseThrow(() -> new IllegalArgumentException("'server' is required: use an apiKey ending in '-<data center>' (for example '-us19') or set 'server' (see https://login.mailchimp.com/oauth2/metadata for OAuth tokens)"));
        if (!SERVER.matcher(server).matches()) {
            // never echo a value taken from the key: a key without a data center suffix is all secret
            throw new IllegalArgumentException("'apiKey' does not end in a valid data center suffix (such as '-us19'); set 'server' explicitly");
        }
        return "https://" + server + ".api.mailchimp.com/3.0";
    }
}

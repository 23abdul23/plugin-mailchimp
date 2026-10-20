package io.kestra.plugin.mailchimp;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;

/**
 * A task and a trigger have different base classes, so both declare the connection fields
 * and share only the client-building logic through this interface.
 */
public interface MailchimpConnectionInterface {
    Pattern SERVER = Pattern.compile("^[a-z]{2,6}[0-9]{1,3}$");
    int MAX_RETRIES = 10;

    Property<String> getApiKey();

    Property<String> getAccessToken();

    Property<String> getServer();

    Property<String> getBaseUrl();

    Property<Integer> getMaxRetries();

    Property<Duration> getTimeout();

    static MailchimpClient client(RunContext runContext, MailchimpConnectionInterface connection) throws Exception {
        var baseUrl = baseUrl(runContext, connection);
        var token = token(runContext, connection);
        var maxRetries = runContext.render(connection.getMaxRetries()).as(Integer.class).orElse(3);
        if (maxRetries < 0) {
            throw new IllegalArgumentException("'maxRetries' must be >= 0");
        }
        if (maxRetries > MAX_RETRIES) {
            throw new IllegalArgumentException("'maxRetries' must be <= " + MAX_RETRIES);
        }
        var timeout = runContext.render(connection.getTimeout()).as(Duration.class).orElse(Duration.ofSeconds(120));
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("'timeout' must be positive");
        }
        return new MailchimpClient(runContext, baseUrl, token, maxRetries, timeout, Thread::sleep);
    }

    /** Renders a required text property; a blank value fails with a message that names the property. */
    static String required(RunContext runContext, Property<String> property, String name) throws Exception {
        return runContext.render(property).as(String.class).filter(s -> !s.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("'" + name + "' is required and must not be blank"));
    }

    private static String token(RunContext runContext, MailchimpConnectionInterface connection) throws Exception {
        var apiKey = rendered(runContext, connection.getApiKey(), "apiKey", true);
        var accessToken = rendered(runContext, connection.getAccessToken(), "accessToken", true);
        if (apiKey.isPresent() == accessToken.isPresent()) {
            throw new IllegalArgumentException("Set exactly one of 'apiKey' or 'accessToken'");
        }
        return apiKey.orElseGet(accessToken::get);
    }

    /**
     * Renders and trims a connection property (a secret often ends with a newline). A credential or server that still
     * holds whitespace or control characters is rejected; the message names the property, never the value.
     */
    private static Optional<String> rendered(RunContext runContext, Property<String> property, String name, boolean strict) throws Exception {
        var value = runContext.render(property).as(String.class).map(String::trim).filter(s -> !s.isEmpty());
        if (value.isPresent() && strict && value.get().chars().anyMatch(c -> c < 0x21 || c == 0x7f)) {
            throw new IllegalArgumentException("'" + name + "' contains whitespace or control characters; check for a stray newline in the secret");
        }
        return value;
    }

    /** API root, including the version segment, e.g. {@code https://us19.api.mailchimp.com/3.0}. */
    static String baseUrl(RunContext runContext, MailchimpConnectionInterface connection) throws Exception {
        token(runContext, connection); // validates "exactly one credential" before anything else

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
                throw new IllegalArgumentException("'baseUrl' is not a valid URL");
            }
            var host = uri.getHost();
            var local = "http".equals(uri.getScheme()) && ("localhost".equals(host) || "127.0.0.1".equals(host));
            var mailchimp = "https".equals(uri.getScheme()) && uri.getUserInfo() == null && host != null && host.endsWith(".api.mailchimp.com");
            if (!local && !mailchimp) {
                throw new IllegalArgumentException("'baseUrl' must be an https '*.api.mailchimp.com' host (or http on localhost / 127.0.0.1 for tests): the credential is sent to it");
            }
            return url;
        }

        var apiKey = rendered(runContext, connection.getApiKey(), "apiKey", true);
        var server = explicitServer
            .or(() -> apiKey.filter(k -> k.contains("-")).map(k -> k.substring(k.lastIndexOf('-') + 1)))
            .orElseThrow(() -> new IllegalArgumentException("'server' is required: use an apiKey ending in '-<data center>' (for example '-us19') or set 'server' (see https://login.mailchimp.com/oauth2/metadata for OAuth tokens)"));
        if (!SERVER.matcher(server).matches()) {
            // never echo a value taken from the key: a key without a data center suffix is all secret
            throw new IllegalArgumentException("'apiKey' does not end in a valid data center suffix (such as '-us19'); set 'server' explicitly");
        }
        return "https://" + server + ".api.mailchimp.com/3.0";
    }
}

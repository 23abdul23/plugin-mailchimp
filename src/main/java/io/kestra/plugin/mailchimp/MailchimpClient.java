package io.kestra.plugin.mailchimp;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.configurations.BearerAuthConfiguration;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.http.client.configurations.TimeoutConfiguration;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;

/** Mailchimp Marketing API client on Kestra's HttpClient: bearer auth, no redirects, backoff retries, errors as {@link MailchimpException}. POST bodies are one-shot streams because Apache would otherwise replay them once after a 429/503. */
public class MailchimpClient implements Closeable {
    /** Injected so tests do not actually sleep. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private static final Duration BASE_DELAY = Duration.ofSeconds(1);
    private static final Duration MAX_DELAY = Duration.ofSeconds(30);
    private static final Set<String> IDEMPOTENT = Set.of("GET", "PUT");

    private final HttpClient http;
    private final String baseUrl;
    private final int maxRetries;
    private final Sleeper sleeper;
    private final Logger logger;

    public MailchimpClient(RunContext runContext, String baseUrl, String token, int maxRetries, Duration timeout, Sleeper sleeper) throws Exception {
        if (token.chars().anyMatch(c -> c < 0x21 || c == 0x7f)) {
            // never echo the value: it is the secret
            throw new IllegalArgumentException("Invalid credential: it cannot be sent as an HTTP header value");
        }
        this.baseUrl = baseUrl;
        this.maxRetries = maxRetries;
        this.sleeper = sleeper;
        this.logger = runContext.logger();
        this.http = HttpClient.builder()
            .runContext(runContext)
            .configuration(HttpConfiguration.builder()
                .followRedirects(Property.ofValue(false)) // a redirect to another host must never receive the credential
                .allowFailed(Property.ofValue(true)) // 4xx/5xx come back as responses; errors are mapped here
                .timeout(TimeoutConfiguration.builder()
                    .connectTimeout(Property.ofValue(Duration.ofSeconds(10)))
                    .readIdleTimeout(Property.ofValue(timeout))
                    .build())
                .auth(BearerAuthConfiguration.builder().token(Property.ofValue(token)).build())
                .build())
            .build();
    }

    /** @param path relative to the API root, e.g. {@code /lists/abc/members} */
    public JsonNode send(String method, String path, Map<String, String> query, Object body) throws Exception {
        var uri = uri(path, query);
        var retryable = IDEMPOTENT.contains(method.toUpperCase());
        var json = body != null ? JacksonMapper.ofJson().writeValueAsString(body) : retryable ? null : "{}";

        for (var attempt = 0; ; attempt++) {
            HttpResponse<String> response;
            try {
                response = http.request(request(method, uri, json, retryable), String.class);
            } catch (HttpClientException | RuntimeException e) {
                // timeouts and connection errors: only safe to repeat for idempotent calls
                if (!hasIoCause(e)) {
                    throw e;
                }
                if (retryable && attempt < maxRetries) {
                    backoff(method, path, "I/O error: " + e.getMessage(), attempt, null);
                    continue;
                }
                throw new IOException(e.getMessage(), e);
            }

            var status = response.getStatus().getCode();
            if (status >= 200 && status < 300) {
                try {
                    return parse(response.getBody());
                } catch (IOException e) {
                    var failure = new MailchimpException(status, "Invalid JSON response", "Mailchimp returned a non-JSON body for " + method + " " + path + "; check 'baseUrl' and the proxy settings", null);
                    failure.initCause(e);
                    throw failure;
                }
            }
            if (attempt < maxRetries && (status == 429 || (status >= 500 && retryable))) {
                backoff(method, path, "HTTP " + status, attempt, response.getHeaders().firstValue("Retry-After").orElse(null));
                continue;
            }
            throw error(status, response);
        }
    }

    /** Sleeps before the next attempt and says so in the logs, so a throttled task does not look hung. */
    private void backoff(String method, String path, String cause, int attempt, String retryAfter) throws InterruptedException {
        var wait = delay(attempt, retryAfter);
        logger.warn("Mailchimp {} {} failed ({}), retry {}/{} in {} ms", method, path, cause, attempt + 1, maxRetries, wait.toMillis());
        sleeper.sleep(wait);
    }

    /** Like {@link #send} for calls that must answer with a body: an empty 2xx answer fails instead of passing as success. */
    public JsonNode sendForBody(String method, String path, Map<String, String> query, Object body) throws Exception {
        var response = send(method, path, query, body);
        if (response.isMissingNode() || response.isNull()) {
            throw new IllegalStateException("Empty response from Mailchimp API on " + method + " " + path + "; check 'baseUrl' and the proxy settings");
        }
        return response;
    }

    /** Built again for every attempt; a POST gets a one-shot entity (never replayed by Apache) and sends {@code {}} when it has no payload. */
    private static HttpRequest request(String method, URI uri, String json, boolean replayable) {
        var builder = HttpRequest.builder().method(method).uri(uri).addHeader("Accept", "application/json");
        if (json != null) {
            builder.body(replayable
                ? HttpRequest.StringRequestBody.builder().content(json).contentType("application/json").build()
                : oneShot(json));
        }
        return builder.build();
    }

    /** A stream entity Apache cannot replay, but with a known length so the POST carries Content-Length, not chunked encoding. */
    private static HttpRequest.RequestBody oneShot(String json) {
        var bytes = json.getBytes(StandardCharsets.UTF_8);
        return HttpRequest.PassthroughRequestBody.builder()
            .entity(new InputStreamEntity(new ByteArrayInputStream(bytes), bytes.length, ContentType.APPLICATION_JSON))
            .contentType("application/json")
            .build();
    }

    private static boolean hasIoCause(Throwable e) {
        for (var t = e; t != null; t = t.getCause()) {
            if (t instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    /** Calls {@code GET path} page by page and hands each whole page (even an empty one) to {@code pageHandler}; stops when it returns {@code false}, on a short page, or once {@code total_items} were read. */
    public void getPagedWhile(String path, Map<String, String> query, int pageSize, Predicate<JsonNode> pageHandler, String arrayField) throws Exception {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("'pageSize' must be > 0");
        }
        var fetched = 0L;
        for (long offset = 0; ; offset += pageSize) {
            var q = new LinkedHashMap<String, String>(query == null ? Map.of() : query);
            q.put("count", String.valueOf(pageSize));
            q.put("offset", String.valueOf(offset));

            var page = send("GET", path, q, null);
            var items = page.path(arrayField);
            if (!pageHandler.test(page) || !items.isArray() || items.isEmpty()) {
                return;
            }
            fetched += items.size();

            // total_items can shrink while we paginate, so it is re-read on every page
            if (items.size() < pageSize || (page.has("total_items") && fetched >= page.get("total_items").asLong())) {
                return;
            }
        }
    }

    /** Percent-encodes one path segment (list id, campaign id, member hash...) so it cannot alter the request path. */
    public static String segment(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Path segment must not be blank");
        }
        if (value.contains("/") || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException("Invalid path segment '" + value + "'");
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private URI uri(String path, Map<String, String> query) {
        var sb = new StringBuilder(baseUrl).append(path);
        if (query != null && !query.isEmpty()) {
            sb.append('?');
            var first = true;
            for (var e : query.entrySet()) {
                sb.append(first ? "" : "&").append(enc(e.getKey())).append('=').append(enc(e.getValue()));
                first = false;
            }
        }
        return URI.create(sb.toString());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static JsonNode parse(String body) throws IOException {
        return body == null || body.isBlank() ? NullNode.getInstance() : JacksonMapper.ofJson().readTree(body);
    }

    /** Exponential backoff (1 s, factor 2, cap 30 s) with jitter in [50%, 100%]; Retry-After wins when present. */
    private static Duration delay(int attempt, String retryAfter) {
        if (retryAfter != null) {
            try {
                return min(Duration.ofSeconds(Math.max(0, Long.parseLong(retryAfter.trim()))), MAX_DELAY);
            } catch (NumberFormatException ignored) {
                // HTTP-date form is not used by Mailchimp; fall back to backoff
            }
        }
        var exp = min(BASE_DELAY.multipliedBy(1L << Math.min(attempt, 10)), MAX_DELAY);
        return Duration.ofMillis((long) (exp.toMillis() * ThreadLocalRandom.current().nextDouble(0.5, 1.0)));
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static MailchimpException error(int status, HttpResponse<String> response) {
        String title = null;
        String detail = null;
        var fields = new LinkedHashMap<String, String>();
        try {
            var json = parse(response.getBody());
            title = json.path("title").asText(null);
            detail = json.path("detail").asText(null);
            for (var err : json.path("errors")) {
                fields.put(err.path("field").asText("?"), err.path("message").asText(""));
            }
        } catch (IOException ignored) {
            // non-JSON body (Mailchimp may send none on 429/403): status text is used instead
        }
        if (title == null || title.isBlank()) {
            title = reason(status);
        }
        if (status == 401) {
            detail = "Mailchimp credentials invalid or revoked" + (detail == null || detail.isBlank() ? "" : " (" + detail + ")");
        } else if (status >= 300 && status < 400) {
            detail = "redirect to '" + response.getHeaders().firstValue("Location").orElse("?") + "' not followed";
        } else if (status == 403) {
            detail = (detail == null || detail.isBlank() ? "" : detail + ". ") + "Check the key's account and permissions";
        } else if (status == 404) {
            detail = (detail == null || detail.isBlank() ? "" : detail + ". ") + "Check 'listId' / 'campaignId' and that the key belongs to the same account";
        }
        return new MailchimpException(status, title, detail, fields);
    }

    private static String reason(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> status >= 300 && status < 400 ? "Redirect" : "HTTP error";
        };
    }

    @Override
    public void close() throws IOException {
        http.close();
    }
}

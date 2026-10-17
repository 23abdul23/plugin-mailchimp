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
import java.util.function.Consumer;
import java.util.function.Predicate;

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



/**
 * Thin Mailchimp Marketing API client on Kestra's own {@link HttpClient} (platform proxy, TLS and, from Kestra 2.0, the
 * HTTP allow/deny list): bearer auth, no redirect following, retry with exponential backoff and error mapping to
 * {@link MailchimpException}.
 *
 * <p>Kestra's client sits on Apache HttpClient, whose built-in retry cannot be configured from here: it replays a
 * request once, immediately, after a 429 or 503 when the request body can be re-read (verified against core 1.3.39). For
 * a POST that would risk a duplicate send, so POST bodies are one-shot streams (see {@link #request}); GET and PUT may be
 * replayed, they are idempotent. Our own retry rules below sit on top of that.
 */
public class MailchimpClient implements Closeable {
    /** Injected so tests do not actually sleep. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    static final Duration BASE_DELAY = Duration.ofSeconds(1);
    static final Duration MAX_DELAY = Duration.ofSeconds(30);
    private static final Set<String> IDEMPOTENT = Set.of("GET", "PUT");

    private final HttpClient http;
    private final String baseUrl;
    private final int maxRetries;
    private final Sleeper sleeper;

    public MailchimpClient(RunContext runContext, String baseUrl, String token, int maxRetries, Duration timeout, Sleeper sleeper) throws Exception {
        if (token.chars().anyMatch(c -> c < 0x21 || c == 0x7f)) {
            // never echo the value: it is the secret
            throw new IllegalArgumentException("Invalid credential: it cannot be sent as an HTTP header value");
        }
        this.baseUrl = baseUrl;
        this.maxRetries = maxRetries;
        this.sleeper = sleeper;
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

        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> response;
            try {
                response = http.request(request(method, uri, json, retryable), String.class);
            } catch (HttpClientException | RuntimeException e) {
                // timeouts and connection errors: only safe to repeat for idempotent calls
                if (!hasIoCause(e)) {
                    throw e;
                }
                if (retryable && attempt < maxRetries) {
                    sleeper.sleep(delay(attempt, null));
                    continue;
                }
                throw new IOException(e.getMessage(), e);
            }

            var status = response.getStatus().getCode();
            if (status >= 200 && status < 300) {
                try {
                    return parse(response.getBody());
                } catch (IOException e) {
                    throw new MailchimpException(status, "Invalid JSON response", null, null);
                }
            }
            if (attempt < maxRetries && (status == 429 || (status >= 500 && retryable))) {
                sleeper.sleep(delay(attempt, response.getHeaders().firstValue("Retry-After").orElse(null)));
                continue;
            }
            throw error(status, response);
        }
    }

    /**
     * Built again for every attempt. A POST gets a one-shot stream entity: Apache only replays a request whose entity
     * can be re-read, so this switches its built-in replay (once, after a 429/503) off and a send or batch POST is
     * never repeated behind our back. A POST without payload sends {@code {}} so that it has an entity too.
     */
    private static HttpRequest request(String method, URI uri, String json, boolean replayable) {
        var builder = HttpRequest.builder().method(method).uri(uri).addHeader("Accept", "application/json");
        if (json != null) {
            builder.body(replayable
                ? HttpRequest.StringRequestBody.builder().content(json).contentType("application/json").build()
                : HttpRequest.InputStreamRequestBody.builder().content(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))).contentType("application/json").build());
        }
        return builder.build();
    }

    private static boolean hasIoCause(Throwable e) {
        for (var t = e; t != null; t = t.getCause()) {
            if (t instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Calls {@code GET path} page by page ({@code count}/{@code offset}) and hands each page's {@code arrayField}
     * array to {@code pageHandler}. Stops on a short page or once {@code total_items} items were read.
     */
    public void getPaged(String path, Map<String, String> query, int pageSize, Consumer<JsonNode> pageHandler, String arrayField) throws Exception {
        getPagedWhile(path, query, pageSize, page -> {
            var items = page.path(arrayField);
            if (items.isArray() && !items.isEmpty()) {
                pageHandler.accept(items);
            }
            return true;
        }, arrayField);
    }

    /**
     * Like {@link #getPaged} but hands the whole page (so callers can read {@code total_items}) to {@code pageHandler},
     * including an empty one, and stops as soon as it returns {@code false} so no further page is requested.
     */
    public void getPagedWhile(String path, Map<String, String> query, int pageSize, Predicate<JsonNode> pageHandler, String arrayField) throws Exception {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("'pageSize' must be > 0");
        }
        long fetched = 0;
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
    static Duration delay(int attempt, String retryAfter) {
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

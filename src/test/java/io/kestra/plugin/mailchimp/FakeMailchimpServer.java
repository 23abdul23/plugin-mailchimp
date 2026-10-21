package io.kestra.plugin.mailchimp;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.ResponseTransformerV2;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.request;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * The Mailchimp API stand-in of the tests, on WireMock: scripted answers per METHOD + path are WireMock scenarios (served
 * in order, the last one repeats), computed answers are a response transformer, and every request is recorded by WireMock.
 */
public class FakeMailchimpServer implements AutoCloseable {
    static {
        // the plugin only accepts a loopback baseUrl when its operator opts in
        System.setProperty("kestra.plugin.mailchimp.allow-local-base-url", "true");
    }

    public record Response(int status, String body, Map<String, String> headers, long delayMillis) {
        public Response(int status, String body, Map<String, String> headers) {
            this(status, body, headers, 0);
        }

        public static Response json(int status, String body) {
            return new Response(status, body, Map.of("Content-Type", "application/json"));
        }

        public static Response empty(int status) {
            return new Response(status, "", Map.of());
        }

        public static Response redirect(String location) {
            return new Response(302, "", Map.of("Location", location));
        }

        /** Answers only after the given delay (to provoke client timeouts). */
        public Response delayed(long millis) {
            return new Response(status, body, headers, millis);
        }

        public Response withHeader(String name, String value) {
            var h = new HashMap<>(headers);
            h.put(name, value);
            return new Response(status, body, h, delayMillis);
        }
    }

    public record Recorded(String method, String path, String query, Map<String, String> headers, String body) {
        public String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    private static final String HANDLER = "mailchimp-handler";

    private final WireMockServer server;
    private final Map<String, Function<Recorded, Response>> handlers = new ConcurrentHashMap<>();
    private final Map<String, Integer> scripted = new HashMap<>();

    public FakeMailchimpServer() {
        server = new WireMockServer(options().dynamicPort().bindAddress("127.0.0.1").extensions(new HandlerTransformer()));
        server.start();
        server.stubFor(any(anyUrl()).atPriority(10).willReturn(notScripted()));
    }

    private static ResponseDefinitionBuilder notScripted() {
        return aResponse().withStatus(404).withHeader("Content-Type", "application/json").withBody("{\"title\":\"Not Scripted\",\"status\":404}");
    }

    private static ResponseDefinitionBuilder answer(Response r) {
        var builder = aResponse().withStatus(r.status());
        r.headers().forEach(builder::withHeader);
        if (!r.body().isEmpty()) {
            builder.withBody(r.body());
        }
        if (r.delayMillis() > 0) {
            builder.withFixedDelay((int) r.delayMillis());
        }
        return builder;
    }

    private static MappingBuilder match(String method, String path) {
        return request(method, urlPathEqualTo(path));
    }

    /** Scripts responses for METHOD + path; sequences are served in order, the last one then repeats. */
    public synchronized FakeMailchimpServer on(String method, String path, Response... responses) {
        var key = method + " " + path;
        var done = scripted.getOrDefault(key, 0);
        if (done > 0) {
            // the old tail (which repeated the last answer) gives way to the new answers
            server.removeStubMapping(server.getStubMappings().stream()
                .filter(m -> key.equals(m.getScenarioName()) && String.valueOf(done).equals(m.getRequiredScenarioState())).findFirst().orElseThrow());
        }
        for (var i = 0; i < responses.length; i++) {
            var n = done + i;
            server.stubFor(match(method, path).atPriority(5).inScenario(key)
                .whenScenarioStateIs(n == 0 ? Scenario.STARTED : String.valueOf(n)).willSetStateTo(String.valueOf(n + 1))
                .willReturn(answer(responses[i])));
        }
        var total = done + responses.length;
        // after the last scripted answer the scenario sits in state `total`: keep answering with that last one
        server.stubFor(match(method, path).atPriority(5).inScenario(key).whenScenarioStateIs(String.valueOf(total))
            .willReturn(answer(responses[responses.length - 1])));
        scripted.put(key, total);
        return this;
    }

    /** Answers METHOD + path by computing the response from the request (takes precedence over scripts). */
    public FakeMailchimpServer handle(String method, String path, Function<Recorded, Response> handler) {
        handlers.put(method + " " + path, handler);
        server.stubFor(match(method, path).atPriority(1).willReturn(aResponse().withTransformers(HANDLER)));
        return this;
    }

    public String url() {
        return "http://localhost:" + server.port();
    }

    /** Base URL as the client expects it (includes the API version segment). */
    public String baseUrl() {
        return url() + "/3.0";
    }

    public List<Recorded> requests() {
        var recorded = new ArrayList<Recorded>();
        for (var event : server.getAllServeEvents()) {
            recorded.addFirst(record(event));
        }
        return recorded;
    }

    private static Recorded record(ServeEvent event) {
        var req = event.getRequest();
        var uri = URI.create(req.getUrl());
        var headers = new LinkedHashMap<String, String>();
        for (HttpHeader h : req.getHeaders().all()) {
            headers.put(h.key().toLowerCase(), h.firstValue());
        }
        return new Recorded(req.getMethod().getName(), uri.getRawPath(), uri.getRawQuery(), headers, req.getBodyAsString());
    }

    @Override
    public void close() {
        server.stop();
    }

    private class HandlerTransformer implements ResponseTransformerV2 {
        @Override
        public com.github.tomakehurst.wiremock.http.Response transform(com.github.tomakehurst.wiremock.http.Response response, ServeEvent event) {
            var recorded = record(event);
            var handled = handlers.get(recorded.method() + " " + recorded.path()).apply(recorded);
            var headers = new ArrayList<HttpHeader>();
            handled.headers().forEach((k, v) -> headers.add(new HttpHeader(k, v)));
            return com.github.tomakehurst.wiremock.http.Response.response()
                .status(handled.status())
                .headers(new HttpHeaders(headers))
                .body(handled.body())
                .build();
        }

        @Override
        public String getName() {
            return HANDLER;
        }

        @Override
        public boolean applyGlobally() {
            return false;
        }
    }
}

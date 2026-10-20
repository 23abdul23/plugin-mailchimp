package io.kestra.plugin.mailchimp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.runners.RunContextFactory;

import io.kestra.plugin.mailchimp.FakeMailchimpServer.Response;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;

@KestraTest
class MailchimpClientPaginationTest {
    @Inject
    private RunContextFactory runContextFactory;

    private static final String PATH = "/3.0/lists/a/members";

    private static String page(int from, int n, int total) {
        var members = IntStream.range(from, from + n).mapToObj(i -> "{\"id\":\"m" + i + "\"}").collect(Collectors.joining(","));
        return "{\"members\":[" + members + "],\"total_items\":" + total + "}";
    }

    private List<String> run(FakeMailchimpServer fake) throws Exception {
        var ids = new ArrayList<String>();
        try (var client = new MailchimpClient(runContextFactory.of(), fake.baseUrl(), "abc-us19", 0, Duration.ofSeconds(10), d -> { })) {
            client.getPagedWhile("/lists/a/members", null, 1000, page -> {
                page.path("members").forEach(i -> ids.add(i.get("id").asText()));
                return true;
            }, "members");
        }
        return ids;
    }

    @Test
    void zeroMembers() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, page(0, 0, 0)));

            assertThat(run(fake), empty());
            assertThat(fake.requests(), hasSize(1));
        }
    }

    @Test
    void exactlyOnePage() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, page(0, 1000, 1000)));

            assertThat(run(fake), hasSize(1000));
            assertThat(fake.requests(), hasSize(1)); // total_items reached: no empty extra page
        }
    }

    @Test
    void threePages() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, page(0, 1000, 2500)), Response.json(200, page(1000, 1000, 2500)), Response.json(200, page(2000, 500, 2500)));

            assertThat(run(fake), hasSize(2500));
            var queries = fake.requests().stream().map(FakeMailchimpServer.Recorded::query).toList();
            assertThat(queries, hasSize(3));
            assertThat(queries.getFirst(), containsString("count=1000"));
            assertThat(queries.getFirst(), containsString("offset=0"));
            assertThat(queries.get(1), containsString("offset=1000"));
            assertThat(queries.get(2), containsString("offset=2000"));
        }
    }

    @Test
    void shortLastPageTerminatesWithoutTotal() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, "{\"members\":[{\"id\":\"a\"}]}"));

            assertThat(run(fake), contains("a"));
            assertThat(fake.requests(), hasSize(1));
        }
    }

    @Test
    void shrinkingTotalTerminatesWithoutDuplicates() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, page(0, 1000, 2500)), Response.json(200, page(1000, 1000, 1200)));

            var ids = run(fake);

            assertThat(ids, hasSize(2000));
            assertThat(ids.stream().distinct().count(), Matchers.equalTo(2000L));
            assertThat(fake.requests(), hasSize(2));
        }
    }

    @Test
    void nonPositivePageSizeRejected() throws Exception {
        try (var fake = new FakeMailchimpServer(); var client = new MailchimpClient(runContextFactory.of(), fake.baseUrl(), "abc-us19", 0, Duration.ofSeconds(10), d -> { })) {
            Assertions.assertThrows(IllegalArgumentException.class, () -> client.getPagedWhile("/lists/a/members", null, 0, page -> true, "members"));
            assertThat(fake.requests(), empty());
        }
    }

    @Test
    void getPagedWhileStopsWhenHandlerReturnsFalseWithoutExtraRequest() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, page(0, 1000, 5000)), Response.json(200, page(1000, 1000, 5000)));
            var pages = new ArrayList<Integer>();

            try (var client = new MailchimpClient(runContextFactory.of(), fake.baseUrl(), "abc-us19", 0, Duration.ofSeconds(10), d -> { })) {
                client.getPagedWhile("/lists/a/members", null, 1000, p -> {
                    pages.add(p.path("members").size());
                    return pages.size() < 2;
                }, "members");
            }

            assertThat(pages, contains(1000, 1000));
            assertThat(fake.requests(), hasSize(2));
        }
    }

    @Test
    void getPagedWhileHandsOverAnEmptyPage() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, page(0, 0, 0)));
            var totals = new ArrayList<Long>();

            try (var client = new MailchimpClient(runContextFactory.of(), fake.baseUrl(), "abc-us19", 0, Duration.ofSeconds(10), d -> { })) {
                client.getPagedWhile("/lists/a/members", null, 1000, p -> totals.add(p.get("total_items").asLong()), "members");
            }

            assertThat(totals, contains(0L));
        }
    }
}

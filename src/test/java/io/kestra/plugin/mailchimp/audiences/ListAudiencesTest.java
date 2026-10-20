package io.kestra.plugin.mailchimp.audiences;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.mailchimp.FakeMailchimpServer;
import io.kestra.plugin.mailchimp.FakeMailchimpServer.Response;
import io.kestra.plugin.mailchimp.ReadTaskSupport;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class ListAudiencesTest {
    private static final String PATH = "/3.0/lists";
    private static final String BODY = """
        {"lists":[
          {"id":"a1","name":"News","date_created":"2024-01-02T03:04:05+00:00","visibility":"prv",
           "stats":{"member_count":12,"unsubscribe_count":3},"web_id":99},
          {"id":"a2","name":"Promo","stats":{"member_count":1,"unsubscribe_count":0}}
        ],"total_items":2}
        """;

    @Inject
    private RunContextFactory runContextFactory;

    private ListAudiences.ListAudiencesBuilder<?, ?> task(FakeMailchimpServer fake) {
        return ListAudiences.builder().id(IdUtils.create()).type(ListAudiences.class.getName()).apiKey(Property.ofValue("abc-us19")).baseUrl(Property.ofValue(fake.baseUrl()));
    }

    @Test
    void fetchMapsRowsToCamelCaseAndKeepsOtherFields() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, BODY));

            var out = task(fake).fetchType(Property.ofValue(FetchType.FETCH)).build().run(runContextFactory.of());

            assertThat(out.getRows(), hasSize(2));
            var row = out.getRows().getFirst();
            assertThat(row, hasEntry("id", "a1"));
            assertThat(row, hasEntry("name", "News"));
            assertThat(row, hasEntry("memberCount", 12));
            assertThat(row, hasEntry("unsubscribeCount", 3));
            assertThat(row, hasEntry("dateCreated", "2024-01-02T03:04:05+00:00"));
            assertThat(row, hasEntry("visibility", "prv"));
            assertThat(row, hasEntry("web_id", 99));
            assertThat(row, hasKey("stats"));
            assertThat(out.getSize(), is(2L));
            assertThat(out.getTotal(), is(2L));
            assertThat(fake.requests().getFirst().query(), equalTo("count=1000&offset=0"));
        }
    }

    @Test
    void fetchOne() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, BODY));

            var out = task(fake).fetchType(Property.ofValue(FetchType.FETCH_ONE)).build().run(runContextFactory.of());

            assertThat(out.getRow(), hasEntry("id", "a1"));
            assertThat(out.getRows(), nullValue());
            assertThat(fake.requests().getFirst().query(), equalTo("count=1&offset=0"));
        }
    }

    @Test
    void fetchOneOnEmptyAudienceListReturnsEmptyOutput() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, "{\"lists\":[],\"total_items\":0}"));

            var out = task(fake).fetchType(Property.ofValue(FetchType.FETCH_ONE)).build().run(runContextFactory.of());

            assertThat(out.getRow(), nullValue());
            assertThat(out.getSize(), is(0L));
        }
    }

    @Test
    void store() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, BODY));
            var rc = runContextFactory.of();

            var out = task(fake).fetchType(Property.ofValue(FetchType.STORE)).build().run(rc);

            assertThat(out.getRows(), nullValue());
            var rows = ReadTaskSupport.readIon(rc, out.getUri());
            assertThat(rows.stream().map(r -> r.get("id")).toList(), contains("a1", "a2"));
            assertThat(rows.getFirst(), hasEntry("memberCount", 12));
        }
    }

    @Test
    void none() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, BODY));

            var out = task(fake).fetchType(Property.ofValue(FetchType.NONE)).build().run(runContextFactory.of());

            assertThat(out.getRows(), nullValue());
            assertThat(out.getRow(), nullValue());
            assertThat(out.getUri(), nullValue());
            assertThat(out.getSize(), is(2L));
        }
    }

    @Test
    void sendsExactQueryWithDatesFieldsAndTotalContacts() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, BODY));

            task(fake)
                .sinceDateCreated(Property.ofValue(Instant.parse("2024-01-01T00:00:00Z")))
                .beforeDateCreated(Property.ofValue(Instant.parse("2024-02-01T10:20:30Z")))
                .includeTotalContacts(Property.ofValue(true))
                .fields(Property.ofValue(List.of("lists.id", "lists.name", "total_items")))
                .build().run(runContextFactory.of());

            assertThat(fake.requests().getFirst().query(), equalTo(
                "since_date_created=2024-01-01T00%3A00%3A00%2B00%3A00"
                    + "&before_date_created=2024-02-01T10%3A20%3A30%2B00%3A00"
                    + "&include_total_contacts=true"
                    + "&fields=lists.id%2Clists.name%2Ctotal_items"
                    + "&count=1000&offset=0"));
        }
    }

    @Test
    void maxItemsStopsPagingEarly() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, ReadTaskSupport.page("lists", 0, 3, 50)));

            var out = task(fake).maxItems(Property.ofValue(3)).build().run(runContextFactory.of());

            assertThat(out.getRows(), hasSize(3));
            assertThat(fake.requests(), hasSize(1));
            assertThat(fake.requests().getFirst().query(), equalTo("count=3&offset=0"));
        }
    }

    @Test
    void emptyResultIsNotAnError() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, "{\"lists\":[],\"total_items\":0}"));

            var out = task(fake).build().run(runContextFactory.of());

            assertThat(out.getRows(), is(empty()));
            assertThat(out.getTotal(), is(0L));
        }
    }

}

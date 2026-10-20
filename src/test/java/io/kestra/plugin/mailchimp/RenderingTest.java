package io.kestra.plugin.mailchimp;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.mailchimp.FakeMailchimpServer.Response;
import io.kestra.plugin.mailchimp.account.Ping;
import io.kestra.plugin.mailchimp.audiences.ListMembers;
import io.kestra.plugin.mailchimp.audiences.NewSubscriberTrigger;
import io.kestra.plugin.mailchimp.audiences.UpsertMember;
import io.kestra.plugin.mailchimp.campaigns.ListCampaigns;
import io.kestra.plugin.mailchimp.reports.GetCampaignReport;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/** Properties given as Pebble expressions reach the request as rendered values, for every kind of property type. */
@KestraTest
class RenderingTest {
    @Inject
    private RunContextFactory runContextFactory;

    private static Property<String> expr(String expression) {
        return Property.ofExpression(expression);
    }

    private static JsonNode json(String s) throws Exception {
        return JacksonMapper.ofJson().readTree(s);
    }

    @Test
    void credentialComesFromAnExpression() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", "/3.0/ping", Response.json(200, "{\"health_status\":\"Everything's Chimpy!\"}"));

            Ping.builder().id(IdUtils.create()).type(Ping.class.getName()).apiKey(expr("{{ inputs.key }}")).baseUrl(Property.ofValue(fake.baseUrl())).build()
                .run(runContextFactory.of(Map.of("inputs", Map.of("key", "abc-us19"))));

            assertThat(fake.requests().getFirst().header("authorization"), equalTo("Bearer abc-us19"));
        }
    }

    @Test
    void stringEnumAndInstantPropertiesAreRenderedIntoTheQuery() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", "/3.0/lists/L1/members", Response.json(200, ReadTaskSupport.page("members", 0, 1, 1)));

            ListMembers.builder().id(IdUtils.create()).type(ListMembers.class.getName()).apiKey(Property.ofValue("abc-us19")).baseUrl(Property.ofValue(fake.baseUrl()))
                .listId(expr("{{ inputs.list }}"))
                .status(Property.ofExpression("{{ inputs.status }}"))
                .sinceLastChanged(Property.ofExpression("{{ inputs.since }}"))
                .build()
                .run(runContextFactory.of(Map.of("inputs", Map.of("list", "L1", "status", "SUBSCRIBED", "since", "2026-01-01T00:00:00Z"))));

            var query = fake.requests().getFirst().query();
            assertThat(query, containsString("status=subscribed"));
            assertThat(query, containsString("since_last_changed=2026-01-01T00%3A00%3A00%2B00%3A00"));
        }
    }

    @Test
    void listAndMapPropertiesAreRenderedIntoTheBody() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("PUT", "/3.0/lists/L1/members/6a58e7286ac0e6944c137f0fb3f101b1", Response.json(200, "{\"id\":\"x\",\"status\":\"subscribed\"}"));

            UpsertMember.builder().id(IdUtils.create()).type(UpsertMember.class.getName()).apiKey(Property.ofValue("abc-us19")).baseUrl(Property.ofValue(fake.baseUrl()))
                .listId(Property.ofValue("L1"))
                .email(expr("{{ inputs.email }}"))
                .statusIfNew(Property.ofExpression("{{ inputs.status }}"))
                .mergeFields(Property.ofExpression("{{ inputs.merge | json }}"))
                .tags(Property.ofExpression("{{ inputs.tags | json }}"))
                .build()
                .run(runContextFactory.of(Map.of("inputs", Map.of(
                    "email", "Jane.Doe+news@Example.COM", "status", "PENDING", "merge", Map.of("FNAME", "Jane"), "tags", List.of("a", "b")))));

            var body = json(fake.requests().getFirst().body());
            assertThat(body.get("status_if_new").asText(), equalTo("pending"));
            assertThat(body.at("/merge_fields/FNAME").asText(), equalTo("Jane"));
            assertThat(body.get("tags").toString(), equalTo("[\"a\",\"b\"]"));
        }
    }

    @Test
    void campaignTaskPropertiesAreRendered() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", "/3.0/campaigns", Response.json(200, ReadTaskSupport.page("campaigns", 0, 1, 1)));

            ListCampaigns.builder().id(IdUtils.create()).type(ListCampaigns.class.getName()).apiKey(Property.ofValue("abc-us19")).baseUrl(Property.ofValue(fake.baseUrl()))
                .status(Property.ofExpression("{{ inputs.status }}"))
                .build()
                .run(runContextFactory.of(Map.of("inputs", Map.of("status", "SENT"))));

            assertThat(fake.requests().getFirst().query(), containsString("status=sent"));
        }
    }

    @Test
    void reportTaskIdIsRendered() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", "/3.0/reports/c1", Response.json(200, "{\"id\":\"c1\",\"emails_sent\":1}"));

            GetCampaignReport.builder().id(IdUtils.create()).type(GetCampaignReport.class.getName()).apiKey(Property.ofValue("abc-us19")).baseUrl(Property.ofValue(fake.baseUrl()))
                .campaignId(expr("{{ inputs.campaign }}"))
                .build()
                .run(runContextFactory.of(Map.of("inputs", Map.of("campaign", "c1"))));

            assertThat(fake.requests().getFirst().path(), equalTo("/3.0/reports/c1"));
        }
    }

    @Test
    void triggerListIdIsRendered() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", "/3.0/lists/L1/members", Response.json(200, ReadTaskSupport.page("members", 0, 0, 0)));
            var trigger = NewSubscriberTrigger.builder()
                .id("new-" + UUID.randomUUID())
                .type(NewSubscriberTrigger.class.getName())
                .apiKey(Property.ofValue("abc-us19"))
                .baseUrl(Property.ofValue(fake.baseUrl()))
                .listId(expr("{{ 'l1' | upper }}"))
                .build();
            var ctx = TestsUtils.mockTrigger(runContextFactory, trigger);

            assertThat(trigger.evaluate(ctx.getKey(), ctx.getValue()).isPresent(), is(false));

            assertThat(fake.requests().getFirst().path(), equalTo("/3.0/lists/L1/members"));
        }
    }
}

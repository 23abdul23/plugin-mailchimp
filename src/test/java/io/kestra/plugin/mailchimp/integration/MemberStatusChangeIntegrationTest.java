package io.kestra.plugin.mailchimp.integration;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.kestra.core.models.property.Property;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.mailchimp.ReadTaskSupport;
import io.kestra.plugin.mailchimp.audiences.MemberStatusChangeTrigger;
import io.kestra.plugin.mailchimp.audiences.UpsertMember;
import io.kestra.plugin.mailchimp.models.MemberStatus;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/** Cursor logic against the real timestamp format ({@code +00:00} offset, whole seconds). */
@EnabledIfEnvironmentVariable(named = "MAILCHIMP_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "MAILCHIMP_LIST_ID", matches = ".+")
class MemberStatusChangeIntegrationTest extends MailchimpIntegrationBase {
    @Test
    void firesOnceForANewUnsubscribedMember() throws Exception {
        var trigger = MemberStatusChangeTrigger.builder()
            .id("it-" + UUID.randomUUID())
            .type(MemberStatusChangeTrigger.class.getName())
            .apiKey(apiKeyProperty()).server(serverProperty())
            .listId(Property.ofValue(listId()))
            .status(Property.ofValue(MemberStatus.UNSUBSCRIBED))
            .build();
        var ctx = TestsUtils.mockTrigger(runContextFactory, trigger);

        // 1st poll: only records the position
        assertThat(trigger.evaluate(ctx.getKey(), ctx.getValue()).isEmpty(), is(true));

        var email = newEmail();
        UpsertMember.builder().id(IdUtils.create()).type(UpsertMember.class.getName()).apiKey(apiKeyProperty()).server(serverProperty())
            .listId(Property.ofValue(listId())).email(Property.ofValue(email))
            .statusIfNew(Property.ofValue(MemberStatus.UNSUBSCRIBED))
            .build().run(runContextFactory.of());

        // 2nd poll: the member (the list is eventually consistent, so poll until it shows up)
        var execution = eventually("trigger fired for the new member", () -> trigger.evaluate(ctx.getKey(), ctx.getValue()), Optional::isPresent).orElseThrow();
        var vars = execution.getTrigger().getVariables();
        assertThat(((Number) vars.get("count")).intValue(), is(1));
        List<Map<String, Object>> rows = ReadTaskSupport.readIon(ctx.getKey().getRunContext(), URI.create(String.valueOf(vars.get("uri"))));
        assertThat(rows.getFirst().get("emailAddress"), equalTo(email));

        // 3rd poll: nothing new (the 1 s slack re-reads the boundary row, the cursor must drop it)
        assertThat(trigger.evaluate(ctx.getKey(), ctx.getValue()).isEmpty(), is(true));
    }
}

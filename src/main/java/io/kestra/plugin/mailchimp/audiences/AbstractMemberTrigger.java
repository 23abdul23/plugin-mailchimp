package io.kestra.plugin.mailchimp.audiences;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.plugin.mailchimp.AbstractMailchimpTrigger;
import io.kestra.plugin.mailchimp.MailchimpClient;
import io.kestra.plugin.mailchimp.MailchimpConnectionInterface;
import io.kestra.plugin.mailchimp.polling.CursorPoll;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/** Audience-member polling shared by {@link NewSubscriberTrigger} and {@link MemberStatusChangeTrigger}. */
@SuperBuilder
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
public abstract class AbstractMemberTrigger extends AbstractMailchimpTrigger implements PollingTriggerInterface, TriggerOutput<AbstractMemberTrigger.Output> {
    @Schema(
        title = "Audience ID",
        description = """
            Mailchimp audience (list) id, found in the audience settings or with `ListAudiences`."""
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> listId;

    /** {@code query}: fixed member filters (status); {@code timeField}: {@code timestamp_opt} or {@code last_changed}. */
    protected Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context, String kind,
                                           Map<String, String> query, String timeField, Predicate<JsonNode> filter) throws Exception {
        var rListId = MailchimpConnectionInterface.required(conditionContext.getRunContext(), listId, "listId");
        var path = "/lists/" + MailchimpClient.segment(rListId) + "/members";

        return poll(conditionContext, context,
            new CursorPoll.Spec(kind, rListId, path, "members", query, timeField, filter, ListMembers::toRow),
            (client, r) -> {
                conditionContext.getRunContext().logger().info("{} new Mailchimp member event(s) in audience {}", r.count(), rListId);
                return Output.builder()
                    .listId(rListId)
                    .count(r.count())
                    .uri(r.uri())
                    .rows(r.rows())
                    .build();
            });
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Audience ID",
            description = """
                Mailchimp audience (list) id the members belong to."""
        )
        private final String listId;

        @Schema(
            title = "Number of new members",
            description = """
                Number of new members, as rows in the `uri` file or in `rows`."""
        )
        private final Long count;

        @Schema(
            title = "New members URI",
            description = """
                ION file in internal storage, one row per member, same fields as `ListMembers`, oldest first. Set when `fetchType` is `STORE`."""
        )
        private final URI uri;

        @Schema(
            title = "New members",
            description = """
                The new members, same fields as `ListMembers`, oldest first. Set when `fetchType` is `FETCH`."""
        )
        private final List<Map<String, Object>> rows;
    }
}

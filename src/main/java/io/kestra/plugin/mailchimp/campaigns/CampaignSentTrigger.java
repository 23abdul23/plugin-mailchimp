package io.kestra.plugin.mailchimp.campaigns;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.plugin.mailchimp.AbstractMailchimpTrigger;
import io.kestra.plugin.mailchimp.MailchimpClient;
import io.kestra.plugin.mailchimp.MailchimpException;
import io.kestra.plugin.mailchimp.models.Rows;
import io.kestra.plugin.mailchimp.polling.CursorPoll;
import io.kestra.plugin.mailchimp.reports.GetCampaignReport;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger on sent Mailchimp campaigns",
    description = """
        Polls `GET /campaigns?status=sent` by send time (`send_time`) and starts one execution per poll with every campaign sent since the last poll, stored as an ION file (`trigger.uri`, `trigger.count`). `campaignId`, `sendTime` and `title` describe the latest one; set `includeReport` to also get its report summary.

        The position is kept in the namespace KV Store, per flow, trigger and `listId` filter: the newest `send_time` seen plus the campaign ids sent at exactly that time. The first poll only records the current position: campaigns already sent do not fire. Each poll reads at most 1000 rows (more only when they all share one timestamp), so a backlog larger than 1000 drains over several polls."""
)
@Plugin(
    examples = {
        @Example(
            title = "Post the report of every sent campaign.",
            full = true,
            code = """
                id: mailchimp_campaign_sent
                namespace: company.team

                triggers:
                  - id: campaign_sent
                    type: io.kestra.plugin.mailchimp.campaigns.CampaignSentTrigger
                    apiKey: "{{ secret('MAILCHIMP_API_KEY') }}"
                    listId: "a1b2c3d4e5"
                    includeReport: true
                    interval: PT15M

                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "Campaign '{{ trigger.title }}' sent at {{ trigger.sendTime }}, {{ trigger.report.emailsSent ?? 'n/a' }} emails"
                """
        )
    }
)
public class CampaignSentTrigger extends AbstractMailchimpTrigger implements PollingTriggerInterface, TriggerOutput<CampaignSentTrigger.Output> {
    @Schema(
        title = "Audience ID",
        description = """
            Only campaigns sent to this audience (`list_id`). All audiences when not set."""
    )
    @PluginProperty(group = "processing")
    private Property<String> listId;

    @Builder.Default
    @Schema(
        title = "Include report",
        description = """
            Also fetch `GET /reports/{id}` for the latest sent campaign into `report`. Default `false`."""
    )
    @PluginProperty(group = "processing")
    private Property<Boolean> includeReport = Property.ofValue(false);

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();
        var query = new LinkedHashMap<String, String>();
        query.put("status", "sent");
        var rListId = runContext.render(listId).as(String.class).filter(s -> !s.isBlank());
        Rows.put(query, "list_id", rListId);

        return poll(conditionContext, context,
            new CursorPoll.Spec("campaign_sent", "campaign_" + rListId.orElse("all"), "/campaigns", "campaigns", query, "send_time", node -> true, ListCampaigns::toRow),
            (client, r) -> {
                var latest = r.latest();
                var campaignId = String.valueOf(latest.get("id"));
                runContext.logger().info("{} newly sent Mailchimp campaign(s), latest {}", r.count(), campaignId);

                Map<String, Object> report = null;
                if (runContext.render(includeReport).as(Boolean.class).orElse(false)) {
                    try {
                        report = Rows.row(client.sendForBody("GET", "/reports/" + MailchimpClient.segment(campaignId), null, null), GetCampaignReport.CAMEL);
                    } catch (MailchimpException e) {
                        // no report (yet) must not block the trigger; any other error fails the poll and nothing advances
                        if (e.getStatus() != 404) {
                            throw e;
                        }
                        runContext.logger().warn("No Mailchimp report for campaign {} (404): 'report' is left empty", campaignId);
                    }
                }

                return Output.builder()
                    .count(r.count())
                    .uri(r.uri())
                    .rows(r.rows())
                    .campaignId(campaignId)
                    .sendTime(OffsetDateTime.parse((String) latest.get("sendTime")).toInstant())
                    .title((String) latest.get("title"))
                    .report(report)
                    .build();
            });
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Number of newly sent campaigns",
            description = """
                Number of newly sent campaigns, as rows in the `uri` file or in `rows`."""
        )
        private final Long count;

        @Schema(
            title = "Sent campaigns URI",
            description = """
                ION file in internal storage, one row per campaign, same fields as `ListCampaigns`, oldest first."""
        )
        private final URI uri;

        @Schema(
            title = "Sent campaigns",
            description = """
                The newly sent campaigns, same fields as `ListCampaigns`, oldest first. Set when `fetchType` is `FETCH`."""
        )
        private final List<Map<String, Object>> rows;

        @Schema(
            title = "Latest campaign ID",
            description = """
                Id of the most recently sent campaign in this poll."""
        )
        private final String campaignId;

        @Schema(
            title = "Latest campaign send time",
            description = """
                Send time of the latest campaign."""
        )
        private final Instant sendTime;

        @Schema(
            title = "Latest campaign title",
            description = """
                Subject-line title of the most recently sent campaign in this poll."""
        )
        private final String title;

        @Schema(
            title = "Latest campaign report",
            description = """
                Report summary of the latest campaign, as `GetCampaignReport` returns it. Only set when `includeReport` is true; empty when Mailchimp has no report for it (404)."""
        )
        private final Map<String, Object> report;
    }
}

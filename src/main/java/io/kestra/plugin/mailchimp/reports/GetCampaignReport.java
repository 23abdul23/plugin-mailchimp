package io.kestra.plugin.mailchimp.reports;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.mailchimp.AbstractMailchimpTask;
import io.kestra.plugin.mailchimp.MailchimpClient;
import io.kestra.plugin.mailchimp.MailchimpConnectionInterface;
import io.kestra.plugin.mailchimp.models.Rows;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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
    title = "Get a Mailchimp campaign report",
    description = """
        Reads the report summary of a sent campaign with `GET /reports/{campaignId}`.

        The report exposes `emailsSent`, `unsubscribed`, `opens`, `clicks` and `bounces` (the last three keep their nested Mailchimp keys such as `opens_total`), plus `id`, `sendTime`, `listId` and `subjectLine` when present; every other Mailchimp field keeps its original key. Use `fields` to trim the response."""
)
@Plugin(
    examples = {
        @Example(
            title = "Get opens and clicks of a campaign.",
            full = true,
            code = """
                id: mailchimp_campaign_report
                namespace: company.team

                tasks:
                  - id: report
                    type: io.kestra.plugin.mailchimp.reports.GetCampaignReport
                    apiKey: "{{ secret('MAILCHIMP_API_KEY') }}"
                    campaignId: "42694e9e57"
                    fields:
                      - emails_sent
                      - opens.opens_total
                      - clicks.clicks_total
                """
        )
    }
)
public class GetCampaignReport extends AbstractMailchimpTask implements RunnableTask<GetCampaignReport.Output> {
    /** Report keys exposed in camelCase; shared with {@code CampaignSentTrigger}. */
    public static final Set<String> CAMEL = Set.of("emails_sent", "send_time", "list_id", "subject_line");

    @Schema(
        title = "Campaign ID",
        description = """
            Id of a sent campaign, as returned by `ListCampaigns`."""
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> campaignId;

    @Schema(
        title = "Fields",
        description = """
            Restrict the response to these Mailchimp fields, using Mailchimp's dotted syntax, e.g. `emails_sent`, `opens.opens_total`."""
    )
    @PluginProperty(group = "processing")
    private Property<List<String>> fields;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rCampaignId = MailchimpConnectionInterface.required(runContext, campaignId, "campaignId");
        var path = "/reports/" + MailchimpClient.segment(rCampaignId);

        var query = new LinkedHashMap<String, String>();
        Rows.put(query, "fields", Optional.of(runContext.render(fields).asList(String.class)));

        try (var client = client(runContext)) {
            return Output.builder().report(Rows.row(client.sendForBody("GET", path, query, null), CAMEL)).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Report",
            description = """
                The campaign report summary, with the main fields in camelCase and the rest under their Mailchimp keys."""
        )
        private final Map<String, Object> report;
    }
}

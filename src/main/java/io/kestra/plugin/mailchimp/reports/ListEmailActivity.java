package io.kestra.plugin.mailchimp.reports;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Set;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.mailchimp.AbstractMailchimpFetchTask;
import io.kestra.plugin.mailchimp.MailchimpClient;
import io.kestra.plugin.mailchimp.MailchimpConnectionInterface;
import io.kestra.plugin.mailchimp.models.FetchOutput;
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
    title = "List the email activity of a Mailchimp campaign",
    description = """
        Reads the per-recipient activity of a sent campaign with `GET /reports/{campaignId}/email-activity` (the `emails` array), 1000 per request, until `maxItems` or the end.

        Each row exposes `emailAddress`, `emailId` and `activity` (a list of `{action, timestamp, url, ip}`, left as Mailchimp sends it); every other field keeps its original key."""
)
@Plugin(
    examples = {
        @Example(
            title = "Store the activity of a campaign as an ION file.",
            full = true,
            code = """
                id: mailchimp_email_activity
                namespace: company.team

                tasks:
                  - id: activity
                    type: io.kestra.plugin.mailchimp.reports.ListEmailActivity
                    apiKey: "{{ secret('MAILCHIMP_API_KEY') }}"
                    campaignId: "42694e9e57"
                    since: "2026-01-01T00:00:00Z"
                    fetchType: STORE
                """
        )
    }
)
public class ListEmailActivity extends AbstractMailchimpFetchTask {
    private static final Set<String> CAMEL = Set.of("email_address", "email_id");

    @Schema(
        title = "Campaign ID",
        description = """
            Id of a sent campaign, as returned by `ListCampaigns`."""
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> campaignId;

    @Schema(
        title = "Since",
        description = """
            Only activity after this ISO-8601 date and time (`since`)."""
    )
    @PluginProperty(group = "processing")
    private Property<Instant> since;

    @Schema(
        title = "Filter bots",
        description = """
            Excludes bot activity via the API's `filter_bots` parameter (sent only when `true`). Default `false`."""
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Boolean> filterBots = Property.ofValue(false);

    @Override
    public FetchOutput run(RunContext runContext) throws Exception {
        var rCampaignId = MailchimpConnectionInterface.required(runContext, campaignId, "campaignId");
        var path = "/reports/" + MailchimpClient.segment(rCampaignId) + "/email-activity";

        var query = new LinkedHashMap<String, String>();
        Rows.put(query, "since", runContext.render(since).as(Instant.class));
        Rows.put(query, "filter_bots", runContext.render(filterBots).as(Boolean.class).filter(Boolean::booleanValue));

        return fetch(runContext, path, query, "emails", FetchType.STORE, item -> Rows.row(item, CAMEL));
    }
}

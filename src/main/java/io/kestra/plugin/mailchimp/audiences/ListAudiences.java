package io.kestra.plugin.mailchimp.audiences;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.mailchimp.AbstractMailchimpFetchTask;
import io.kestra.plugin.mailchimp.models.FetchOutput;
import io.kestra.plugin.mailchimp.models.Rows;

import io.swagger.v3.oas.annotations.media.Schema;
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
    title = "List Mailchimp audiences",
    description = """
        Reads the audiences (lists) of the account with `GET /lists`.

        By default every page is read (1000 per request) until `maxItems` or the end. If `count` or `offset` is set, a single request honoring them is made and no further page is read.

        Each row exposes `id`, `name`, `dateCreated`, `visibility`, `memberCount` and `unsubscribeCount` (the latter two come from `stats`); every other Mailchimp field keeps its original key."""
)
@Plugin(
    examples = {
        @Example(
            title = "List all audiences created this year and keep them in memory.",
            full = true,
            code = """
                id: mailchimp_list_audiences
                namespace: company.team

                tasks:
                  - id: audiences
                    type: io.kestra.plugin.mailchimp.audiences.ListAudiences
                    apiKey: "{{ secret('MAILCHIMP_API_KEY') }}"
                    sinceDateCreated: "2026-01-01T00:00:00Z"
                    fetchType: FETCH
                """
        )
    }
)
public class ListAudiences extends AbstractMailchimpFetchTask {
    private static final Set<String> CAMEL = Set.of("date_created");

    @Schema(
        title = "Created since",
        description = """
            Only audiences created after this ISO-8601 date and time (sent as `since_date_created`)."""
    )
    @PluginProperty(group = "processing")
    private Property<Instant> sinceDateCreated;

    @Schema(
        title = "Created before",
        description = """
            Only audiences created before this ISO-8601 date and time (sent as `before_date_created`)."""
    )
    @PluginProperty(group = "processing")
    private Property<Instant> beforeDateCreated;

    @Schema(
        title = "Include total contacts",
        description = """
            Also ask Mailchimp for the total contact count of each audience (`include_total_contacts`)."""
    )
    @PluginProperty(group = "processing")
    private Property<Boolean> includeTotalContacts;

    @Schema(
        title = "Fields",
        description = """
            Restrict the response to these Mailchimp fields, using Mailchimp's dotted syntax, e.g. `lists.id`, `lists.name`, `total_items`."""
    )
    @PluginProperty(group = "processing")
    private Property<List<String>> fields;

    @Override
    public FetchOutput run(RunContext runContext) throws Exception {
        var query = new LinkedHashMap<String, String>();
        Rows.put(query, "since_date_created", runContext.render(sinceDateCreated).as(Instant.class));
        Rows.put(query, "before_date_created", runContext.render(beforeDateCreated).as(Instant.class));
        Rows.put(query, "include_total_contacts", runContext.render(includeTotalContacts).as(Boolean.class).filter(Boolean::booleanValue));
        Rows.put(query, "fields", Optional.of(runContext.render(fields).asList(String.class)));

        return fetch(runContext, "/lists", query, "lists", FetchType.FETCH,
            item -> Rows.hoist(Rows.row(item, CAMEL), item.path("stats"), "member_count", "unsubscribe_count"));
    }
}

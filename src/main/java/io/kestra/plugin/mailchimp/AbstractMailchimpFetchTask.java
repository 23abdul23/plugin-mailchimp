package io.kestra.plugin.mailchimp;

import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.mailchimp.models.FetchOutput;
import io.kestra.plugin.mailchimp.models.Fetcher;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/** Base of the tasks that list Mailchimp records: they share `fetchType`, `maxItems` and the paged read. */
@SuperBuilder
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
public abstract class AbstractMailchimpFetchTask extends AbstractMailchimpTask implements RunnableTask<FetchOutput> {

    @Schema(
        title = "Fetch strategy",
        description = """
            `FETCH_ONE`: first record only.
            `FETCH`: all records in memory.
            `STORE`: write rows as ION to internal storage and return the URI.
            `NONE`: only count them.
            Default `FETCH`, except `STORE` for `ListMembers` and `ListEmailActivity`, which can return very many rows."""
    )
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType;

    @Schema(
        title = "Max items",
        minimum = "1",
        description = """
            Stop after this many records. Must be at least 1. Default: unlimited, so set it to bound a huge result."""
    )
    @PluginProperty(group = "processing")
    private Property<Integer> maxItems;

    /** Pages through {@code GET path} and shapes the rows as `fetchType` and `maxItems` ask. */
    protected FetchOutput fetch(RunContext runContext, String path, Map<String, String> query, String arrayField,
                                FetchType defaultFetchType, Function<JsonNode, Map<String, Object>> mapper) throws Exception {
        try (var client = client(runContext)) {
            return Fetcher.fetchPaged(
                runContext, client, path, query, arrayField,
                runContext.render(fetchType).as(FetchType.class).orElse(defaultFetchType),
                runContext.render(maxItems).as(Integer.class).orElse(null),
                mapper
            );
        }
    }
}

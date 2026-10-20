package io.kestra.plugin.mailchimp.audiences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Data;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.mailchimp.MailchimpConnectionInterface;
import io.kestra.plugin.mailchimp.AbstractMailchimpTask;
import io.kestra.plugin.mailchimp.MailchimpClient;
import io.kestra.plugin.mailchimp.models.MemberStatus;
import io.kestra.plugin.mailchimp.models.SubscriberHash;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
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
    title = "Subscribe or update many members of a Mailchimp audience",
    description = """
        Reads an ION file from internal storage row by row and sends the members to `POST /lists/{listId}` in chunks of `chunkSize` (500 at most, Mailchimp's limit), one request after the other. The file is streamed, never loaded in memory.

        Each row needs `emailAddress` and `status` (`subscribed`, `unsubscribed`, `cleaned`, `pending` or `transactional`), and may have `mergeFields` (object) and `tags` (list of names). The whole file is checked before the first request, so a bad row fails the task before anything is sent and the message names the 1-based row number.

        Mailchimp answers 200 even when some members were rejected; those are reported in `errors`. By default (`failOnError: true`) the task fails after all chunks were sent if any member was rejected. A failed request (for example a 5xx) fails the task at once and is never retried, because replaying a POST could repeat work; the message tells how many chunks had already succeeded so you can resume. Retries only apply to 429. An empty file makes no request.

        Only add contacts who have opted in; you are responsible for GDPR and Mailchimp terms."""
)
@Plugin(
    examples = {
        @Example(
            title = "Subscribe a list of members.",
            full = true,
            code = """
                id: mailchimp_batch_subscribe
                namespace: company.team

                tasks:
                  - id: subscribe
                    type: io.kestra.plugin.mailchimp.audiences.BatchSubscribe
                    apiKey: "{{ secret('MAILCHIMP_API_KEY') }}"
                    listId: "a1b2c3d4e5"
                    from:
                      - emailAddress: jane.doe@example.com
                        status: subscribed
                        mergeFields:
                          FNAME: Jane
                        tags:
                          - newsletter
                      - emailAddress: john.doe@example.com
                        status: pending
                    updateExisting: true
                    failOnError: false
                """
        ),
        @Example(
            title = "Subscribe the rows of an ION file, here the members of another audience.",
            full = true,
            code = """
                id: mailchimp_copy_audience
                namespace: company.team

                tasks:
                  - id: members
                    type: io.kestra.plugin.mailchimp.audiences.ListMembers
                    apiKey: "{{ secret('MAILCHIMP_API_KEY') }}"
                    listId: "a1b2c3d4e5"
                    status: SUBSCRIBED
                    fetchType: STORE

                  - id: subscribe
                    type: io.kestra.plugin.mailchimp.audiences.BatchSubscribe
                    apiKey: "{{ secret('MAILCHIMP_API_KEY') }}"
                    listId: "f6g7h8i9j0"
                    from: "{{ outputs.members.uri }}"
                """
        )
    }
)
public class BatchSubscribe extends AbstractMailchimpTask implements RunnableTask<BatchSubscribe.Output> {
    private static final int MAX_CHUNK = 500;
    private static final int LISTED_ERRORS = 10;
    /** Rejected members kept in the output; {@code errorCount} still holds the real total. */
    private static final int MAX_ERRORS = 1000;

    @Schema(
        title = "Audience ID",
        description = """
            Mailchimp audience (list) id, found in the audience settings or with `ListAudiences`."""
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> listId;

    @Schema(
        title = "Members to subscribe",
        description = Data.From.DESCRIPTION + " Each item is one member with `emailAddress` and `status`, and optionally `mergeFields` and `tags`.",
        anyOf = {String.class, List.class, Map.class}
    )
    @NotNull
    @PluginProperty(group = "main", internalStorageURI = true)
    private Object from;

    @Schema(
        title = "Update existing",
        description = """
            Update members that already exist (`update_existing`). Mailchimp's default is false: existing members are then reported as errors."""
    )
    @PluginProperty(group = "processing")
    private Property<Boolean> updateExisting;

    @Schema(
        title = "Sync tags",
        description = """
            Replace the tags of existing members by the ones in the file (`sync_tags`)."""
    )
    @PluginProperty(group = "processing")
    private Property<Boolean> syncTags;

    @Schema(
        title = "Skip merge validation",
        description = """
            Accept members with missing required merge fields (`skip_merge_validation`)."""
    )
    @PluginProperty(group = "advanced")
    private Property<Boolean> skipMergeValidation;

    @Schema(
        title = "Skip duplicate check",
        description = """
            Do not check the batch for duplicate emails (`skip_duplicate_check`)."""
    )
    @PluginProperty(group = "advanced")
    private Property<Boolean> skipDuplicateCheck;

    @Schema(
        title = "Fail on error",
        description = """
            Fail the task, after all chunks were sent, when Mailchimp rejected members. When false, they are returned in `errors`. Default `true`."""
    )
    @Builder.Default
    @PluginProperty(group = "reliability")
    private Property<Boolean> failOnError = Property.ofValue(true);

    @Schema(
        title = "Chunk size",
        minimum = "1",
        maximum = "500",
        description = """
            Members per request, between 1 and 500. Default 500."""
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Integer> chunkSize = Property.ofValue(MAX_CHUNK);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rListId = MailchimpConnectionInterface.required(runContext, listId, "listId");
        if (from == null || (from instanceof String text && text.isBlank())) {
            throw new IllegalArgumentException("'from' is required and must not be blank");
        }
        var data = Data.from(from);
        var rChunk = runContext.render(chunkSize).as(Integer.class).orElse(MAX_CHUNK);
        if (rChunk < 1 || rChunk > MAX_CHUNK) {
            throw new IllegalArgumentException("'chunkSize' must be between 1 and " + MAX_CHUNK);
        }
        var rFailOnError = runContext.render(failOnError).as(Boolean.class).orElse(true);

        var flags = new LinkedHashMap<String, Object>();
        runContext.render(updateExisting).as(Boolean.class).ifPresent(v -> flags.put("update_existing", v));
        runContext.render(syncTags).as(Boolean.class).ifPresent(v -> flags.put("sync_tags", v));
        runContext.render(skipMergeValidation).as(Boolean.class).ifPresent(v -> flags.put("skip_merge_validation", v));
        runContext.render(skipDuplicateCheck).as(Boolean.class).ifPresent(v -> flags.put("skip_duplicate_check", v));

        // pass 1: validate every row before the first request; streaming, only a counter is kept
        var rows = new long[1];
        forEachRow(runContext, data, (n, raw) -> {
            toMember(n, raw);
            rows[0] = n;
        });
        var chunks = (int) ((rows[0] + rChunk - 1) / rChunk);

        var totals = new Totals();
        if (chunks > 0) {
            try (var client = client(runContext)) {
                var batch = new ArrayList<Map<String, Object>>();
                forEachRow(runContext, data, (n, raw) -> {
                    batch.add(toMember(n, raw));
                    if (batch.size() >= rChunk) {
                        sendChunk(client, rListId, batch, flags, totals, chunks, rChunk);
                        batch.clear();
                    }
                });
                if (!batch.isEmpty()) {
                    sendChunk(client, rListId, batch, flags, totals, chunks, rChunk);
                }
            }
        }

        if (rFailOnError && totals.rejected > 0) {
            throw new IllegalStateException(totals.rejected + " member(s) were rejected by Mailchimp (" + totals.created + " created, " + totals.updated + " updated): "
                + describe(totals.errors, totals.rejected) + ". Set 'failOnError: false' to get them in the 'errors' output.");
        }

        return Output.builder()
            .totalCreated(totals.created)
            .totalUpdated(totals.updated)
            .errorCount(totals.rejected)
            .errors(List.copyOf(totals.errors))
            .batches(totals.sent)
            .build();
    }

    private static final class Totals {
        long created;
        long updated;
        long rejected;
        int sent;
        final List<Output.MemberError> errors = new ArrayList<>();
    }

    private static void sendChunk(MailchimpClient client, String rListId, List<Map<String, Object>> members, Map<String, Object> flags,
                                  Totals totals, int chunks, int rChunk) {
        var body = new LinkedHashMap<String, Object>();
        body.put("members", List.copyOf(members));
        body.putAll(flags);
        try {
            var response = client.sendForBody("POST", "/lists/" + MailchimpClient.segment(rListId), null, body);
            for (var field : List.of("total_created", "total_updated", "error_count")) {
                if (!response.path(field).canConvertToLong()) {
                    // a renamed field must not silently count as 0 members
                    throw new IllegalStateException("Unexpected Mailchimp response: '" + field + "' is missing or not a number");
                }
            }
            totals.created += response.path("total_created").asLong();
            totals.updated += response.path("total_updated").asLong();
            totals.rejected += response.path("error_count").asLong();
            for (var err : response.path("errors")) {
                if (totals.errors.size() >= MAX_ERRORS) {
                    break;
                }
                totals.errors.add(new Output.MemberError(err.path("email_address").asText(null), err.path("error").asText(null), err.path("error_code").asText(null)));
            }
            totals.sent++;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Batch subscribe failed on chunk " + (totals.sent + 1) + " of " + chunks + ": " + e.getMessage()
                + ". " + totals.sent + " of " + chunks + " chunk(s) succeeded before this failure (rows 1-" + ((long) totals.sent * rChunk)
                + " were sent); the failed request is not retried, resume from row " + ((long) totals.sent * rChunk + 1)
                + ". So far: " + totals.created + " created, " + totals.updated + " updated, " + totals.rejected + " rejected"
                + (totals.rejected > 0 ? ": " + describe(totals.errors, totals.rejected) : ""), e);
        }
    }

    private static String describe(List<Output.MemberError> errors, long errorCount) {
        var listed = errors.stream().limit(LISTED_ERRORS)
            .map(e -> (e.getEmailAddress() == null ? "<unknown>" : e.getEmailAddress()) + (e.getErrorCode() == null ? "" : " (" + e.getErrorCode() + ")"))
            .collect(Collectors.joining(", "));
        var more = errorCount - Math.min(errors.size(), LISTED_ERRORS);
        return listed + (more > 0 ? " and " + more + " more" : "");
    }

    // the flux is cancelled (and its file closed) when a row fails, because the exception propagates through it
    private static void forEachRow(RunContext runContext, Data data, BiConsumer<Long, Map<String, Object>> handler) throws Exception {
        data.read(runContext).index().doOnNext(row -> handler.accept(row.getT1() + 1, row.getT2())).blockLast();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMember(long n, Map<String, Object> raw) {
        var rawEmail = raw.get("emailAddress");
        if (rawEmail == null || rawEmail.toString().isBlank()) {
            throw new IllegalArgumentException("Invalid input at row " + n + ": 'emailAddress' is required");
        }
        var email = rawEmail.toString().trim().toLowerCase(Locale.ROOT);
        try {
            SubscriberHash.of(email);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid input at row " + n + ": " + e.getMessage());
        }
        var status = raw.get("status") == null ? "" : raw.get("status").toString().trim().toLowerCase(Locale.ROOT);
        if (Arrays.stream(MemberStatus.values()).noneMatch(v -> v.name().toLowerCase(Locale.ROOT).equals(status))) {
            throw new IllegalArgumentException("Invalid input at row " + n + ": 'status' must be one of "
                + Arrays.stream(MemberStatus.values()).map(v -> v.name().toLowerCase(Locale.ROOT)).sorted().toList() + " but was '" + raw.get("status") + "'");
        }
        var member = new LinkedHashMap<String, Object>();
        member.put("email_address", email);
        member.put("status", status);
        if (raw.get("mergeFields") instanceof Map<?, ?> m && !m.isEmpty()) {
            member.put("merge_fields", m);
        }
        if (raw.get("tags") instanceof List<?> l && !l.isEmpty()) {
            member.put("tags", l);
        }
        return member;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Created",
            description = """
                Members created, summed over all requests."""
        )
        private final long totalCreated;

        @Schema(
            title = "Updated",
            description = """
                Members updated, summed over all requests."""
        )
        private final long totalUpdated;

        @Schema(
            title = "Error count",
            description = """
                Members rejected by Mailchimp (`error_count`), summed over all requests."""
        )
        private final long errorCount;

        @Schema(
            title = "Errors",
            description = """
                Rejected members with Mailchimp's message and code, capped at 1000 entries. Mailchimp may list fewer than `errorCount`; `errorCount` is always the full total."""
        )
        private final List<MemberError> errors;

        @Schema(
            title = "Batches",
            description = """
                Number of HTTP requests sent."""
        )
        private final int batches;

        @Getter
        @AllArgsConstructor
        public static class MemberError {
            @Schema(
                title = "Email address",
                description = """
                    Email of the rejected member."""
            )
            private final String emailAddress;

            @Schema(
                title = "Error",
                description = """
                    Mailchimp's message."""
            )
            private final String error;

            @Schema(
                title = "Error code",
                description = """
                    Mailchimp's error code, e.g. `ERROR_CONTACT_EXISTS`."""
            )
            private final String errorCode;
        }
    }
}

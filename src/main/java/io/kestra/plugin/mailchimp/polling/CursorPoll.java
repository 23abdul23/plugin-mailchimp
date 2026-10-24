package io.kestra.plugin.mailchimp.polling;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;

import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.runners.RunContext;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVStore;
import io.kestra.core.storages.kv.KVValue;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.kestra.plugin.mailchimp.MailchimpClient;
import io.kestra.plugin.mailchimp.models.Fetcher;
import io.kestra.plugin.mailchimp.models.Rows;

/**
 * Polling logic shared by every Mailchimp trigger: cursor in the namespace KV store, incremental query sorted
 * ascending on a timestamp field, client-side dedup through {@link TriggerCursor}, new rows stored as one ION file.
 *
 * <p>Each poll reads ONE page (1000 rows) and moves the cursor to the newest row of it; the next poll restarts from
 * that cursor (minus 1 s). Paging further with {@code offset} inside a poll is unsafe: a row whose timestamp
 * changes mid-poll jumps to the end, the following rows shift left and one is skipped below the advanced cursor
 * for good. The only exception is a full page that does not move the cursor at all (only known rows, e.g. more
 * than 1000 rows in the 1 s slack window or more than 1000 ties in one second): the next poll would read the very
 * same page, so the next page is read in the same poll to guarantee progress. Pages are only chained while no
 * progress was made, so a shift there can only skip a row if it happens in that narrow case. A larger backlog
 * therefore drains at 1000 rows per poll.
 */
public final class CursorPoll {
    private static final Comparator<TriggerCursor.Item> ORDER =
        Comparator.comparing(TriggerCursor.Item::timestamp).thenComparing(TriggerCursor.Item::id);

    private CursorPoll() {
    }

    /** {@code kind} and {@code scope} build the KV key; {@code query} holds the fixed filters; {@code filter} rows that fail it do not fire, but the cursor still moves past them. */
    public record Spec(String kind, String scope, String path, String arrayField, Map<String, String> query,
                       String timeField, Predicate<JsonNode> filter, Function<JsonNode, Map<String, Object>> mapper) {
    }

    /** New rows of one poll. Call {@link #commit()} once the execution is built to persist the advanced cursor. */
    public record Result(long count, URI uri, List<Map<String, Object>> rows, TriggerCursor cursor, Map<String, Object> latest, KVStore kv, String key) {
        public void commit() throws IOException {
            put(kv, key, cursor);
        }
    }

    /** First poll (or unreadable cursor): records the current position and returns empty. Later polls return the new rows, or empty. */
    public static Optional<Result> poll(RunContext runContext, TriggerContext context, MailchimpClient client, Spec spec, FetchType fetchType) throws Exception {
        if (fetchType != FetchType.STORE && fetchType != FetchType.FETCH) {
            throw new IllegalArgumentException("A trigger's 'fetchType' must be STORE or FETCH, got " + fetchType);
        }
        var logger = runContext.logger();
        var kv = runContext.namespaceKv(context.getNamespace());
        // length-prefixed flow and trigger ids: no collision across flows or segment splits
        var flowId = context.getFlowId();
        var triggerId = context.getTriggerId();
        var key = "mailchimp_" + spec.kind() + "_" + flowId.length() + "_" + flowId + "_" + triggerId.length() + "_" + triggerId + "_" + spec.scope();
        var raw = kv.getValue(key).map(KVValue::value).map(String::valueOf);
        var stored = raw.flatMap(TriggerCursor::tryParse);
        if (raw.isPresent() && stored.isEmpty()) {
            logger.warn("Stored cursor in KV key '{}' cannot be read: restarting from the current position, events since the last valid poll are skipped", key);
        }

        if (stored.isEmpty()) {
            var query = new LinkedHashMap<>(spec.query());
            query.put("sort_field", spec.timeField());
            query.put("sort_dir", "DESC");
            query.put("count", String.valueOf(Fetcher.PAGE_SIZE));
            query.put("offset", "0");
            // only the id and the timestamp are used here, so do not download whole records
            query.put("fields", spec.arrayField() + ".id," + spec.arrayField() + "." + spec.timeField());
            var items = new ArrayList<TriggerCursor.Item>();
            for (var node : client.send("GET", spec.path(), query, null).path(spec.arrayField())) {
                item(node, spec, logger).ifPresent(items::add);
            }
            // empty list: start at the epoch, so the first row ever added fires whatever its timestamp
            var start = new TriggerCursor(Instant.EPOCH, new TreeSet<>());
            put(kv, key, items.isEmpty() ? start : start.advance(items));
            return Optional.empty();
        }

        var cursor = stored.get();
        var query = new LinkedHashMap<>(spec.query());
        // 1 s slack: `since_*` is exclusive with whole-second precision; the cursor drops the rows it brings back twice
        var since = cursor.timestamp().minusSeconds(1);
        query.put("since_" + spec.timeField(), Rows.iso(since.isBefore(Instant.EPOCH) ? Instant.EPOCH : since));
        query.put("sort_field", spec.timeField());
        query.put("sort_dir", "ASC");

        var read = new ArrayList<TriggerCursor.Item>(); // every dated row read: the cursor moves past all of them
        var fired = new ArrayList<TriggerCursor.Item>();
        var seen = new HashSet<String>();
        var latest = new Progress(cursor);
        try (var collector = Fetcher.collector(runContext, fetchType, null, spec.arrayField(), node -> {
            var item = item(node, spec, logger);
            item.ifPresent(read::add);
            item = item.filter(i -> spec.filter().test(node) && cursor.isNew(i.timestamp(), i.id()) && seen.add(i.id()));
            if (item.isEmpty()) {
                return null;
            }
            fired.add(item.get());
            var row = spec.mapper().apply(node);
            if (latest.item == null || ORDER.compare(item.get(), latest.item) > 0) {
                latest.item = item.get();
                latest.row = row;
            }
            return row;
        })) {
            // ponytail: more than 1000 rows in one second (bulk import) are re-read on every poll; resume from a stored offset if that is expected
            client.getPagedWhile(spec.path(), query, Fetcher.PAGE_SIZE, page -> {
                collector.accept(page);
                latest.running = latest.running.advance(read.subList(latest.advancedTo, read.size()));
                latest.advancedTo = read.size();
                // next page only while a full page brought nothing that moves the cursor: guarantees progress
                return page.path(spec.arrayField()).size() >= Fetcher.PAGE_SIZE && latest.running.equals(cursor);
            }, spec.arrayField());
            var next = latest.running;
            if (fired.isEmpty()) {
                // only ignored rows (other status...): still move past them so they are not read again and again
                if (!next.equals(cursor)) {
                    put(kv, key, next);
                }
                return Optional.empty();
            }
            var out = collector.build();
            return Optional.of(new Result(out.getSize(), out.getUri(), out.getRows(), next, latest.row, kv, key));
        }
    }

    /** What one poll has seen so far; the cursor is advanced page by page, never recomputed over everything read. */
    private static final class Progress {
        TriggerCursor.Item item;
        Map<String, Object> row;
        TriggerCursor running;
        int advancedTo;

        Progress(TriggerCursor start) {
            this.running = start;
        }
    }

    private static Optional<Instant> time(JsonNode node, String timeField) {
        try {
            return Optional.of(OffsetDateTime.parse(node.path(timeField).asText("")).toInstant());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** Row id and timestamp, or empty with a warning when either is missing or invalid. */
    private static Optional<TriggerCursor.Item> item(JsonNode node, Spec spec, Logger logger) {
        var id = node.path("id").asText("");
        var raw = node.path(spec.timeField()).asText("");
        if (id.isEmpty()) {
            logger.warn("Skipping a Mailchimp record without id");
            return Optional.empty();
        }
        var time = time(node, spec.timeField());
        if (time.isEmpty()) {
            logger.warn("Skipping Mailchimp record '{}': '{}' is not an ISO-8601 date-time: '{}'", id, spec.timeField(), raw);
        }
        return time.map(t -> new TriggerCursor.Item(t, id));
    }

    private static void put(KVStore kv, String key, TriggerCursor cursor) throws IOException {
        kv.put(key, new KVValueAndMetadata(new KVMetadata(null, (Duration) null), cursor.format()));
    }
}

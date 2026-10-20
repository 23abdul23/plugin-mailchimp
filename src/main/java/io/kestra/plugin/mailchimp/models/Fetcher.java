package io.kestra.plugin.mailchimp.models;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;

import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.mailchimp.MailchimpClient;

/** Reads Mailchimp list pages and keeps the rows the way `fetchType` asks. */
public final class Fetcher {
    public static final int PAGE_SIZE = 1000;

    private Fetcher() {
    }

    /** Pages through {@code GET path}, 1000 rows per request (fewer when {@code maxItems} is lower), and collects the mapped rows. */
    public static FetchOutput fetchPaged(RunContext runContext, MailchimpClient client, String path, Map<String, String> query,
                                         String arrayField, FetchType fetchType, Integer maxItems,
                                         Function<JsonNode, Map<String, Object>> mapper) throws Exception {
        try (var collector = collector(runContext, fetchType, maxItems, arrayField, mapper)) {
            client.getPagedWhile(path, query, (int) Math.min(PAGE_SIZE, collector.cap), collector::accept, arrayField);
            return collector.build();
        }
    }

    public static Collector collector(RunContext runContext, FetchType fetchType, Integer maxItems, String arrayField,
                                      Function<JsonNode, Map<String, Object>> mapper) throws IOException {
        if (maxItems != null && maxItems < 1) {
            throw new IllegalArgumentException("'maxItems' must be >= 1");
        }
        return new Collector(runContext, fetchType, maxItems, arrayField, mapper);
    }

    /** `STORE` streams rows to a temp file, so only `FETCH` keeps all rows in memory. */
    public static class Collector implements Closeable {
        private final RunContext runContext;
        private final FetchType fetchType;
        private final long cap;
        private final String arrayField;
        private final Function<JsonNode, Map<String, Object>> mapper;
        private final List<Map<String, Object>> rows = new ArrayList<>();
        private final Path tempFile;
        private final OutputStream out;
        private long size;
        private Long total;

        private Collector(RunContext runContext, FetchType fetchType, Integer maxItems, String arrayField, Function<JsonNode, Map<String, Object>> mapper) throws IOException {
            this.runContext = runContext;
            this.fetchType = fetchType;
            this.arrayField = arrayField;
            this.mapper = mapper;
            this.cap = fetchType == FetchType.FETCH_ONE ? 1 : (maxItems == null ? Long.MAX_VALUE : maxItems);
            this.tempFile = fetchType == FetchType.STORE ? runContext.workingDir().createTempFile(".ion") : null;
            this.out = tempFile == null ? null : new BufferedOutputStream(Files.newOutputStream(tempFile), FileSerde.BUFFER_SIZE);
        }

        /** Consumes one response page; returns {@code false} once enough rows were read (stop requesting pages). */
        public boolean accept(JsonNode page) {
            if (page.has("total_items")) {
                total = page.get("total_items").asLong();
            }
            try {
                for (var item : page.path(arrayField)) {
                    var row = mapper.apply(item);
                    if (row == null) {
                        continue;
                    }
                    size++;
                    switch (fetchType) {
                        case FETCH, FETCH_ONE -> rows.add(row);
                        case STORE -> FileSerde.write(out, row);
                        case NONE -> { }
                    }
                    if (size >= cap) {
                        return false;
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return true;
        }

        public FetchOutput build() throws IOException {
            var output = FetchOutput.builder().size(size).total(total);
            switch (fetchType) {
                case FETCH_ONE -> output.row(rows.isEmpty() ? null : rows.getFirst());
                case FETCH -> output.rows(List.copyOf(rows));
                case STORE -> {
                    close();
                    output.uri(runContext.storage().putFile(tempFile.toFile()));
                }
                case NONE -> { }
            }
            return output.build();
        }

        @Override
        public void close() throws IOException {
            if (out != null) {
                out.close();
            }
        }
    }
}

package io.kestra.plugin.mailchimp.models;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;

import io.kestra.core.serializers.JacksonMapper;

/** Query parameters going out and rows coming in: the conversions between Mailchimp's JSON and Kestra values. */
public final class Rows {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    private Rows() {
    }

    /** ISO-8601 with numeric offset (e.g. {@code 2024-01-01T00:00:00+00:00}), as Mailchimp expects. */
    public static String iso(Instant instant) {
        return ISO.format(instant.atOffset(ZoneOffset.UTC));
    }

    /** Adds a rendered optional property as query parameter: {@link Instant} as ISO-8601, {@link SortDir} as is, other enums in lower case, lists comma-joined. */
    public static void put(Map<String, String> query, String key, Optional<?> value) {
        value.filter(v -> !(v instanceof List<?> l && l.isEmpty())).ifPresent(v -> query.put(key, switch (v) {
            case Instant i -> iso(i);
            case SortDir d -> d.name();
            case Enum<?> e -> e.name().toLowerCase(Locale.ROOT);
            case List<?> l -> l.stream().map(String::valueOf).collect(Collectors.joining(","));
            default -> String.valueOf(v);
        }));
    }

    public static String camel(String snake) {
        var sb = new StringBuilder();
        var up = false;
        for (var c : snake.toCharArray()) {
            if (c == '_') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return sb.toString();
    }

    /** A Mailchimp JSON object as a row: keys in {@code camelKeys} become camelCase, the others keep their name; the per-item {@code _links} are dropped. */
    public static Map<String, Object> row(JsonNode node, Set<String> camelKeys) {
        var row = new LinkedHashMap<String, Object>();
        node.fields().forEachRemaining(e -> {
            if (!"_links".equals(e.getKey())) {
                row.put(camelKeys.contains(e.getKey()) ? camel(e.getKey()) : e.getKey(), JacksonMapper.ofJson().convertValue(e.getValue(), Object.class));
            }
        });
        return row;
    }

    /** The row plus {@code keys} of a nested object (e.g. {@code stats.member_count}) copied up in camelCase. */
    public static Map<String, Object> hoist(Map<String, Object> row, JsonNode parent, String... keys) {
        var hoisted = new LinkedHashMap<>(row);
        for (var key : keys) {
            if (parent.has(key)) {
                hoisted.put(camel(key), JacksonMapper.ofJson().convertValue(parent.get(key), Object.class));
            }
        }
        return hoisted;
    }
}

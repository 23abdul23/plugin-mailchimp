package io.kestra.plugin.mailchimp;

import java.time.Duration;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
public abstract class AbstractMailchimpTask extends Task implements MailchimpConnectionInterface {

    @ToString.Exclude
    @Schema(
        title = "Mailchimp API key",
        description = """
            Set exactly one of `apiKey` or `accessToken`. The data center is taken from the key suffix (for example `-us19`) unless `server` is set."""
    )
    @PluginProperty(group = "connection", secret = true)
    private Property<String> apiKey;

    @ToString.Exclude
    @Schema(
        title = "OAuth access token",
        description = """
            Set exactly one of `apiKey` or `accessToken`. `server` is required with an access token."""
    )
    @PluginProperty(group = "connection", secret = true)
    private Property<String> accessToken;

    @Schema(
        title = "Data center",
        description = """
            Mailchimp data center such as `us19`. Required with `accessToken`, optional with `apiKey` (overrides the key suffix)."""
    )
    @PluginProperty(group = "connection")
    private Property<String> server;

    @Schema(
        title = "Base URL override",
        description = """
            Replaces `https://<server>.api.mailchimp.com/3.0`. The credential is sent to this URL, so it must be an https `*.api.mailchimp.com` host, or http on localhost / 127.0.0.1."""
    )
    @PluginProperty(group = "connection")
    private Property<String> baseUrl;

    @Builder.Default
    @Schema(
        title = "Max retries",
        minimum = "0",
        maximum = "10",
        description = """
            Retries done by this plugin on HTTP 429 (all methods) and on 5xx or I/O errors (GET and PUT only), with exponential backoff. Default 3, between 0 and 10. Kestra's HTTP client also replays a GET or PUT once after a 429 or 503 on its own, so `0` only turns off the plugin's retries."""
    )
    @PluginProperty(group = "reliability")
    private Property<Integer> maxRetries = Property.ofValue(3);

    @Builder.Default
    @Schema(
        title = "Request timeout",
        description = """
            Idle read timeout of each call: how long to wait without receiving data. Default PT2M, must be positive. Mailchimp itself cuts requests off after 120 seconds."""
    )
    @PluginProperty(group = "advanced")
    private Property<Duration> timeout = Property.ofValue(Duration.ofSeconds(120));

    protected MailchimpClient client(RunContext runContext) throws Exception {
        return MailchimpConnectionInterface.client(runContext, this);
    }
}

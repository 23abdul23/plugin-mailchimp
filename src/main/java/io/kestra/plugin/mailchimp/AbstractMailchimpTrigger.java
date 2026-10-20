package io.kestra.plugin.mailchimp;

import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.annotations.VisibleForTesting;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Output;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.mailchimp.models.CursorPoll;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

// Same connection properties as AbstractMailchimpTask, a trigger cannot extend Task
@SuperBuilder
@ToString
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
public abstract class AbstractMailchimpTrigger extends AbstractTrigger implements MailchimpConnectionInterface {

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

    public static final Duration MIN_INTERVAL = Duration.ofSeconds(30);

    @Builder.Default
    @Schema(
        title = "Polling interval",
        description = """
            Time between two polls. Defaults to 5 minutes, minimum 30 seconds to stay well within Mailchimp's rate limits."""
    )
    @PluginProperty(group = "execution")
    private Duration interval = Duration.ofMinutes(5);

    // validated when the flow is saved, so a too short interval is refused in the editor instead of failing every poll
    @JsonIgnore
    @AssertTrue(message = "interval must be at least PT30S")
    public boolean isIntervalAtLeastMinimum() {
        return interval == null || interval.compareTo(MIN_INTERVAL) >= 0;
    }

    @FunctionalInterface
    protected interface OutputBuilder {
        Output build(MailchimpClient client, CursorPoll.Result result) throws Exception;
    }

    /** Polls once and, when something is new, starts an execution; the cursor is saved only after the execution is built. */
    protected Optional<Execution> poll(ConditionContext conditionContext, TriggerContext context, CursorPoll.Spec spec, OutputBuilder outputBuilder) throws Exception {
        var runContext = conditionContext.getRunContext();
        try (var client = client(runContext)) {
            var result = CursorPoll.poll(runContext, context, client, spec);
            if (result.isEmpty()) {
                return Optional.empty();
            }
            var r = result.get();
            var execution = TriggerService.generateExecution(this, conditionContext, context, outputBuilder.build(client, r));
            // at most once: if Kestra failed to emit the execution now, these rows would not fire again, but a successful poll never fires them twice
            r.commit();
            return Optional.of(execution);
        }
    }

    @VisibleForTesting
    protected MailchimpClient client(RunContext runContext) throws Exception {
        return MailchimpConnectionInterface.client(runContext, this);
    }
}

package io.kestra.plugin.mailchimp;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.ExecuteFlow;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.State;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/** Runs the flows of {@code sanity-checks} through a real runner; none of them needs a Mailchimp account. */
@KestraTest(startRunner = true)
class RunnerTest {
    @Test
    @ExecuteFlow("sanity-checks/mailchimp_batch_subscribe_empty.yaml")
    void emptyBatchSucceedsWithoutAnyRequest(Execution execution) {
        assertThat(execution.getState().getCurrent(), is(State.Type.SUCCESS));
    }

    @Test
    @ExecuteFlow("sanity-checks/mailchimp_rejects_foreign_base_url.yaml")
    void foreignBaseUrlIsRefusedBeforeTheCredentialIsSent(Execution execution) {
        assertThat(execution.getState().getCurrent(), is(State.Type.WARNING));
    }
}

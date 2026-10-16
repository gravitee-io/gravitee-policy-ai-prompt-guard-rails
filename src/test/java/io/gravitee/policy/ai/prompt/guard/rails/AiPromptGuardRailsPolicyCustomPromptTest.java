/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.policy.ai.prompt.guard.rails;

import static io.gravitee.policy.ai.prompt.guard.rails.TestFixtures.keyword;
import static io.gravitee.policy.ai.prompt.guard.rails.TestFixtures.windowCount;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.gateway.reactive.api.context.llm.LlmRequestInspector;
import io.gravitee.policy.ai.prompt.guard.rails.configuration.AiPromptGuardRailsConfiguration;
import io.gravitee.policy.ai.prompt.guard.rails.configuration.AiPromptGuardRailsConfiguration.PromptPreset;
import io.gravitee.policy.ai.prompt.guard.rails.configuration.RequestPolicy;
import io.reactivex.rxjava3.core.Maybe;
import java.util.List;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * The prompt extracted from the configured location, by the template engine on a plain http api or by the llm request
 * inspector on an llm api, inspected against a classifier that only sees the first tokens of what it is given.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class AiPromptGuardRailsPolicyCustomPromptTest {

    private static final String PROMPT_LOCATION = "{#request.jsonContent.prompt}";
    private static final String LONG_TOXIC_PROMPT = "You are a helpful assistant. ".repeat(200) + "You are toxic";
    private static final AiPromptGuardRailsConfiguration CONFIGURATION = new AiPromptGuardRailsConfiguration(
        "ai-model-text-classification-resource",
        PromptPreset.CUSTOM_PROMPT,
        PROMPT_LOCATION,
        null,
        null,
        RequestPolicy.BLOCK_REQUEST
    );

    private PolicyHarness harness;

    @BeforeEach
    void setUp() {
        harness = new PolicyHarness();
    }

    @Test
    void should_block_a_prompt_whose_toxic_part_lies_beyond_the_first_tokens_the_classifier_reads() {
        harness.run(CONFIGURATION, contextEvaluatingTheLocation(Maybe.just(LONG_TOXIC_PROMPT)));

        assertBlockedAtItsLastWindow();
    }

    @Test
    void should_block_a_prompt_extracted_by_the_llm_request_inspector_whose_toxic_part_lies_beyond_the_first_tokens_the_classifier_reads() {
        harness.run(CONFIGURATION, contextInspectingTheLlmRequest(LONG_TOXIC_PROMPT));

        assertBlockedAtItsLastWindow();
    }

    @Test
    void should_let_a_blank_prompt_through_without_any_inference() {
        harness.run(CONFIGURATION, contextEvaluatingTheLocation(Maybe.just("   ")));

        assertSoftly(softly -> {
            softly.assertThat(harness.classifier.inputs).as("inferences").isEmpty();
            softly.assertThat(harness.interruptions).as("interruptions").isEmpty();
        });
    }

    @Test
    void should_interrupt_the_request_when_the_location_fails_to_be_evaluated() {
        harness.run(CONFIGURATION, contextEvaluatingTheLocation(Maybe.error(new IllegalStateException("Unparsable body"))));

        assertSoftly(softly -> {
            softly.assertThat(harness.classifier.inputs).as("inferences").isEmpty();
            softly
                .assertThat(harness.interruptions)
                .as("interruptions (status code, key)")
                .extracting(ExecutionFailure::statusCode, ExecutionFailure::key)
                .containsExactly(tuple(500, "UNEXPECTED_ERROR"));
        });
    }

    private void assertBlockedAtItsLastWindow() {
        assertSoftly(softly -> {
            softly
                .assertThat(harness.classifier.inputs)
                .as("inferences, in order")
                .hasSize((int) windowCount(LONG_TOXIC_PROMPT))
                .last(InstanceOfAssertFactories.STRING)
                .endsWith("You are toxic");
            softly
                .assertThat(harness.interruptions)
                .as("interruptions (status code, message)")
                .extracting(ExecutionFailure::statusCode, ExecutionFailure::message)
                .containsExactly(tuple(400, "AI prompt validation detected. Reason: [toxic]"));
            softly
                .assertThat(harness.metrics.getAdditionalMetrics())
                .as("additional metrics")
                .haveExactly(1, keyword("keyword_action", "request-blocked"))
                .haveExactly(1, keyword("keyword_content_violations", "toxic"));
        });
    }

    /**
     * A plain http api: no llm request inspector, the prompt is evaluated by the template engine.
     */
    private HttpPlainExecutionContext contextEvaluatingTheLocation(Maybe<String> prompt) {
        var templateEngine = mock(TemplateEngine.class);
        when(templateEngine.eval(PROMPT_LOCATION, String.class)).thenReturn(prompt);

        var ctx = harness.context();
        when(ctx.getTemplateEngine()).thenReturn(templateEngine);
        return ctx;
    }

    /**
     * An llm api: the llm request inspector extracts the prompt from the configured location.
     */
    private HttpPlainExecutionContext contextInspectingTheLlmRequest(String prompt) {
        var inspector = mock(LlmRequestInspector.class);
        when(inspector.prompt(any(), eq(new LlmRequestInspector.PromptQuery.CustomPrompt(PROMPT_LOCATION)), any())).thenReturn(
            Maybe.just(List.of(prompt))
        );

        var ctx = harness.context();
        when(ctx.<LlmRequestInspector>getAttribute("llm.inspector")).thenReturn(inspector);
        return ctx;
    }
}

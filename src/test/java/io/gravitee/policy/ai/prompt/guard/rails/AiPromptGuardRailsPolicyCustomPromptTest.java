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
import static io.gravitee.policy.ai.prompt.guard.rails.TestFixtures.policy;
import static io.gravitee.policy.ai.prompt.guard.rails.TestFixtures.windowCount;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainRequest;
import io.gravitee.policy.ai.prompt.guard.rails.configuration.AiPromptGuardRailsConfiguration;
import io.gravitee.policy.ai.prompt.guard.rails.configuration.AiPromptGuardRailsConfiguration.PromptPreset;
import io.gravitee.policy.ai.prompt.guard.rails.configuration.RequestPolicy;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The prompt of a plain http api, extracted from the configured location, inspected against a classifier that only
 * sees the first tokens of what it is given.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class AiPromptGuardRailsPolicyCustomPromptTest {

    private static final String PROMPT_LOCATION = "{#request.jsonContent.prompt}";

    private TruncatingClassifier classifier;
    private Metrics metrics;
    private List<ExecutionFailure> interruptions;

    @BeforeEach
    void setUp() {
        classifier = new TruncatingClassifier();
        metrics = new Metrics();
        interruptions = new ArrayList<>();
    }

    @Test
    void should_block_a_prompt_whose_toxic_part_lies_beyond_the_first_tokens_the_classifier_reads() {
        var prompt = "You are a helpful assistant. ".repeat(200) + "You are toxic";

        inspect(Maybe.just(prompt));

        assertSoftly(softly -> {
            softly
                .assertThat(classifier.inputs)
                .as("inferences, in order")
                .hasSize(windowCount(prompt))
                .last(InstanceOfAssertFactories.STRING)
                .endsWith("You are toxic");
            softly
                .assertThat(interruptions)
                .as("interruptions (status code, message)")
                .extracting(ExecutionFailure::statusCode, ExecutionFailure::message)
                .containsExactly(tuple(400, "AI prompt validation detected. Reason: [toxic]"));
            softly
                .assertThat(metrics.getAdditionalMetrics())
                .as("additional metrics")
                .haveExactly(1, keyword("keyword_action", "request-blocked"))
                .haveExactly(1, keyword("keyword_content_violations", "toxic"));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "   " })
    void should_let_a_blank_prompt_through_without_any_inference(String prompt) {
        inspect(Maybe.just(prompt));

        assertSoftly(softly -> {
            softly.assertThat(classifier.inputs).as("inferences").isEmpty();
            softly.assertThat(interruptions).as("interruptions").isEmpty();
            softly.assertThat(metrics.getAdditionalMetrics()).as("additional metrics").isEmpty();
        });
    }

    @Test
    void should_interrupt_the_request_as_an_unexpected_error_when_the_location_fails_to_be_evaluated() {
        inspect(Maybe.error(new IllegalStateException("Unparsable expression")));

        assertSoftly(softly -> {
            softly.assertThat(classifier.inputs).as("inferences").isEmpty();
            softly
                .assertThat(interruptions)
                .as("interruptions (status code, key)")
                .extracting(ExecutionFailure::statusCode, ExecutionFailure::key)
                .containsExactly(tuple(500, "UNEXPECTED_ERROR"));
        });
    }

    private void inspect(Maybe<String> prompt) {
        var configuration = new AiPromptGuardRailsConfiguration(
            "ai-model-text-classification-resource",
            PromptPreset.CUSTOM_PROMPT,
            PROMPT_LOCATION,
            null,
            null,
            RequestPolicy.BLOCK_REQUEST
        );

        policy(configuration, classifier).onRequest(context(prompt)).test().assertComplete();
    }

    private HttpPlainExecutionContext context(Maybe<String> prompt) {
        var request = mock(HttpPlainRequest.class);
        when(request.bodyOrEmpty()).thenReturn(Single.just(Buffer.buffer()));

        var templateEngine = mock(TemplateEngine.class);
        when(templateEngine.eval(PROMPT_LOCATION, String.class)).thenReturn(prompt);

        var ctx = TestFixtures.context(HttpPlainExecutionContext.class, metrics, interruptions);
        when(ctx.request()).thenReturn(request);
        when(ctx.getTemplateEngine()).thenReturn(templateEngine);
        return ctx;
    }
}

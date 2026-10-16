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

import static io.gravitee.policy.ai.prompt.guard.rails.TestFixtures.distinctCodePoints;
import static io.gravitee.policy.ai.prompt.guard.rails.TestFixtures.keyword;
import static io.gravitee.policy.ai.prompt.guard.rails.TestFixtures.violations;
import static io.gravitee.policy.ai.prompt.guard.rails.TestFixtures.windowCount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.gateway.reactive.api.context.llm.LlmRequestInspector;
import io.gravitee.policy.ai.prompt.guard.rails.configuration.AiPromptGuardRailsConfiguration;
import io.gravitee.policy.ai.prompt.guard.rails.configuration.AiPromptGuardRailsConfiguration.PromptPreset;
import io.gravitee.policy.ai.prompt.guard.rails.configuration.RequestPolicy;
import io.reactivex.rxjava3.core.Maybe;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The conversation of an llm api, as the texts the llm request inspector extracts from it in conversation order,
 * inspected against a classifier that behaves like the real one: it reports a score for every label it knows, and
 * only sees the first tokens of what it is given. Texts are inspected from the last one to the first one, and the
 * windows of a text from its start to its end.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class AiPromptGuardRailsPolicyConversationTest {

    private static final String LONG_SYSTEM_PROMPT = "You are a helpful assistant. ".repeat(200);

    private PolicyHarness harness;
    private TruncatingClassifier classifier;

    @BeforeEach
    void setUp() {
        harness = new PolicyHarness();
        classifier = harness.classifier;
    }

    @Nested
    class BlockRequest {

        @Test
        void should_block_a_toxic_last_user_message_behind_a_long_system_message() {
            inspect(RequestPolicy.BLOCK_REQUEST, null, LONG_SYSTEM_PROMPT, "You are toxic");

            assertSoftly(softly -> {
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

        @Test
        void should_stop_at_the_first_inference_flagging_a_label() {
            inspect(RequestPolicy.BLOCK_REQUEST, null, LONG_SYSTEM_PROMPT, "You are toxic");

            assertThat(classifier.inputs).as("inferences").singleElement().isEqualTo("You are toxic");
        }

        @Test
        void should_inspect_the_conversation_from_its_last_message_and_stop_at_the_first_one_flagged() {
            inspect(RequestPolicy.BLOCK_REQUEST, null, "Be nice", "That is toxic", "Sorry", "Hi");

            assertSoftly(softly -> {
                softly.assertThat(classifier.inputs).as("inferences, in order").containsExactly("Hi", "Sorry", "That is toxic");
                softly
                    .assertThat(harness.interruptions)
                    .as("interruptions (status code)")
                    .extracting(ExecutionFailure::statusCode)
                    .containsExactly(400);
            });
        }

        @Test
        void should_inspect_the_windows_of_a_message_from_its_start() {
            var message = distinctCodePoints(1000);

            inspect(RequestPolicy.BLOCK_REQUEST, null, message);

            assertSoftly(softly -> {
                softly
                    .assertThat(classifier.inputs)
                    .as("inferences, in order")
                    .containsExactly(message.substring(0, 500), message.substring(250, 750), message.substring(500, 1000));
                softly.assertThat(harness.interruptions).as("interruptions").isEmpty();
            });
        }

        @Test
        void should_interrupt_the_request_with_the_failure_of_an_inference_that_is_not_the_first_one() {
            inspect(RequestPolicy.BLOCK_REQUEST, null, "Are you " + TruncatingClassifier.NOT_READY + "?", "Tell me a joke");

            assertSoftly(softly -> {
                softly
                    .assertThat(classifier.inputs)
                    .as("inferences, in order")
                    .containsExactly("Tell me a joke", "Are you " + TruncatingClassifier.NOT_READY + "?");
                softly
                    .assertThat(harness.interruptions)
                    .as("interruptions (status code, message)")
                    .extracting(ExecutionFailure::statusCode, ExecutionFailure::message)
                    .containsExactly(tuple(503, "Model is not ready"));
                softly.assertThat(harness.metrics.getAdditionalMetrics()).as("additional metrics").isEmpty();
            });
        }

        @Test
        void should_let_a_friendly_conversation_through() {
            inspect(RequestPolicy.BLOCK_REQUEST, null, "Be nice", "Tell me a joke");

            assertSoftly(softly -> {
                softly.assertThat(classifier.inputs).as("inferences, in order").containsExactly("Tell me a joke", "Be nice");
                softly.assertThat(harness.interruptions).as("interruptions").isEmpty();
                softly.assertThat(harness.metrics.getAdditionalMetrics()).as("additional metrics").isEmpty();
            });
        }

        @Test
        void should_skip_the_texts_that_carry_nothing() {
            inspect(RequestPolicy.BLOCK_REQUEST, null, "Be nice", "   ", "", "Tell me a joke");

            assertThat(classifier.inputs).as("inferences, in order").containsExactly("Tell me a joke", "Be nice");
        }

        @Test
        void should_let_a_request_without_any_text_through_without_any_inference() {
            inspect(RequestPolicy.BLOCK_REQUEST, null);

            assertSoftly(softly -> {
                softly.assertThat(classifier.inputs).as("inferences").isEmpty();
                softly.assertThat(harness.interruptions).as("interruptions").isEmpty();
            });
        }
    }

    @Test
    void should_never_run_two_inferences_at_once() {
        classifier.answeringAfter(Duration.ofMillis(10));

        inspect(RequestPolicy.BLOCK_REQUEST, null, "Be nice", "Tell me a joke", "Why did the chicken cross the road?");

        assertSoftly(softly -> {
            softly.assertThat(classifier.inputs).as("inferences").hasSize(3);
            softly.assertThat(classifier.mostRunningAtOnce).as("most inferences running at once").hasValue(1);
            softly.assertThat(harness.interruptions).as("interruptions").isEmpty();
        });
    }

    @Test
    void should_interrupt_the_request_when_the_inspector_fails_to_extract_its_texts() {
        var ctx = harness.context();
        var inspector = mock(LlmRequestInspector.class);
        when(inspector.prompt(any(), any(), any())).thenReturn(Maybe.error(new IllegalStateException("Unparsable body")));
        when(ctx.<LlmRequestInspector>getAttribute("llm.inspector")).thenReturn(inspector);

        harness.run(configuration(RequestPolicy.BLOCK_REQUEST, null), ctx);

        assertSoftly(softly -> {
            softly.assertThat(classifier.inputs).as("inferences").isEmpty();
            softly
                .assertThat(harness.interruptions)
                .as("interruptions (status code, key)")
                .extracting(ExecutionFailure::statusCode, ExecutionFailure::key)
                .containsExactly(tuple(500, "UNEXPECTED_ERROR"));
        });
    }

    @Nested
    class LogRequest {

        @Test
        void should_stop_once_every_configured_content_check_is_flagged() {
            inspect(RequestPolicy.LOG_REQUEST, "toxic,obscene", "Never insult anyone", "That is obscene", "Sorry", "You are toxic");

            assertSoftly(softly -> {
                softly
                    .assertThat(classifier.inputs)
                    .as("inferences, in order")
                    .containsExactly("You are toxic", "Sorry", "That is obscene");
                softly.assertThat(harness.interruptions).as("interruptions").isEmpty();
                softly
                    .assertThat(harness.metrics.getAdditionalMetrics())
                    .as("additional metrics")
                    .haveExactly(1, keyword("keyword_action", "request-logged"))
                    .haveExactly(1, violations("toxic", "obscene"));
            });
        }

        @Test
        void should_inspect_every_window_when_no_content_check_is_configured() {
            inspect(RequestPolicy.LOG_REQUEST, null, "Be nice", "That is an insult", "That is obscene", "You are toxic");

            assertSoftly(softly -> {
                softly
                    .assertThat(classifier.inputs)
                    .as("inferences, in order")
                    .containsExactly("You are toxic", "That is obscene", "That is an insult", "Be nice");
                softly.assertThat(harness.interruptions).as("interruptions").isEmpty();
                softly
                    .assertThat(harness.metrics.getAdditionalMetrics())
                    .as("additional metrics")
                    .haveExactly(1, keyword("keyword_action", "request-logged"))
                    .haveExactly(1, violations("toxic", "obscene", "insult"));
            });
        }

        @Test
        void should_inspect_every_window_when_nothing_is_flagged() {
            inspect(
                RequestPolicy.LOG_REQUEST,
                "toxic,obscene",
                LONG_SYSTEM_PROMPT,
                "Tell me a joke",
                "Why did the chicken cross the road?"
            );

            assertSoftly(softly -> {
                softly
                    .assertThat(classifier.inputs)
                    .as("inferences")
                    .hasSize((int) windowCount(LONG_SYSTEM_PROMPT, "Tell me a joke", "Why did the chicken cross the road?"))
                    .startsWith("Why did the chicken cross the road?", "Tell me a joke");
                softly.assertThat(harness.interruptions).as("interruptions").isEmpty();
                softly.assertThat(harness.metrics.getAdditionalMetrics()).as("additional metrics").isEmpty();
            });
        }

        @Test
        void should_keep_the_highest_score_of_a_label_over_the_inferences() {
            inspect(RequestPolicy.LOG_REQUEST, "toxic,obscene", "Be nice", "You are toxic", "Tell me a joke");

            assertSoftly(softly -> {
                softly
                    .assertThat(classifier.inputs)
                    .as("inferences, in order: toxic scored low, then high, then low again")
                    .containsExactly("Tell me a joke", "You are toxic", "Be nice");
                softly.assertThat(harness.interruptions).as("interruptions").isEmpty();
                softly
                    .assertThat(harness.metrics.getAdditionalMetrics())
                    .as("additional metrics")
                    .haveExactly(1, keyword("keyword_action", "request-logged"))
                    .haveExactly(1, keyword("keyword_content_violations", "toxic"));
            });
        }

        @Test
        void should_log_the_violations_of_a_toxic_last_user_message_behind_a_long_system_message() {
            inspect(RequestPolicy.LOG_REQUEST, "toxic,obscene", LONG_SYSTEM_PROMPT, "You are toxic");

            assertSoftly(softly -> {
                softly
                    .assertThat(classifier.inputs)
                    .as("inferences")
                    .hasSize((int) windowCount(LONG_SYSTEM_PROMPT, "You are toxic"))
                    .startsWith("You are toxic");
                softly.assertThat(harness.interruptions).as("interruptions").isEmpty();
                softly
                    .assertThat(harness.metrics.getAdditionalMetrics())
                    .as("additional metrics")
                    .haveExactly(1, keyword("keyword_action", "request-logged"))
                    .haveExactly(1, keyword("keyword_content_violations", "toxic"));
            });
        }
    }

    /**
     * Runs the policy on a request whose conversation the llm request inspector extracts as the given texts, in
     * conversation order.
     */
    private void inspect(RequestPolicy requestPolicy, String contentChecks, String... conversation) {
        var ctx = harness.context();
        var inspector = mock(LlmRequestInspector.class);
        when(inspector.prompt(any(), any(), any())).thenReturn(Maybe.just(List.of(conversation)));
        when(ctx.<LlmRequestInspector>getAttribute("llm.inspector")).thenReturn(inspector);

        harness.run(configuration(requestPolicy, contentChecks), ctx);
    }

    private static AiPromptGuardRailsConfiguration configuration(RequestPolicy requestPolicy, String contentChecks) {
        return new AiPromptGuardRailsConfiguration(
            "ai-model-text-classification-resource",
            PromptPreset.ALL_PROMPTS,
            null,
            contentChecks,
            null,
            requestPolicy
        );
    }
}

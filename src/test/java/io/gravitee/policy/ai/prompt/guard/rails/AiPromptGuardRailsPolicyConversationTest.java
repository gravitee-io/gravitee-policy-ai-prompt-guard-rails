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

import static io.gravitee.policy.ai.prompt.guard.rails.TestFixtures.context;
import static io.gravitee.policy.ai.prompt.guard.rails.TestFixtures.distinctCodePoints;
import static io.gravitee.policy.ai.prompt.guard.rails.TestFixtures.keyword;
import static io.gravitee.policy.ai.prompt.guard.rails.TestFixtures.policy;
import static io.gravitee.policy.ai.prompt.guard.rails.TestFixtures.windowCount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.llm.LlmContextPart;
import io.gravitee.gateway.reactive.api.context.llm.LlmExecutionContext;
import io.gravitee.gateway.reactive.api.context.llm.LlmRequest;
import io.gravitee.gateway.reactive.api.context.llm.Role;
import io.gravitee.gateway.reactive.api.context.llm.Turn;
import io.gravitee.policy.ai.prompt.guard.rails.configuration.AiPromptGuardRailsConfiguration;
import io.gravitee.policy.ai.prompt.guard.rails.configuration.AiPromptGuardRailsConfiguration.PromptPreset;
import io.gravitee.policy.ai.prompt.guard.rails.configuration.RequestPolicy;
import io.gravitee.reporter.api.v4.metric.AdditionalMetric;
import io.gravitee.reporter.api.v4.metric.Metrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Condition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The conversation of an llm api, inspected against a classifier that behaves like the real one: it reports a
 * score for every label it knows, and only sees the first tokens of what it is given. Messages are inspected
 * from the last one to the first one, and the windows of a message from its start to its end.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class AiPromptGuardRailsPolicyConversationTest {

    private static final String LONG_SYSTEM_PROMPT = "You are a helpful assistant. ".repeat(200);

    private TruncatingClassifier classifier;
    private Metrics metrics;
    private List<ExecutionFailure> interruptions;

    @BeforeEach
    void setUp() {
        classifier = new TruncatingClassifier();
        metrics = new Metrics();
        interruptions = new ArrayList<>();
    }

    @Nested
    class BlockRequest {

        @Test
        void should_block_a_toxic_last_user_message_behind_a_long_system_message() {
            inspect(RequestPolicy.BLOCK_REQUEST, null, turn(Role.SYSTEM, LONG_SYSTEM_PROMPT), turn(Role.USER, "You are toxic"));

            assertSoftly(softly -> {
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

        @Test
        void should_stop_at_the_first_inference_flagging_a_label() {
            inspect(RequestPolicy.BLOCK_REQUEST, null, turn(Role.SYSTEM, LONG_SYSTEM_PROMPT), turn(Role.USER, "You are toxic"));

            assertThat(classifier.inputs).as("inferences").singleElement().isEqualTo("You are toxic");
        }

        @Test
        void should_inspect_the_conversation_from_its_last_message_and_stop_at_the_first_one_flagged() {
            inspect(
                RequestPolicy.BLOCK_REQUEST,
                null,
                turn(Role.SYSTEM, "Be nice"),
                turn(Role.USER, "That is toxic"),
                turn(Role.ASSISTANT, "Sorry"),
                turn(Role.USER, "Hi")
            );

            assertSoftly(softly -> {
                softly.assertThat(classifier.inputs).as("inferences, in order").containsExactly("Hi", "Sorry", "That is toxic");
                softly
                    .assertThat(interruptions)
                    .as("interruptions (status code)")
                    .extracting(ExecutionFailure::statusCode)
                    .containsExactly(400);
            });
        }

        @Test
        void should_inspect_the_windows_of_a_message_from_its_start() {
            var message = distinctCodePoints(1000);

            inspect(RequestPolicy.BLOCK_REQUEST, null, turn(Role.USER, message));

            assertSoftly(softly -> {
                softly
                    .assertThat(classifier.inputs)
                    .as("inferences, in order")
                    .containsExactly(message.substring(0, 500), message.substring(250, 750), message.substring(500, 1000));
                softly.assertThat(interruptions).as("interruptions").isEmpty();
            });
        }

        @Test
        void should_interrupt_the_request_with_the_failure_of_an_inference_that_is_not_the_first_one() {
            inspect(
                RequestPolicy.BLOCK_REQUEST,
                null,
                turn(Role.USER, "Are you " + TruncatingClassifier.NOT_READY + "?"),
                turn(Role.USER, "Tell me a joke")
            );

            assertSoftly(softly -> {
                softly
                    .assertThat(classifier.inputs)
                    .as("inferences, in order")
                    .containsExactly("Tell me a joke", "Are you " + TruncatingClassifier.NOT_READY + "?");
                softly
                    .assertThat(interruptions)
                    .as("interruptions (status code, message)")
                    .extracting(ExecutionFailure::statusCode, ExecutionFailure::message)
                    .containsExactly(tuple(503, "Model is not ready"));
                softly.assertThat(metrics.getAdditionalMetrics()).as("additional metrics").isEmpty();
            });
        }

        @Test
        void should_classify_the_windows_one_after_the_other() {
            classifier = new TruncatingClassifier(Duration.ofMillis(5));

            inspect(RequestPolicy.BLOCK_REQUEST, null, turn(Role.SYSTEM, LONG_SYSTEM_PROMPT), turn(Role.USER, "Tell me a joke"));

            assertSoftly(softly -> {
                softly.assertThat(classifier.inputs).as("inferences").hasSize(1 + windowCount(LONG_SYSTEM_PROMPT));
                softly.assertThat(classifier.maxInFlight()).as("inferences running at the same time, at most").isEqualTo(1);
            });
        }

        @Test
        void should_skip_the_messages_that_carry_no_text() {
            inspect(RequestPolicy.BLOCK_REQUEST, null, turn(Role.SYSTEM, "Be nice"), turn(Role.ASSISTANT, "   "), turn(Role.USER, ""));

            assertSoftly(softly -> {
                softly.assertThat(classifier.inputs).as("inferences").containsExactly("Be nice");
                softly.assertThat(interruptions).as("interruptions").isEmpty();
            });
        }

        @Test
        void should_inspect_the_whole_conversation_whatever_the_custom_prompt_location() {
            inspect(
                new AiPromptGuardRailsConfiguration(
                    "ai-model-text-classification-resource",
                    PromptPreset.CUSTOM_PROMPT,
                    "{#request.jsonContent.prompt}",
                    null,
                    null,
                    RequestPolicy.BLOCK_REQUEST
                ),
                turn(Role.SYSTEM, LONG_SYSTEM_PROMPT),
                turn(Role.USER, "You are toxic")
            );

            assertSoftly(softly -> {
                softly.assertThat(classifier.inputs).as("inferences").singleElement().isEqualTo("You are toxic");
                softly
                    .assertThat(interruptions)
                    .as("interruptions (status code, message)")
                    .extracting(ExecutionFailure::statusCode, ExecutionFailure::message)
                    .containsExactly(tuple(400, "AI prompt validation detected. Reason: [toxic]"));
            });
        }

        @Test
        void should_let_a_friendly_conversation_through() {
            inspect(RequestPolicy.BLOCK_REQUEST, null, turn(Role.SYSTEM, "Be nice"), turn(Role.USER, "Tell me a joke"));

            assertSoftly(softly -> {
                softly.assertThat(classifier.inputs).as("inferences, in order").containsExactly("Tell me a joke", "Be nice");
                softly.assertThat(interruptions).as("interruptions").isEmpty();
                softly.assertThat(metrics.getAdditionalMetrics()).as("additional metrics").isEmpty();
            });
        }
    }

    @Nested
    class LogRequest {

        @Test
        void should_stop_once_every_configured_content_check_is_flagged() {
            inspect(
                RequestPolicy.LOG_REQUEST,
                "toxic,obscene",
                turn(Role.SYSTEM, "Never insult anyone"),
                turn(Role.USER, "That is obscene"),
                turn(Role.ASSISTANT, "Sorry"),
                turn(Role.USER, "You are toxic")
            );

            assertSoftly(softly -> {
                softly
                    .assertThat(classifier.inputs)
                    .as("inferences, in order")
                    .containsExactly("You are toxic", "Sorry", "That is obscene");
                softly.assertThat(interruptions).as("interruptions").isEmpty();
                softly
                    .assertThat(metrics.getAdditionalMetrics())
                    .as("additional metrics")
                    .haveExactly(1, keyword("keyword_action", "request-logged"))
                    .haveExactly(1, violations("toxic", "obscene"));
            });
        }

        @Test
        void should_inspect_every_window_when_no_content_check_is_configured() {
            inspect(
                RequestPolicy.LOG_REQUEST,
                null,
                turn(Role.SYSTEM, "Be nice"),
                turn(Role.USER, "That is an insult"),
                turn(Role.USER, "That is obscene"),
                turn(Role.USER, "You are toxic")
            );

            assertSoftly(softly -> {
                softly
                    .assertThat(classifier.inputs)
                    .as("inferences, in order")
                    .containsExactly("You are toxic", "That is obscene", "That is an insult", "Be nice");
                softly.assertThat(interruptions).as("interruptions").isEmpty();
                softly
                    .assertThat(metrics.getAdditionalMetrics())
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
                turn(Role.SYSTEM, LONG_SYSTEM_PROMPT),
                turn(Role.USER, "Tell me a joke"),
                turn(Role.ASSISTANT, "Why did the chicken cross the road?")
            );

            assertSoftly(softly -> {
                softly
                    .assertThat(classifier.inputs)
                    .as("inferences")
                    .hasSize(2 + windowCount(LONG_SYSTEM_PROMPT))
                    .startsWith("Why did the chicken cross the road?", "Tell me a joke");
                softly.assertThat(interruptions).as("interruptions").isEmpty();
                softly.assertThat(metrics.getAdditionalMetrics()).as("additional metrics").isEmpty();
            });
        }

        @Test
        void should_keep_the_highest_score_of_a_label_over_the_inferences() {
            inspect(
                RequestPolicy.LOG_REQUEST,
                "toxic,obscene",
                turn(Role.SYSTEM, "Be nice"),
                turn(Role.USER, "You are toxic"),
                turn(Role.USER, "Tell me a joke")
            );

            assertSoftly(softly -> {
                softly
                    .assertThat(classifier.inputs)
                    .as("inferences, in order: toxic scored low, then high, then low again")
                    .containsExactly("Tell me a joke", "You are toxic", "Be nice");
                softly.assertThat(interruptions).as("interruptions").isEmpty();
                softly
                    .assertThat(metrics.getAdditionalMetrics())
                    .as("additional metrics")
                    .haveExactly(1, keyword("keyword_action", "request-logged"))
                    .haveExactly(1, keyword("keyword_content_violations", "toxic"));
            });
        }

        @Test
        void should_log_the_violations_of_a_toxic_last_user_message_behind_a_long_system_message() {
            inspect(RequestPolicy.LOG_REQUEST, "toxic,obscene", turn(Role.SYSTEM, LONG_SYSTEM_PROMPT), turn(Role.USER, "You are toxic"));

            assertSoftly(softly -> {
                softly
                    .assertThat(classifier.inputs)
                    .as("inferences")
                    .hasSize(1 + windowCount(LONG_SYSTEM_PROMPT))
                    .startsWith("You are toxic");
                softly.assertThat(interruptions).as("interruptions").isEmpty();
                softly
                    .assertThat(metrics.getAdditionalMetrics())
                    .as("additional metrics")
                    .haveExactly(1, keyword("keyword_action", "request-logged"))
                    .haveExactly(1, keyword("keyword_content_violations", "toxic"));
            });
        }
    }

    private void inspect(RequestPolicy requestPolicy, String contentChecks, LlmContextPart... conversation) {
        inspect(
            new AiPromptGuardRailsConfiguration(
                "ai-model-text-classification-resource",
                PromptPreset.ALL_PROMPTS,
                null,
                contentChecks,
                null,
                requestPolicy
            ),
            conversation
        );
    }

    private void inspect(AiPromptGuardRailsConfiguration configuration, LlmContextPart... conversation) {
        var request = mock(LlmRequest.class);
        when(request.llmParts(any())).thenReturn(List.of(conversation));
        var ctx = context(LlmExecutionContext.class, metrics, interruptions);
        when(ctx.request()).thenReturn(request);

        policy(configuration, classifier).onRequest(ctx).test().awaitDone(5, TimeUnit.SECONDS).assertComplete();
    }

    private static Turn turn(Role role, String content) {
        return new Turn(role, content, null, null, null, null);
    }

    private static Condition<AdditionalMetric> violations(String... expectedLabels) {
        return new Condition<>(
            metric ->
                metric instanceof AdditionalMetric.KeywordMetric(String key, String value) &&
                "keyword_content_violations".equals(key) &&
                Set.of(value.split(",")).equals(Set.of(expectedLabels)),
            "content violations %s, in any order",
            Arrays.toString(expectedLabels)
        );
    }
}

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

import static io.gravitee.policy.ai.prompt.guard.rails.TestFixtures.results;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.gravitee.policy.ai.prompt.guard.rails.configuration.RequestPolicy;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.Condition;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class VerdictTest {

    private static final double THRESHOLD = 0.5;

    private static final Condition<Verdict> CONCLUSIVE = new Condition<>(Verdict::isConclusive, "conclusive");

    @Nested
    class Detected {

        @Test
        void should_keep_a_label_detected_by_one_inference_whatever_the_others_score() {
            var verdict = rules(Set.of(), RequestPolicy.LOG_REQUEST)
                .noDetection()
                .merge(results(Map.of("toxic", 0.2F)))
                .merge(results(Map.of("toxic", 0.9F)))
                .merge(results(Map.of("toxic", 0.1F)));

            assertThat(verdict.detected()).as("labels detected once toxic rose then fell").containsExactly("toxic");
        }

        @Test
        void should_add_up_the_labels_detected_by_each_inference() {
            var verdict = rules(Set.of(), RequestPolicy.LOG_REQUEST)
                .noDetection()
                .merge(results(Map.of("toxic", 0.9F, "obscene", 0.1F)))
                .merge(results(Map.of("toxic", 0.1F, "obscene", 0.9F)));

            assertThat(verdict.detected()).as("labels detected over two inferences").containsExactlyInAnyOrder("toxic", "obscene");
        }

        @Test
        void should_only_detect_the_labels_scored_above_the_sensitivity_threshold() {
            var verdict = rules(Set.of(), RequestPolicy.LOG_REQUEST).noDetection().merge(results(Map.of("toxic", 0.5F, "obscene", 0.51F)));

            assertThat(verdict.detected()).as("labels detected above %s", THRESHOLD).containsExactly("obscene");
        }

        @Test
        void should_only_detect_the_configured_content_checks() {
            var flagged = results(Map.of("toxic", 0.9F, "obscene", 0.9F));

            assertSoftly(softly -> {
                softly
                    .assertThat(rules(Set.of("toxic"), RequestPolicy.LOG_REQUEST).noDetection().merge(flagged).detected())
                    .as("labels detected with toxic as the only content check")
                    .containsExactly("toxic");
                softly
                    .assertThat(rules(Set.of(), RequestPolicy.LOG_REQUEST).noDetection().merge(flagged).detected())
                    .as("labels detected without any content check")
                    .containsExactlyInAnyOrder("toxic", "obscene");
            });
        }

        @Test
        void should_detect_nothing_before_any_result_is_merged() {
            assertThat(rules(Set.of(), RequestPolicy.BLOCK_REQUEST).noDetection().detected()).as("labels detected").isEmpty();
        }
    }

    @Nested
    class BlockRequest {

        @Test
        void should_be_conclusive_as_soon_as_a_label_is_detected() {
            var verdict = rules(Set.of("toxic", "obscene"), RequestPolicy.BLOCK_REQUEST).noDetection();

            assertSoftly(softly -> {
                softly.assertThat(verdict).as("verdict before any result").isNot(CONCLUSIVE);
                softly
                    .assertThat(verdict.merge(results(Map.of("toxic", 0.1F, "obscene", 0.1F))))
                    .as("verdict without detection")
                    .isNot(CONCLUSIVE);
                softly
                    .assertThat(verdict.merge(results(Map.of("toxic", 0.9F, "obscene", 0.1F))))
                    .as("verdict once toxic is detected")
                    .is(CONCLUSIVE);
            });
        }
    }

    @Nested
    class LogRequest {

        @Test
        void should_be_conclusive_once_every_configured_content_check_is_detected() {
            var verdict = rules(Set.of("toxic", "obscene"), RequestPolicy.LOG_REQUEST)
                .noDetection()
                .merge(results(Map.of("toxic", 0.9F, "obscene", 0.1F, "insult", 0.9F)));

            assertSoftly(softly -> {
                softly.assertThat(verdict).as("verdict once toxic only is detected").isNot(CONCLUSIVE);
                softly
                    .assertThat(verdict.merge(results(Map.of("obscene", 0.9F))))
                    .as("verdict once toxic and obscene are detected")
                    .is(CONCLUSIVE);
            });
        }

        @Test
        void should_never_be_conclusive_without_any_content_check() {
            var verdict = rules(Set.of(), RequestPolicy.LOG_REQUEST).noDetection();

            assertSoftly(softly -> {
                softly.assertThat(verdict).as("verdict before any result").isNot(CONCLUSIVE);
                softly
                    .assertThat(verdict.merge(results(Map.of("toxic", 0.9F, "obscene", 0.9F, "insult", 0.9F))))
                    .as("verdict once every label of the model is detected")
                    .isNot(CONCLUSIVE);
            });
        }
    }

    @Test
    void should_reject_rules_without_any_request_policy() {
        assertThatNullPointerException()
            .as("rules without any request policy")
            .isThrownBy(() -> rules(Set.of(), null))
            .withMessage("requestPolicy is required");
    }

    private static DetectionRules rules(Set<String> contentChecks, RequestPolicy requestPolicy) {
        return new DetectionRules(THRESHOLD, contentChecks, requestPolicy);
    }
}

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

import static io.gravitee.policy.ai.prompt.guard.rails.TestFixtures.classifierResults;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.gravitee.policy.ai.prompt.guard.rails.configuration.RequestPolicy;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DetectionRulesTest {

    private static final double THRESHOLD = 0.5;

    @Test
    void should_require_a_request_policy() {
        assertThatNullPointerException()
            .as("rules without any request policy")
            .isThrownBy(() -> new DetectionRules(THRESHOLD, Set.of(), null))
            .withMessage("A request policy is required");
    }

    @Nested
    class DetectedIn {

        @Test
        void should_only_detect_the_labels_scored_above_the_sensitivity_threshold() {
            var rules = new DetectionRules(THRESHOLD, Set.of(), RequestPolicy.LOG_REQUEST);

            assertThat(rules.detectedIn(classifierResults(Map.of("toxic", 0.5F, "obscene", 0.51F))))
                .as("labels detected above %s", THRESHOLD)
                .containsExactly("obscene");
        }

        @Test
        void should_only_detect_the_configured_content_checks() {
            var flagged = classifierResults(Map.of("toxic", 0.9F, "obscene", 0.9F));

            assertSoftly(softly -> {
                softly
                    .assertThat(new DetectionRules(THRESHOLD, Set.of("toxic"), RequestPolicy.LOG_REQUEST).detectedIn(flagged))
                    .as("labels detected with toxic as the only content check")
                    .containsExactly("toxic");
                softly
                    .assertThat(new DetectionRules(THRESHOLD, Set.of(), RequestPolicy.LOG_REQUEST).detectedIn(flagged))
                    .as("labels detected without any content check")
                    .containsExactlyInAnyOrder("toxic", "obscene");
            });
        }
    }

    @Nested
    class BlockRequest {

        private final DetectionRules rules = new DetectionRules(THRESHOLD, Set.of("toxic", "obscene"), RequestPolicy.BLOCK_REQUEST);

        @Test
        void should_be_conclusive_as_soon_as_a_label_is_detected() {
            assertSoftly(softly -> {
                softly.assertThat(rules.isConclusive(Set.of())).as("conclusive without detection").isFalse();
                softly.assertThat(rules.isConclusive(Set.of("toxic"))).as("conclusive once toxic is detected").isTrue();
            });
        }

        @Test
        void should_block_a_request_in_which_a_label_is_detected() {
            assertSoftly(softly -> {
                softly.assertThat(rules.decide(Set.of())).as("decision without detection").isEqualTo(new Decision.Pass());
                softly
                    .assertThat(rules.decide(Set.of("toxic")))
                    .as("decision once toxic is detected")
                    .isEqualTo(new Decision.Block(Set.of("toxic")));
            });
        }
    }

    @Nested
    class LogRequest {

        @Test
        void should_be_conclusive_once_every_configured_content_check_is_detected() {
            var rules = new DetectionRules(THRESHOLD, Set.of("toxic", "obscene"), RequestPolicy.LOG_REQUEST);

            assertSoftly(softly -> {
                softly.assertThat(rules.isConclusive(Set.of("toxic"))).as("conclusive once toxic only is detected").isFalse();
                softly
                    .assertThat(rules.isConclusive(Set.of("toxic", "obscene")))
                    .as("conclusive once toxic and obscene are detected")
                    .isTrue();
            });
        }

        @Test
        void should_never_be_conclusive_without_any_content_check() {
            var rules = new DetectionRules(THRESHOLD, Set.of(), RequestPolicy.LOG_REQUEST);

            assertSoftly(softly -> {
                softly.assertThat(rules.isConclusive(Set.of())).as("conclusive without detection").isFalse();
                softly
                    .assertThat(rules.isConclusive(Set.of("toxic", "obscene", "insult")))
                    .as("conclusive once every label of the model is detected")
                    .isFalse();
            });
        }

        @Test
        void should_log_a_request_in_which_a_label_is_detected() {
            var rules = new DetectionRules(THRESHOLD, Set.of(), RequestPolicy.LOG_REQUEST);

            assertSoftly(softly -> {
                softly.assertThat(rules.decide(Set.of())).as("decision without detection").isEqualTo(new Decision.Pass());
                softly
                    .assertThat(rules.decide(Set.of("toxic")))
                    .as("decision once toxic is detected")
                    .isEqualTo(new Decision.Log(Set.of("toxic")));
            });
        }
    }
}

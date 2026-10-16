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

import io.gravitee.reporter.api.v4.metric.AdditionalMetric;
import io.gravitee.resource.ai_model.api.result.ClassifierResults;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.assertj.core.api.Condition;

final class TestFixtures {

    /**
     * The labels of a toxicity classification model.
     */
    static final List<String> TOXICITY_LABELS = List.of("toxic", "severe_toxic", "obscene", "threat", "insult", "identity_hate");

    private TestFixtures() {}

    /**
     * A text of {@code length} code points, all different, so that a window cut from it tells where it starts.
     */
    static String distinctCodePoints(int length) {
        return IntStream.range(0, length)
            .map(index -> 0x4E00 + index)
            .mapToObj(Character::toString)
            .collect(Collectors.joining());
    }

    /**
     * The code points of {@code text} from {@code start} included to {@code end} excluded.
     */
    static String codePoints(String text, int start, int end) {
        return text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end));
    }

    /**
     * The number of windows the given texts are cut into.
     */
    static long windowCount(String... texts) {
        return Stream.of(texts).flatMap(PromptWindows::of).count();
    }

    /**
     * The results of an inference reporting the given score for each label.
     */
    static ClassifierResults results(Map<String, Float> scoreByLabel) {
        return new ClassifierResults(
            scoreByLabel
                .entrySet()
                .stream()
                .map(score -> new ClassifierResults.ClassifierResult(score.getKey(), score.getValue(), null, null, null))
                .toList()
        );
    }

    static Condition<Object> keyword(String expectedKey, String expectedValue) {
        return new Condition<>(
            metric ->
                metric instanceof AdditionalMetric.KeywordMetric(String key, String value) &&
                expectedKey.equals(key) &&
                expectedValue.equals(value),
            "keyword additional metric with key %s and value %s",
            expectedKey,
            expectedValue
        );
    }

    /**
     * The content violations metric, holding the given labels in any order.
     */
    static Condition<Object> violations(String... expectedLabels) {
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

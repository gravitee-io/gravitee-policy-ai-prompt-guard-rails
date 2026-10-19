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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.policy.ai.prompt.guard.rails.configuration.AiPromptGuardRailsConfiguration;
import io.gravitee.policy.ai.prompt.guard.rails.model.AiModelResourceProvider;
import io.gravitee.reporter.api.v4.metric.AdditionalMetric;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.resource.ai_model.api.AiTextModelResource;
import io.gravitee.resource.ai_model.api.result.ClassifierResults;
import io.reactivex.rxjava3.core.Completable;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.assertj.core.api.Condition;

final class TestFixtures {

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
     * The number of inferences a text takes, one per window.
     */
    static int windowCount(String text) {
        return Math.toIntExact(PromptWindows.of(text).count());
    }

    /**
     * The results of an inference scoring each label as given.
     */
    static ClassifierResults classifierResults(Map<String, Float> scoreByLabel) {
        return new ClassifierResults(
            scoreByLabel
                .entrySet()
                .stream()
                .map(score -> new ClassifierResults.ClassifierResult(score.getKey(), score.getValue(), null, null, null))
                .toList()
        );
    }

    /**
     * A policy classifying with {@code classifier} rather than with the resource of the api.
     */
    static AiPromptGuardRailsPolicy policy(
        AiPromptGuardRailsConfiguration configuration,
        AiTextModelResource<?, ?, ClassifierResults> classifier
    ) {
        return new AiPromptGuardRailsPolicy(
            configuration,
            new AiModelResourceProvider(configuration) {
                @Override
                public AiTextModelResource<?, ?, ClassifierResults> get(HttpPlainExecutionContext ctx) {
                    return classifier;
                }
            }
        );
    }

    /**
     * A mocked context recording the metrics of the request into {@code metrics} and its interruptions into
     * {@code interruptions}.
     */
    static <C extends HttpPlainExecutionContext> C context(Class<C> type, Metrics metrics, List<ExecutionFailure> interruptions) {
        var ctx = mock(type);
        when(ctx.metrics()).thenReturn(metrics);
        when(ctx.withLogger(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ctx.interruptWith(any(ExecutionFailure.class))).thenAnswer(invocation -> {
            interruptions.add(invocation.getArgument(0));
            return Completable.complete();
        });
        return ctx;
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
}

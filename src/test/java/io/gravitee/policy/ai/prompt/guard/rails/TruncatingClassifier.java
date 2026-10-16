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

import static io.gravitee.policy.ai.prompt.guard.rails.TestFixtures.TOXICITY_LABELS;
import static io.gravitee.policy.ai.prompt.guard.rails.TestFixtures.results;
import static java.util.function.Function.identity;

import io.gravitee.resource.ai_model.api.AiTextModelResource;
import io.gravitee.resource.ai_model.api.InferenceServiceClient;
import io.gravitee.resource.ai_model.api.model.PromptInput;
import io.gravitee.resource.ai_model.api.result.ClassifierResults;
import io.reactivex.rxjava3.core.Single;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.eventbus.ReplyFailure;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * A classifier reporting a score for each of its labels, high when the label is written in what it sees.
 * Like a real one behind a tokenizer, it only sees the first tokens of its input: here, whitespace separated words.
 * It fails, like a model that is not loaded yet, on an input holding {@value #NOT_READY}.
 * <p>
 * It answers at once, unless given a latency, and records the most inferences it has ever been running at once.
 */
class TruncatingClassifier extends AiTextModelResource<FakeAiModelResourceConfiguration, Object, ClassifierResults> {

    static final String NOT_READY = "not ready";
    private static final int MAX_TOKENS = 510;

    final List<String> inputs = new CopyOnWriteArrayList<>();
    final AtomicInteger mostRunningAtOnce = new AtomicInteger();
    private final AtomicInteger running = new AtomicInteger();
    private Duration latency = Duration.ZERO;

    TruncatingClassifier answeringAfter(Duration latency) {
        this.latency = latency;
        return this;
    }

    @Override
    public Single<ClassifierResults> invokeModel(PromptInput promptInput) {
        inputs.add(promptInput.promptContent());
        var answer = answer(promptInput.promptContent());
        return (latency.isZero() ? answer : answer.delay(latency.toMillis(), TimeUnit.MILLISECONDS)).doOnSubscribe(subscription ->
            mostRunningAtOnce.accumulateAndGet(running.incrementAndGet(), Math::max)
        ).doOnEvent((results, error) -> running.decrementAndGet());
    }

    private Single<ClassifierResults> answer(String content) {
        if (content.contains(NOT_READY)) {
            return Single.error(new ReplyException(ReplyFailure.ERROR, 503, "Model is not ready"));
        }
        var seen = Arrays.stream(content.split("\\s+")).limit(MAX_TOKENS).collect(Collectors.joining(" "));
        return Single.just(
            results(TOXICITY_LABELS.stream().collect(Collectors.toMap(identity(), label -> seen.contains(label) ? 0.9F : 0.01F)))
        );
    }

    @Override
    protected String getModelName() {
        return "truncating-classifier";
    }

    @Override
    protected InferenceServiceClient<PromptInput, Object> buildInferenceServiceClient() {
        return null;
    }
}

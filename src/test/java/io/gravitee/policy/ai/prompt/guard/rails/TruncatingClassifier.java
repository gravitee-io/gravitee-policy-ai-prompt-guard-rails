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
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

import io.gravitee.resource.ai_model.api.AiTextModelResource;
import io.gravitee.resource.ai_model.api.InferenceServiceClient;
import io.gravitee.resource.ai_model.api.model.PromptInput;
import io.gravitee.resource.ai_model.api.result.ClassifierResults;
import io.reactivex.rxjava3.core.Single;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.eventbus.ReplyFailure;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * A classifier reporting a score for each of its labels, high when the label is written in what it sees.
 * Like a real one behind a tokenizer, it only sees the first tokens of its input: here, whitespace separated words.
 * It fails, like a model that is not loaded yet, on an input holding {@value #NOT_READY}.
 * <p>
 * Words are never more numerous than code points, so the truncation only bites on a text longer than a window:
 * it reproduces a whole conversation classified as a single text, never the end of a window being dropped.
 */
class TruncatingClassifier extends AiTextModelResource<FakeAiModelResourceConfiguration, Object, ClassifierResults> {

    static final String NOT_READY = "not ready";
    private static final int MAX_TOKENS = 510;
    private static final List<String> LABELS = List.of("toxic", "obscene", "insult");

    final List<String> inputs = Collections.synchronizedList(new ArrayList<>());
    private final Duration latency;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();

    TruncatingClassifier() {
        this(Duration.ZERO);
    }

    /**
     * A classifier answering each inference after {@code latency}, as a model running on another thread does.
     */
    TruncatingClassifier(Duration latency) {
        this.latency = latency;
    }

    /**
     * The highest number of inferences that have been running at the same time.
     */
    int maxInFlight() {
        return maxInFlight.get();
    }

    @Override
    public Single<ClassifierResults> invokeModel(PromptInput promptInput) {
        inputs.add(promptInput.promptContent());
        var results = classify(promptInput.promptContent());
        if (!latency.isZero()) {
            results = results.delay(latency.toMillis(), TimeUnit.MILLISECONDS, true);
        }
        return results
            .doOnSubscribe(subscription -> maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max))
            .doOnEvent((classified, error) -> inFlight.decrementAndGet());
    }

    private Single<ClassifierResults> classify(String input) {
        if (input.contains(NOT_READY)) {
            return Single.error(new ReplyException(ReplyFailure.ERROR, 503, "Model is not ready"));
        }
        var seen = Arrays.stream(input.split("\\s+")).limit(MAX_TOKENS).collect(Collectors.joining(" "));
        return Single.just(classifierResults(LABELS.stream().collect(toMap(identity(), label -> seen.contains(label) ? 0.9F : 0.01F))));
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

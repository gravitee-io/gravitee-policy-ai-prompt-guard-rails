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

import io.gravitee.resource.ai_model.api.AiTextModelResource;
import io.gravitee.resource.ai_model.api.InferenceServiceClient;
import io.gravitee.resource.ai_model.api.model.PromptInput;
import io.gravitee.resource.ai_model.api.result.ClassifierResults;
import io.reactivex.rxjava3.core.Single;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.eventbus.ReplyFailure;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * A classifier shaped like the real one in sequence mode: every inference reports a score for every label of the
 * model, low unless the label is triggered by what the prompt holds.
 */
public class FakeAiModelResource
    extends AiTextModelResource<
        FakeAiModelResourceConfiguration,
        io.gravitee.inference.api.classifier.ClassifierResults,
        io.gravitee.resource.ai_model.api.result.ClassifierResults
    > {

    private static final List<String> LABELS = List.of("toxic", "severe_toxic", "obscene", "threat", "insult", "identity_hate");
    private static final float LOW_SCORE = 0.01F;

    @Override
    public void doStart() {}

    @Override
    public void doStop() {}

    @Override
    public Single<ClassifierResults> invokeModel(PromptInput promptInput) {
        var content = promptInput.promptContent();
        if (content.contains("not ready")) {
            return Single.error(new ReplyException(ReplyFailure.ERROR, 503, "Model is not ready"));
        }

        var scores = new LinkedHashMap<String, Float>();
        LABELS.forEach(label -> scores.put(label, LOW_SCORE));
        if (content.contains("toxic") || content.contains("bullsh*t") || content.contains("hello!")) {
            scores.merge("toxic", 0.9F, Math::max);
        }
        if (content.contains("identity_hate")) {
            scores.merge("identity_hate", 0.8F, Math::max);
        }
        if (content.contains("obscene") || content.contains("bullsh*t")) {
            scores.merge("obscene", 0.2F, Math::max);
        }
        if (content.contains("hello!")) {
            scores.merge("obscene", 0.15F, Math::max);
        }

        return Single.just(classifierResults(scores));
    }

    @Override
    protected String getModelName() {
        return "";
    }

    @Override
    protected InferenceServiceClient<PromptInput, io.gravitee.inference.api.classifier.ClassifierResults> buildInferenceServiceClient() {
        return null;
    }
}

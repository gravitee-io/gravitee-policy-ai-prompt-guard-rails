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

import io.gravitee.resource.ai_model.api.AiTextClassificationModelResource;
import io.gravitee.resource.ai_model.api.ClassifierResults;
import io.gravitee.resource.ai_model.api.model.PromptInput;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;

public class FakeAiModelResource extends AiTextClassificationModelResource<FakeAiModelResourceConfiguration> {

    @Override
    public Single<ClassifierResults> invokeModel(PromptInput promptInput) {
        var result = new ArrayList<ClassifierResults.ClassifierResult>();

        if (promptInput.promptContent().contains("toxic")) {
            result.add(new ClassifierResults.ClassifierResult("toxic", 0.9F, "token", 0, 1));
        }
        if (promptInput.promptContent().contains("identity_hate")) {
            result.add(new ClassifierResults.ClassifierResult("identity_hate", 0.8F, "token", 0, 1));
        }
        if (promptInput.promptContent().contains("obscene")) {
            result.add(new ClassifierResults.ClassifierResult("obscene", 0.2F, "token", 0, 1));
        }

        return Single.just(new ClassifierResults(result));
    }
}

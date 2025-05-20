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

import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.gateway.reactive.api.policy.http.HttpPolicy;
import io.gravitee.policy.ai.prompt.guard.rails.configuration.AiPromptGuardRailsConfiguration;
import io.gravitee.policy.ai.prompt.guard.rails.configuration.RequestPolicy;
import io.gravitee.policy.ai.prompt.guard.rails.model.AiModelResourceProvider;
import io.gravitee.policy.api.annotations.RequireResource;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.resource.ai_model.api.ClassifierResults;
import io.gravitee.resource.ai_model.api.model.PromptInput;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.CompletableSource;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequireResource
public class AiPromptGuardRailsPolicy implements HttpPolicy {

    private final AiPromptGuardRailsConfiguration configuration;
    private final AiModelResourceProvider modelResourceProvider;

    public AiPromptGuardRailsPolicy(AiPromptGuardRailsConfiguration configuration) {
        this.configuration = configuration;
        this.modelResourceProvider = new AiModelResourceProvider(configuration);
    }

    @Override
    public String id() {
        return "ai-prompt-guard-rails";
    }

    @Override
    public Completable onRequest(HttpPlainExecutionContext ctx) {
        return ctx.request().bodyOrEmpty().flatMapCompletable(body -> checkContent(ctx));
    }

    private CompletableSource checkContent(HttpPlainExecutionContext ctx) {
        var templateEngine = ctx.getTemplateEngine();

        var sensitivityThreshold = configuration.getSensitivityThreshold();
        var promptContent = templateEngine.eval(configuration.promptLocation(), String.class).toSingle();
        var aiModelResource = modelResourceProvider.get(ctx);

        if (aiModelResource == null) {
            return ctx.interruptWith(new ExecutionFailure(500).message("AI Model Text Classification resource incorrectly configured"));
        }

        return promptContent.flatMapCompletable(prompt ->
            aiModelResource
                .invokeModel(new PromptInput(prompt))
                .flatMapCompletable(classifierResults -> {
                    var detectedContentTypes = detectClassifierResultContentTypes(classifierResults, sensitivityThreshold);
                    if (configuration.parseContentChecks().stream().anyMatch(detectedContentTypes::contains)) {
                        logMetrics(detectedContentTypes, ctx, configuration.requestPolicy().getAction());
                        if (configuration.requestPolicy().equals(RequestPolicy.BLOCK_REQUEST)) {
                            return ctx.interruptWith(
                                new ExecutionFailure(400)
                                    .message(String.format("AI prompt validation detected. Reason: %s", detectedContentTypes))
                            );
                        }
                    }
                    return Completable.complete();
                })
                .doOnError(throwable -> log.error("Fail to analyze prompt", throwable))
        );
    }

    private void logMetrics(Set<String> detectedCategories, HttpPlainExecutionContext ctx, String action) {
        var metrics = ctx.metrics();
        metrics.putAdditionalKeywordMetric("keyword_content_violations", detectedCategories.toString());
        metrics.putAdditionalKeywordMetric("keyword_action", action);
    }

    private Set<String> detectClassifierResultContentTypes(ClassifierResults classifierResults, Double sensitivityThreshold) {
        return classifierResults
            .results()
            .stream()
            .filter(classifierResult -> classifierResult.score() > sensitivityThreshold)
            .map(ClassifierResults.ClassifierResult::label)
            .collect(Collectors.toSet());
    }
}

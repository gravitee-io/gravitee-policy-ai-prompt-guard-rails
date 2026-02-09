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
import io.gravitee.resource.ai_model.api.model.PromptInput;
import io.gravitee.resource.ai_model.api.result.ClassifierResults;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.CompletableSource;
import io.vertx.core.eventbus.ReplyException;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequireResource
public class AiPromptGuardRailsPolicy implements HttpPolicy {

    private static final String UNEXPECTED_ERROR = "UNEXPECTED_ERROR";
    private static final String CONFIGURATION_ISSUE = "CONFIGURATION_ISSUE";
    private final AiPromptGuardRailsConfiguration configuration;
    private final AiModelResourceProvider modelResourceProvider;

    public AiPromptGuardRailsPolicy(AiPromptGuardRailsConfiguration configuration) {
        this(configuration, new AiModelResourceProvider(configuration));
    }

    public AiPromptGuardRailsPolicy(AiPromptGuardRailsConfiguration configuration, AiModelResourceProvider modelResourceProvider) {
        this.configuration = configuration;
        this.modelResourceProvider = modelResourceProvider;
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
            return ctx.interruptWith(
                new ExecutionFailure(500).key(CONFIGURATION_ISSUE).message("AI Model Text Classification resource incorrectly configured")
            );
        }

        return promptContent.flatMapCompletable(prompt ->
            aiModelResource
                .invokeModel(new PromptInput(prompt))
                .flatMapCompletable(classifierResults -> {
                    var detectedContentTypes = detectClassifierResultContentTypes(classifierResults, sensitivityThreshold);
                    if (configuration.parseContentChecks().stream().anyMatch(detectedContentTypes::contains)) {
                        logMetrics(detectedContentTypes, ctx, configuration.requestPolicy().getAction());
                        if (RequestPolicy.BLOCK_REQUEST.equals(configuration.requestPolicy())) {
                            return Completable.error(new BlockQueryException(detectedContentTypes));
                        }
                    }
                    return Completable.complete();
                })
                .onErrorResumeNext(throwable ->
                    switch (throwable) {
                        case BlockQueryException e -> ctx.interruptWith(new ExecutionFailure(400).message(e.getMessage()));
                        case ReplyException replyException -> ctx.interruptWith(adaptReplyException(replyException));
                        default -> ctx.interruptWith(
                            new ExecutionFailure(500).message("Unexpected error occurred").cause(throwable).key(UNEXPECTED_ERROR)
                        );
                    }
                )
        );
    }

    private void logMetrics(Set<String> detectedCategories, HttpPlainExecutionContext ctx, String action) {
        ctx
            .metrics()
            .putAdditionalKeywordMetric("keyword_content_violations", String.join(",", detectedCategories))
            .putAdditionalKeywordMetric("keyword_action", action);
    }

    private Set<String> detectClassifierResultContentTypes(ClassifierResults classifierResults, Double sensitivityThreshold) {
        return classifierResults
            .results()
            .stream()
            .filter(classifierResult -> classifierResult.score() > sensitivityThreshold)
            .map(ClassifierResults.ClassifierResult::label)
            .collect(Collectors.toSet());
    }

    private ExecutionFailure adaptReplyException(ReplyException replyException) {
        return new ExecutionFailure(replyException.failureCode()).message(replyException.getMessage()).cause(replyException);
    }
}

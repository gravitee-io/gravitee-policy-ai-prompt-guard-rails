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
import io.gravitee.gateway.reactive.api.context.llm.LlmContextPart;
import io.gravitee.gateway.reactive.api.context.llm.LlmExecutionContext;
import io.gravitee.gateway.reactive.api.policy.http.HttpPolicy;
import io.gravitee.gateway.reactive.api.policy.llm.LlmPolicy;
import io.gravitee.policy.ai.prompt.guard.rails.configuration.AiPromptGuardRailsConfiguration;
import io.gravitee.policy.ai.prompt.guard.rails.configuration.RequestPolicy;
import io.gravitee.policy.ai.prompt.guard.rails.model.AiModelResourceProvider;
import io.gravitee.policy.api.annotations.RequireResource;
import io.gravitee.resource.ai_model.api.model.PromptInput;
import io.gravitee.resource.ai_model.api.result.ClassifierResults;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.CompletableSource;
import io.reactivex.rxjava3.core.Maybe;
import io.vertx.core.eventbus.ReplyException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.CustomLog;
import org.jspecify.annotations.NonNull;

/**
 * <h2>Two exchanges, one policy</h2>
 * On an llm api the gateway normalizes the exchange before any policy runs, so the prompts are read from the
 * conversation it exposes — no provider wire format is parsed here. On any other api the very same instance
 * keeps the plain http behavior: the prompt is extracted from the configured location expression, which is
 * the only thing to read when there is no conversation. Which of the two the gateway calls is decided by the
 * reactor that deployed the api, so both interfaces are implemented and neither path knows about the other.
 */
@CustomLog
@RequireResource
public class AiPromptGuardRailsPolicy implements HttpPolicy, LlmPolicy {

    private static final String PROMPT_SEPARATOR = "\n\n";

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

    /**
     * Entry point for the request phase of a plain http api: the prompt is read from the location expression.
     */
    @Override
    public Completable onRequest(HttpPlainExecutionContext ctx) {
        return ctx
            .request()
            .bodyOrEmpty()
            .flatMapCompletable(body ->
                configuration.isCustomPrompt()
                    ? checkContent(ctx, ctx.getTemplateEngine().eval(configuration.promptLocation(), String.class))
                    : ctx.interruptWith(new ExecutionFailure(500).key(CONFIGURATION_ISSUE).message("Impossible to inspect query"))
            );
    }

    /**
     * Entry point for the request phase of an llm api: the prompts are read from the conversation the gateway
     * normalized, rather than from the provider body it was decoded from. The location expression is ignored
     * there, so an api configured with {@code CUSTOM_PROMPT} has its whole conversation inspected.
     * <p>
     * Deferred, because this method is called while the policy chain is being built: read there, the request
     * has not been normalized yet.
     */
    @Override
    public Completable onRequest(LlmExecutionContext ctx) {
        return Completable.defer(() -> checkContent(ctx, prompts(ctx)));
    }

    private Maybe<String> prompts(LlmExecutionContext ctx) {
        return Maybe.fromCallable(() -> textOf(ctx.request().llmParts(configuration.promptCriteria()))).filter(text -> !text.isEmpty());
    }

    /**
     * All the text the selected parts carry, in the order the model will receive it.
     * <p>
     * Text only: an inline image or an audio attachment is transmitted to the model but is not represented
     * here, so a verdict based on this value is a verdict on a partial view of the request.
     */
    static String textOf(List<LlmContextPart> parts) {
        return parts
            .stream()
            .map(LlmContextPart::textContent)
            .filter(text -> !text.isBlank())
            .collect(Collectors.joining(PROMPT_SEPARATOR));
    }

    private CompletableSource checkContent(HttpPlainExecutionContext ctx, Maybe<String> promptToInspect) {
        var sensitivityThreshold = configuration.getSensitivityThreshold();
        var aiModelResource = modelResourceProvider.get(ctx);

        if (aiModelResource == null) {
            return ctx.interruptWith(
                new ExecutionFailure(500).key(CONFIGURATION_ISSUE).message("AI Model Text Classification resource incorrectly configured")
            );
        }

        return promptToInspect.flatMapCompletable(prompt ->
            aiModelResource
                .invokeModel(new PromptInput(prompt))
                .flatMapCompletable(classifierResults -> {
                    ctx.withLogger(log).debug("Result of analyzing prompt: '{}': {}", prompt, classifierResults);
                    Set<String> allDetected = detectClassifierResultContentTypes(classifierResults, sensitivityThreshold);
                    var detectedContentTypes = configuration.parseContentChecks().isEmpty() ? allDetected : filteredWithConfig(allDetected);

                    if (!detectedContentTypes.isEmpty()) {
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

    private @NonNull Set<String> filteredWithConfig(Set<String> allDetected) {
        return allDetected.stream().filter(configuration.parseContentChecks()::contains).collect(Collectors.toSet());
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

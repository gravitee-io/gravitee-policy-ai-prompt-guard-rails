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
import io.gravitee.policy.ai.prompt.guard.rails.model.AiModelResourceProvider;
import io.gravitee.policy.api.annotations.RequireResource;
import io.gravitee.resource.ai_model.api.AiTextModelResource;
import io.gravitee.resource.ai_model.api.model.PromptInput;
import io.gravitee.resource.ai_model.api.result.ClassifierResults;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.CompletableSource;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.vertx.core.eventbus.ReplyException;
import java.util.List;
import java.util.Set;
import lombok.CustomLog;

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

    private static final String UNEXPECTED_ERROR = "UNEXPECTED_ERROR";
    private static final String CONFIGURATION_ISSUE = "CONFIGURATION_ISSUE";
    private final AiPromptGuardRailsConfiguration configuration;
    private final AiModelResourceProvider modelResourceProvider;
    private final Verdict verdict;

    public AiPromptGuardRailsPolicy(AiPromptGuardRailsConfiguration configuration) {
        this(configuration, new AiModelResourceProvider(configuration));
    }

    public AiPromptGuardRailsPolicy(AiPromptGuardRailsConfiguration configuration, AiModelResourceProvider modelResourceProvider) {
        this.configuration = configuration;
        this.modelResourceProvider = modelResourceProvider;
        this.verdict = Verdict.of(configuration);
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
                    ? checkContent(ctx, ctx.getTemplateEngine().eval(configuration.promptLocation(), String.class).toFlowable())
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

    /**
     * The texts of the conversation, in the order they are inspected: from its last message back to its first one,
     * the end of a conversation being where an instruction is the most likely to be found.
     */
    private Flowable<String> prompts(LlmExecutionContext ctx) {
        return Flowable.fromIterable(textsOf(ctx.request().llmParts(configuration.promptCriteria())).reversed());
    }

    /**
     * The text of each selected part, in the order the model will receive them.
     * <p>
     * Kept apart rather than joined: a classifier only reads the first tokens of its input, so one long part
     * would hide the ones that follow it.
     * <p>
     * Text only: an inline image or an audio attachment is transmitted to the model but is not represented
     * here, so a verdict based on this value is a verdict on a partial view of the request.
     */
    static List<String> textsOf(List<LlmContextPart> parts) {
        return parts
            .stream()
            .map(LlmContextPart::textContent)
            .filter(text -> !text.isBlank())
            .toList();
    }

    /**
     * Inspects the given texts, in the order they are given. No text, no inference: the request goes on.
     */
    private CompletableSource checkContent(HttpPlainExecutionContext ctx, Flowable<String> textsToInspect) {
        var aiModelResource = modelResourceProvider.get(ctx);

        if (aiModelResource == null) {
            return ctx.interruptWith(
                new ExecutionFailure(500).key(CONFIGURATION_ISSUE).message("AI Model Text Classification resource incorrectly configured")
            );
        }

        return detectContentTypes(ctx, aiModelResource, textsToInspect)
            .flatMapCompletable(detectedContentTypes -> {
                logMetrics(detectedContentTypes, ctx, verdict.requestPolicy().getAction());
                return switch (verdict.requestPolicy()) {
                    case BLOCK_REQUEST -> Completable.error(new BlockQueryException(detectedContentTypes));
                    case LOG_REQUEST -> Completable.complete();
                };
            })
            .onErrorResumeNext(throwable ->
                switch (throwable) {
                    case BlockQueryException e -> ctx.interruptWith(new ExecutionFailure(400).message(e.getMessage()));
                    case ReplyException replyException -> ctx.interruptWith(adaptReplyException(replyException));
                    default -> ctx.interruptWith(
                        new ExecutionFailure(500).message("Unexpected error occurred").cause(throwable).key(UNEXPECTED_ERROR)
                    );
                }
            );
    }

    /**
     * The content types detected in the texts, if any.
     * <p>
     * Each text is classified window by window, from its start to its end (see {@link PromptWindows}), and the
     * score of a label is the highest one seen so far. The windows are classified one after the other, never
     * concurrently, so that a request does not send a burst of inferences to a model shared by the whole gateway.
     * Their number grows with the length of the texts and is not capped: a long conversation occupies the model,
     * and delays its own request, for as many inferences as it has windows. The inspection stops as soon as the
     * verdict cannot change anymore (see {@link Verdict#isConclusive()}).
     */
    private Maybe<Set<String>> detectContentTypes(
        HttpPlainExecutionContext ctx,
        AiTextModelResource<?, ?, ClassifierResults> aiModelResource,
        Flowable<String> texts
    ) {
        return texts
            .concatMapStream(PromptWindows::of)
            .concatMapSingle(window ->
                aiModelResource
                    .invokeModel(new PromptInput(window))
                    .doOnSuccess(classifierResults ->
                        ctx.withLogger(log).debug("Result of analyzing prompt: '{}': {}", window, classifierResults)
                    )
            )
            .scan(verdict, Verdict::merge)
            .takeUntil(Verdict::isConclusive)
            .lastElement()
            .map(Verdict::detected)
            .filter(detectedContentTypes -> !detectedContentTypes.isEmpty());
    }

    private void logMetrics(Set<String> detectedCategories, HttpPlainExecutionContext ctx, String action) {
        ctx
            .metrics()
            .putAdditionalKeywordMetric("keyword_content_violations", String.join(",", detectedCategories))
            .putAdditionalKeywordMetric("keyword_action", action);
    }

    private ExecutionFailure adaptReplyException(ReplyException replyException) {
        return new ExecutionFailure(replyException.failureCode()).message(replyException.getMessage()).cause(replyException);
    }
}

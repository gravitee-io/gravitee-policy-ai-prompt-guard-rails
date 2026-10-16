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

import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.gateway.reactive.api.context.llm.LlmRequestInspector;
import io.gravitee.gateway.reactive.api.policy.http.HttpPolicy;
import io.gravitee.policy.ai.prompt.guard.rails.configuration.AiPromptGuardRailsConfiguration;
import io.gravitee.policy.ai.prompt.guard.rails.model.AiModelResourceProvider;
import io.gravitee.policy.api.annotations.RequireResource;
import io.gravitee.resource.ai_model.api.AiTextModelResource;
import io.gravitee.resource.ai_model.api.model.PromptInput;
import io.gravitee.resource.ai_model.api.result.ClassifierResults;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.vertx.core.eventbus.ReplyException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.CustomLog;

@CustomLog
@RequireResource
public class AiPromptGuardRailsPolicy implements HttpPolicy {

    private static final String ATTR_LLM_INSPECTOR = "llm.inspector";

    private static final String UNEXPECTED_ERROR = "UNEXPECTED_ERROR";
    private static final String CONFIGURATION_ISSUE = "CONFIGURATION_ISSUE";
    private final AiPromptGuardRailsConfiguration configuration;
    private final AiModelResourceProvider modelResourceProvider;
    private final DetectionRules rules;

    public AiPromptGuardRailsPolicy(AiPromptGuardRailsConfiguration configuration) {
        this(configuration, new AiModelResourceProvider(configuration));
    }

    public AiPromptGuardRailsPolicy(AiPromptGuardRailsConfiguration configuration, AiModelResourceProvider modelResourceProvider) {
        this.configuration = configuration;
        this.modelResourceProvider = modelResourceProvider;
        this.rules = DetectionRules.of(configuration);
    }

    @Override
    public String id() {
        return "ai-prompt-guard-rails";
    }

    @Override
    public Completable onRequest(HttpPlainExecutionContext ctx) {
        return ctx
            .request()
            .bodyOrEmpty()
            .flatMapCompletable(body -> checkContent(ctx, body));
    }

    private Completable checkContent(HttpPlainExecutionContext ctx, Buffer body) {
        var aiModelResource = modelResourceProvider.get(ctx);
        if (aiModelResource == null) {
            return ctx.interruptWith(
                new ExecutionFailure(500).key(CONFIGURATION_ISSUE).message("AI Model Text Classification resource incorrectly configured")
            );
        }

        return textsToInspect(ctx, body)
            .map(texts -> inspect(ctx, aiModelResource, texts))
            .orElseGet(() -> ctx.interruptWith(new ExecutionFailure(500).key(CONFIGURATION_ISSUE).message("Impossible to inspect query")));
    }

    /**
     * The texts of the request, in the order they are inspected, if the request can be inspected at all.
     * <p>
     * The texts the llm request inspector extracts are inspected from the last one back to the first one, the end of
     * a conversation being where an instruction is the most likely to be found. They are kept apart rather than
     * joined: a classifier only reads the first tokens of its input, so one long text would hide the ones that
     * follow it.
     */
    private Optional<Flowable<String>> textsToInspect(HttpPlainExecutionContext ctx, Buffer body) {
        LlmRequestInspector.PromptQuery promptQuery = configuration.getPromptQuery();
        LlmRequestInspector inspector = ctx.getAttribute(ATTR_LLM_INSPECTOR);
        if (inspector != null) {
            return Optional.of(inspector.prompt(ctx, promptQuery, body).flattenAsFlowable(List::reversed));
        }
        if (promptQuery instanceof LlmRequestInspector.PromptQuery.CustomPrompt(String expression)) {
            return Optional.of(ctx.getTemplateEngine().eval(expression, String.class).toFlowable());
        }
        return Optional.empty();
    }

    /**
     * Blocks or logs the request when content is detected in its texts. Blank texts are skipped: without any text
     * left, there is no inference and the request goes on.
     */
    private Completable inspect(
        HttpPlainExecutionContext ctx,
        AiTextModelResource<?, ?, ClassifierResults> aiModelResource,
        Flowable<String> texts
    ) {
        return detect(ctx, aiModelResource, texts.filter(text -> !text.isBlank()))
            .onErrorResumeNext(throwable -> interruptOnFailure(ctx, throwable).toMaybe())
            .flatMapCompletable(verdict -> {
                logMetrics(verdict.detected(), ctx, verdict.rules().requestPolicy().getAction());
                return switch (verdict.rules().requestPolicy()) {
                    case BLOCK_REQUEST -> ctx.interruptWith(
                        new ExecutionFailure(400).message(String.format("AI prompt validation detected. Reason: %s", verdict.detected()))
                    );
                    case LOG_REQUEST -> Completable.complete();
                };
            });
    }

    /**
     * The verdict of the texts, if any content is detected.
     * <p>
     * Each text is classified window by window (see {@link PromptWindows}). The windows are classified one after the
     * other, never concurrently, so that a request does not send a burst of inferences to a model shared by the whole
     * gateway. Their number grows with the length of the texts and is not capped: a long conversation occupies the
     * model, and delays its own request, for as many inferences as it has windows. The inspection stops as soon as
     * the verdict cannot change anymore (see {@link Verdict#isConclusive()}).
     */
    private Maybe<Verdict> detect(
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
            .scan(rules.noDetection(), Verdict::merge)
            .takeUntil(Verdict::isConclusive)
            .lastElement()
            .filter(verdict -> !verdict.detected().isEmpty());
    }

    private Completable interruptOnFailure(HttpPlainExecutionContext ctx, Throwable throwable) {
        return switch (throwable) {
            case ReplyException replyException -> ctx.interruptWith(adaptReplyException(replyException));
            default -> ctx.interruptWith(
                new ExecutionFailure(500).message("Unexpected error occurred").cause(throwable).key(UNEXPECTED_ERROR)
            );
        };
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

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

import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainRequest;
import io.gravitee.policy.ai.prompt.guard.rails.configuration.AiPromptGuardRailsConfiguration;
import io.gravitee.policy.ai.prompt.guard.rails.model.AiModelResourceProvider;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.resource.ai_model.api.AiTextModelResource;
import io.gravitee.resource.ai_model.api.result.ClassifierResults;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Runs the policy on a mocked request against {@link #classifier}, and records the metrics and the interruptions
 * of the request.
 */
class PolicyHarness {

    final TruncatingClassifier classifier = new TruncatingClassifier();
    final Metrics metrics = new Metrics();
    final List<ExecutionFailure> interruptions = new CopyOnWriteArrayList<>();

    void run(AiPromptGuardRailsConfiguration configuration, HttpPlainExecutionContext ctx) {
        var policy = new AiPromptGuardRailsPolicy(
            configuration,
            new AiModelResourceProvider(configuration) {
                @Override
                public AiTextModelResource<?, ?, ClassifierResults> get(HttpPlainExecutionContext ctx) {
                    return classifier;
                }
            }
        );

        policy.onRequest(ctx).test().awaitDone(5, TimeUnit.SECONDS).assertComplete();
    }

    /**
     * The context of a request with a body, without any llm request inspector nor template engine.
     */
    HttpPlainExecutionContext context() {
        var body = Buffer.buffer("{}");
        var request = mock(HttpPlainRequest.class);
        when(request.body()).thenReturn(Maybe.just(body));
        when(request.bodyOrEmpty()).thenReturn(Single.just(body));

        var ctx = mock(HttpPlainExecutionContext.class);
        when(ctx.request()).thenReturn(request);
        when(ctx.metrics()).thenReturn(metrics);
        when(ctx.withLogger(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ctx.interruptWith(any(ExecutionFailure.class))).thenAnswer(invocation -> {
            interruptions.add(invocation.getArgument(0));
            return Completable.complete();
        });
        return ctx;
    }
}

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

import io.gravitee.apim.gateway.tests.sdk.AbstractPolicyTest;
import io.gravitee.apim.gateway.tests.sdk.annotations.GatewayTest;
import io.gravitee.apim.gateway.tests.sdk.connector.EndpointBuilder;
import io.gravitee.apim.gateway.tests.sdk.connector.EntrypointBuilder;
import io.gravitee.apim.gateway.tests.sdk.reporter.FakeReporter;
import io.gravitee.inference.service.InferenceService;
import io.gravitee.plugin.endpoint.EndpointConnectorPlugin;
import io.gravitee.plugin.endpoint.http.proxy.HttpProxyEndpointConnectorFactory;
import io.gravitee.plugin.entrypoint.EntrypointConnectorPlugin;
import io.gravitee.plugin.entrypoint.http.proxy.HttpProxyEntrypointConnectorFactory;
import io.gravitee.plugin.resource.ResourcePlugin;
import io.gravitee.policy.ai.prompt.guard.rails.configuration.AiPromptGuardRailsConfiguration;
import io.gravitee.reporter.api.v4.metric.AdditionalMetric;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.subjects.BehaviorSubject;
import io.vertx.core.buffer.Buffer;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.core.http.HttpClientResponse;
import java.nio.file.Files;
import java.util.Map;
import java.util.NoSuchElementException;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Condition;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;

@Slf4j
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@GatewayTest
class AbstractAiPromptGuardRailsPolicyIntegrationTest
    extends AbstractPolicyTest<AiPromptGuardRailsPolicy, AiPromptGuardRailsConfiguration> {

    BehaviorSubject<Metrics> metricsSubject;

    private static InferenceService inferenceService;

    @Override
    public void configureEntrypoints(Map<String, EntrypointConnectorPlugin<?, ?>> entrypoints) {
        entrypoints.putIfAbsent("http-proxy", EntrypointBuilder.build("http-proxy", HttpProxyEntrypointConnectorFactory.class));
    }

    @Override
    public void configureEndpoints(Map<String, EndpointConnectorPlugin<?, ?>> endpoints) {
        endpoints.putIfAbsent("http-proxy", EndpointBuilder.build("http-proxy", HttpProxyEndpointConnectorFactory.class));
    }

    @Override
    @SneakyThrows
    public void configureResources(Map<String, ResourcePlugin> resources) {
        inferenceService = new InferenceService(getBean(Vertx.class), Files.createTempDirectory("models").toString());
        inferenceService.start();
    }

    @AfterAll
    @SneakyThrows
    static void afterAll() {
        inferenceService.stop();
    }

    @BeforeEach
    void setUp() {
        metricsSubject = BehaviorSubject.create();

        FakeReporter fakeReporter = getBean(FakeReporter.class);
        fakeReporter.setReportableHandler(reportable -> {
            if (reportable instanceof Metrics) {
                metricsSubject.onNext((Metrics) reportable);
                metricsSubject.onComplete();
            }
        });
    }

    protected @NonNull Single<Result> then(Single<Result> clientAsserts) {
        return Single.zip(
            metricsSubject.firstElement().switchIfEmpty(Single.error(new NoSuchElementException("Metrics not available"))),
            clientAsserts,
            Result::new
        );
    }

    protected @NonNull Single<Result> toResult(HttpClientResponse response) {
        return response
            .toFlowable()
            .reduce(Buffer.buffer(), Buffer::appendBuffer)
            .map(responseBody -> new Result(response, responseBody));
    }

    protected Condition<Object> keyword(String expectedKey, String expectedValue) {
        return new Condition<>(
            e ->
                e instanceof AdditionalMetric.KeywordMetric(String key, String value) &&
                expectedKey.equals(key) &&
                expectedValue.equals(value),
            "keyword additionnal metric with key %s and value %s",
            expectedKey,
            expectedValue
        );
    }

    record Result(Metrics metrics, Integer statusCode, Buffer responseBody) {
        Result(HttpClientResponse response, Buffer responseBody) {
            this(null, response.statusCode(), responseBody);
        }

        Result(Metrics metrics, Result result) {
            this(metrics, result.statusCode, result.responseBody);
        }
    }
}

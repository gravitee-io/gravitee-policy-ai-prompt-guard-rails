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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.apim.gateway.tests.sdk.AbstractPolicyTest;
import io.gravitee.apim.gateway.tests.sdk.annotations.DeployApi;
import io.gravitee.apim.gateway.tests.sdk.annotations.GatewayTest;
import io.gravitee.apim.gateway.tests.sdk.connector.EndpointBuilder;
import io.gravitee.apim.gateway.tests.sdk.connector.EntrypointBuilder;
import io.gravitee.apim.gateway.tests.sdk.reporter.FakeReporter;
import io.gravitee.apim.gateway.tests.sdk.resource.ResourceBuilder;
import io.gravitee.definition.model.ExecutionMode;
import io.gravitee.inference.service.InferenceService;
import io.gravitee.plugin.endpoint.EndpointConnectorPlugin;
import io.gravitee.plugin.endpoint.http.proxy.HttpProxyEndpointConnectorFactory;
import io.gravitee.plugin.entrypoint.EntrypointConnectorPlugin;
import io.gravitee.plugin.entrypoint.http.proxy.HttpProxyEntrypointConnectorFactory;
import io.gravitee.plugin.resource.ResourcePlugin;
import io.gravitee.policy.ai.prompt.guard.rails.configuration.AiPromptGuardRailsConfiguration;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.resource.ai_model.TextClassificationAiModelResource;
import io.gravitee.resource.ai_model.configuration.TextClassificationAiModelConfiguration;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.subjects.BehaviorSubject;
import io.vertx.core.http.HttpMethod;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.core.http.HttpClient;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

@Slf4j
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@GatewayTest(v2ExecutionMode = ExecutionMode.V4_EMULATION_ENGINE)
class AiPromptGuardRailsIntegrationTest extends AbstractPolicyTest<AiPromptGuardRailsPolicy, AiPromptGuardRailsConfiguration> {

    BehaviorSubject<Metrics> metricsSubject;

    private InferenceService inferenceService;

    @Override
    public void configureEntrypoints(Map<String, EntrypointConnectorPlugin<?, ?>> entrypoints) {
        entrypoints.putIfAbsent("http-proxy", EntrypointBuilder.build("http-proxy", HttpProxyEntrypointConnectorFactory.class));
    }

    @Override
    public void configureEndpoints(Map<String, EndpointConnectorPlugin<?, ?>> endpoints) {
        endpoints.putIfAbsent("http-proxy", EndpointBuilder.build("http-proxy", HttpProxyEndpointConnectorFactory.class));
    }

    @Override
    public void configureResources(Map<String, ResourcePlugin> resources) {
        resources.putIfAbsent(
            "ai-model-text-classification",
            ResourceBuilder.build(
                "ai-model-text-classification",
                TextClassificationAiModelResource.class,
                TextClassificationAiModelConfiguration.class
            )
        );
    }

    @BeforeEach
    void setUp() throws Exception {
        metricsSubject = BehaviorSubject.create();

        inferenceService = new InferenceService(getBean(Vertx.class));
        inferenceService.start();

        FakeReporter fakeReporter = getBean(FakeReporter.class);
        fakeReporter.setReportableHandler(reportable -> {
            if (reportable instanceof Metrics) {
                metricsSubject.onNext((Metrics) reportable);
            }
        });
    }

    @Test
    @DeployApi({ "/apis/log_request_policy.json" })
    void shouldFlagRequestIfPromptViolationDetected(HttpClient client) throws InterruptedException {
        Thread.sleep(10000);
        wiremock.stubFor(get("/endpoint").willReturn(aResponse().withStatus(200)));

        Completable
            .fromObservable(Observable.timer(15, SECONDS))
            .andThen(
                client
                    .rxRequest(HttpMethod.GET, "/log-request")
                    .flatMap(request ->
                        request.rxSend(
                            """
            {
              "model": "GPT-2000",
              "date": "01-01-2025",
              "prompt": "Nobody asked for your bullsh*t response."
            }"""
                        )
                    )
            )
            .test()
            .awaitDone(20, SECONDS)
            .assertComplete()
            .assertValue(response -> {
                assertThat(response.statusCode()).isEqualTo(200);
                return true;
            })
            .assertNoErrors();
    }

    @Test
    @DeployApi({ "/apis/log_request_policy.json" })
    void shouldIgnoreFlaggingRequestIfPromptViolationNotDetected(HttpClient client) throws InterruptedException {
        wiremock.stubFor(get("/endpoint").willReturn(aResponse().withStatus(200)));

        Completable
            .fromObservable(Observable.timer(15, SECONDS))
            .andThen(
                client
                    .rxRequest(HttpMethod.GET, "/log-request")
                    .flatMap(request ->
                        request.rxSend(
                            """
                        {
                          "model": "GPT-2000",
                          "date": "01-01-2025",
                          "prompt": "This is super friendly message"
                        }"""
                        )
                    )
            )
            .test()
            .awaitDone(20, SECONDS)
            .assertComplete()
            .assertValue(response -> {
                assertThat(response.statusCode()).isEqualTo(200);
                return true;
            })
            .assertNoErrors();
    }

    @Test
    @DeployApi({ "/apis/block_request_policy.json" })
    void shouldBlockRequestIfPromptViolationDetected(HttpClient client) throws InterruptedException {
        wiremock.stubFor(get("/endpoint").willReturn(aResponse().withStatus(200)));

        Completable
            .fromObservable(Observable.timer(15, SECONDS))
            .andThen(
                client
                    .rxRequest(HttpMethod.GET, "/block-request")
                    .flatMap(request ->
                        request.rxSend(
                            """
                                            {
                                              "model": "GPT-2000",
                                              "date": "01-01-2025",
                                              "prompt": "Nobody asked for your bullsh*t response."
                                            }"""
                        )
                    )
                    .flatMapPublisher(response -> {
                        assertThat(response.statusCode()).isEqualTo(400);
                        return response.toFlowable();
                    })
            )
            .test()
            .awaitDone(20, SECONDS)
            .assertComplete()
            .assertValue(responseBody -> {
                assertThat(responseBody).hasToString("AI prompt validation detected. Reason: [toxic]");
                return true;
            })
            .assertNoErrors();
    }

    @Test
    @DeployApi({ "/apis/custom_sensitivity_threshold.json" })
    void shouldChangeSensitivityIfCustomSensitivityThresholdProvided(HttpClient client) throws InterruptedException {
        Thread.sleep(10000);
        wiremock.stubFor(get("/endpoint").willReturn(aResponse().withStatus(200)));

        Completable
            .fromObservable(Observable.timer(15, SECONDS))
            .andThen(
                client
                    .rxRequest(HttpMethod.GET, "/block-request")
                    .flatMap(request ->
                        request.rxSend(
                            """
                        {
                          "model": "GPT-2000",
                          "date": "01-01-2025",
                          "prompt": "Nobody asked for your bullsh*t response."
                        }"""
                        )
                    )
                    .flatMapPublisher(response -> {
                        assertThat(response.statusCode()).isEqualTo(400);
                        return response.toFlowable();
                    })
            )
            .test()
            .awaitDone(20, SECONDS)
            .assertComplete()
            .assertValue(responseBody -> {
                assertThat(responseBody).hasToString("AI prompt validation detected. Reason: [toxic, obscene]");
                return true;
            })
            .assertNoErrors();
    }

    @Test
    @DeployApi({ "/apis/missing_resource.json" })
    void shouldReturnErrorIfResourceIsNotConfiguredProperly(HttpClient client) throws InterruptedException {
        Thread.sleep(10000);
        wiremock.stubFor(get("/endpoint").willReturn(aResponse().withStatus(200)));

        Completable
            .fromObservable(Observable.timer(15, SECONDS))
            .andThen(
                client
                    .rxRequest(HttpMethod.GET, "/missing-resource")
                    .flatMap(request ->
                        request.rxSend(
                            """
                        {
                          "model": "GPT-2000",
                          "date": "01-01-2025",
                          "prompt": "Nobody asked for your bullsh*t response."
                        }"""
                        )
                    )
                    .flatMapPublisher(response -> {
                        assertThat(response.statusCode()).isEqualTo(500);
                        return response.toFlowable();
                    })
            )
            .test()
            .awaitDone(20, SECONDS)
            .assertComplete()
            .assertValue(responseBody -> {
                assertThat(responseBody).hasToString("AI Model Text Classification resource incorrectly configured");
                return true;
            })
            .assertNoErrors();
    }

    @Test
    @DeployApi({ "/apis/broken_content_check_list.json" })
    void shouldHandleParsingIncorrectlyFormattedList(HttpClient client) throws InterruptedException {
        Thread.sleep(10000);
        wiremock.stubFor(get("/endpoint").willReturn(aResponse().withStatus(200)));

        Completable
            .fromObservable(Observable.timer(15, SECONDS))
            .andThen(
                client
                    .rxRequest(HttpMethod.GET, "/broken-content-check")
                    .flatMap(request ->
                        request.rxSend(
                            """
                        {
                          "model": "GPT-2000",
                          "date": "01-01-2025",
                          "prompt": "This is some friendly prompt"
                        }"""
                        )
                    )
            )
            .test()
            .awaitDone(20, SECONDS)
            .assertComplete()
            .assertValue(response -> {
                assertThat(response.statusCode()).isEqualTo(200);
                return true;
            })
            .assertNoErrors();
    }

    @AfterEach
    public void cleanup() throws Exception {
        inferenceService.stop();
    }
}

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
import io.gravitee.plugin.endpoint.EndpointConnectorPlugin;
import io.gravitee.plugin.endpoint.http.proxy.HttpProxyEndpointConnectorFactory;
import io.gravitee.plugin.entrypoint.EntrypointConnectorPlugin;
import io.gravitee.plugin.entrypoint.http.proxy.HttpProxyEntrypointConnectorFactory;
import io.gravitee.plugin.resource.ResourcePlugin;
import io.gravitee.policy.ai.prompt.guard.rails.configuration.AiPromptGuardRailsConfiguration;
import io.gravitee.reporter.api.v4.metric.AdditionalMetric;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.reactivex.rxjava3.subjects.BehaviorSubject;
import io.vertx.core.http.HttpMethod;
import io.vertx.junit5.VertxTestContext;
import io.vertx.rxjava3.core.http.HttpClient;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.AssertionsForClassTypes;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@Slf4j
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@GatewayTest(v2ExecutionMode = ExecutionMode.V4_EMULATION_ENGINE)
class AiPromptGuardRailsIntegrationTest extends AbstractPolicyTest<AiPromptGuardRailsPolicy, AiPromptGuardRailsConfiguration> {

    BehaviorSubject<Metrics> metricsSubject;

    @Override
    public void configureEntrypoints(Map<String, EntrypointConnectorPlugin<?, ?>> entrypoints) {
        entrypoints.putIfAbsent("http-proxy", EntrypointBuilder.build("http-proxy", HttpProxyEntrypointConnectorFactory.class));
    }

    @Override
    public void configureEndpoints(Map<String, EndpointConnectorPlugin<?, ?>> endpoints) {
        endpoints.putIfAbsent("http-proxy", EndpointBuilder.build("http-proxy", HttpProxyEndpointConnectorFactory.class));
    }

    @BeforeEach
    void setUp() {
        metricsSubject = BehaviorSubject.create();

        FakeReporter fakeReporter = getBean(FakeReporter.class);
        fakeReporter.setReportableHandler(reportable -> {
            if (reportable instanceof Metrics) {
                metricsSubject.onNext((Metrics) reportable);
            }
        });
    }

    @Override
    public void configureResources(Map<String, ResourcePlugin> resources) {
        resources.putIfAbsent(
            "fake-ai-model-text-classification",
            ResourceBuilder.build("fake-ai-model-text-classification", FakeAiModelResource.class, FakeAiModelResourceConfiguration.class)
        );
    }

    @Test
    @DeployApi({ "/apis/log_request_policy.json" })
    void should_flag_request_if_prompt_violation_detected(HttpClient client, VertxTestContext context) {
        wiremock.stubFor(get("/endpoint").willReturn(aResponse().withStatus(200)));

        metricsSubject
            .doOnNext(metrics ->
                AssertionsForClassTypes
                    .assertThat(metrics)
                    .extracting(Metrics::getAdditionalMetrics)
                    .asInstanceOf(InstanceOfAssertFactories.SET)
                    .containsExactlyInAnyOrder(
                        new AdditionalMetric.KeywordMetric("keyword_action", "request-logged"),
                        new AdditionalMetric.KeywordMetric("keyword_content_violations", "toxic")
                    )
            )
            .doOnNext(m -> context.completeNow())
            .doOnError(context::failNow)
            .subscribe();

        client
            .rxRequest(HttpMethod.GET, "/log-request")
            .flatMap(request ->
                request.rxSend(
                    """
            {
              "model": "GPT-2000",
              "date": "01-01-2025",
              "prompt": "Nobody asked for your toxic response."
            }"""
                )
            )
            .doOnSuccess(response -> AssertionsForClassTypes.assertThat(response.statusCode()).isEqualTo(200))
            .subscribe();
    }

    @Test
    @DeployApi({ "/apis/log_request_policy.json" })
    void should_ignore_flagging_request_if_prompt_violation_not_detected(HttpClient client, VertxTestContext context) {
        wiremock.stubFor(get("/endpoint").willReturn(aResponse().withStatus(200)));

        metricsSubject
            .doOnNext(metrics ->
                AssertionsForClassTypes
                    .assertThat(metrics)
                    .extracting(Metrics::getAdditionalMetrics)
                    .asInstanceOf(InstanceOfAssertFactories.SET)
                    .isEmpty()
            )
            .doOnNext(m -> context.completeNow())
            .doOnError(context::failNow)
            .subscribe();

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
            .doOnSuccess(response -> AssertionsForClassTypes.assertThat(response.statusCode()).isEqualTo(200))
            .subscribe();
    }

    @Test
    @DeployApi({ "/apis/block_request_policy.json" })
    void should_block_request_if_prompt_violation_detected(HttpClient client) {
        wiremock.stubFor(get("/endpoint").willReturn(aResponse().withStatus(200)));

        client
            .rxRequest(HttpMethod.GET, "/block-request")
            .flatMap(request ->
                request.rxSend(
                    """
                        {
                          "model": "GPT-2000",
                          "date": "01-01-2025",
                          "prompt": "Nobody asked for your toxic response."
                        }"""
                )
            )
            .flatMapPublisher(response -> {
                assertThat(response.statusCode()).isEqualTo(400);
                return response.toFlowable();
            })
            .test()
            .awaitDone(15, SECONDS)
            .assertComplete()
            .assertValue(responseBody -> {
                assertThat(responseBody).hasToString("AI prompt validation detected. Reason: [toxic]");
                return true;
            })
            .assertNoErrors();
    }

    @Test
    @DeployApi({ "/apis/custom_sensitivity_threshold.json" })
    void should_change_sensitivity_if_custom_sensitivity_threshold_provided(HttpClient client, VertxTestContext context) {
        wiremock.stubFor(get("/endpoint").willReturn(aResponse().withStatus(200)));

        metricsSubject
            .doOnNext(metrics ->
                AssertionsForClassTypes
                    .assertThat(metrics)
                    .extracting(Metrics::getAdditionalMetrics)
                    .asInstanceOf(InstanceOfAssertFactories.SET)
                    .containsExactlyInAnyOrder(
                        new AdditionalMetric.KeywordMetric("keyword_action", "request-blocked"),
                        new AdditionalMetric.KeywordMetric("keyword_content_violations", "toxic,obscene")
                    )
            )
            .doOnNext(m -> context.completeNow())
            .doOnError(context::failNow)
            .subscribe();

        client
            .rxRequest(HttpMethod.GET, "/block-request")
            .flatMap(request ->
                request.rxSend(
                    """
                        {
                          "model": "GPT-2000",
                          "date": "01-01-2025",
                          "prompt": "Nobody asked for your toxic and obscene response."
                        }"""
                )
            )
            .flatMapPublisher(response -> {
                assertThat(response.statusCode()).isEqualTo(400);
                return response.toFlowable();
            })
            .test()
            .awaitDone(15, SECONDS)
            .assertComplete()
            .assertValue(responseBody -> {
                assertThat(responseBody).hasToString("AI prompt validation detected. Reason: [toxic, obscene]");
                return true;
            })
            .assertNoErrors();
    }

    @Test
    @DeployApi({ "/apis/missing_resource.json" })
    void should_return_error_if_resource_is_not_configured_properly(HttpClient client) {
        wiremock.stubFor(get("/endpoint").willReturn(aResponse().withStatus(200)));

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
            .test()
            .awaitDone(15, SECONDS)
            .assertComplete()
            .assertValue(responseBody -> {
                assertThat(responseBody).hasToString("AI Model Text Classification resource incorrectly configured");
                return true;
            })
            .assertNoErrors();
    }

    @Test
    @DeployApi({ "/apis/broken_content_check_list.json" })
    void should_handle_parsing_incorrectly_formatted_list(HttpClient client) {
        wiremock.stubFor(get("/endpoint").willReturn(aResponse().withStatus(200)));

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
            .test()
            .awaitDone(15, SECONDS)
            .assertComplete()
            .assertValue(response -> {
                assertThat(response.statusCode()).isEqualTo(200);
                return true;
            })
            .assertNoErrors();
    }
}

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
import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.apim.gateway.tests.sdk.AbstractPolicyTest;
import io.gravitee.apim.gateway.tests.sdk.annotations.DeployApi;
import io.gravitee.apim.gateway.tests.sdk.annotations.GatewayTest;
import io.gravitee.apim.gateway.tests.sdk.resource.ResourceBuilder;
import io.gravitee.definition.model.ExecutionMode;
import io.gravitee.plugin.resource.ResourcePlugin;
import io.gravitee.policy.ai.prompt.guard.rails.configuration.AiPromptGuardRailsConfiguration;
import io.gravitee.reporter.api.v4.metric.AdditionalMetric;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.resource.ai_model.TextClassificationAiModelResource;
import io.gravitee.resource.ai_model.configuration.TextClassificationAiModelConfiguration;
import io.vertx.core.http.HttpMethod;
import io.vertx.junit5.VertxTestContext;
import io.vertx.rxjava3.core.http.HttpClient;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.*;

@Slf4j
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@GatewayTest(v2ExecutionMode = ExecutionMode.V4_EMULATION_ENGINE)
class AiPromptGuardRailsPolicyIntegrationTest extends AbstractPolicyTest<AiPromptGuardRailsPolicy, AiPromptGuardRailsConfiguration> {

    @Nested
    class WithFakeAiResource extends AbstractAiPromptGuardRailsPolicyIntegrationTest {

        @Override
        public void configureResources(Map<String, ResourcePlugin> resources) {
            super.configureResources(resources);

            resources.putIfAbsent(
                "ai-model-text-classification",
                ResourceBuilder.build("ai-model-text-classification", FakeAiModelResource.class, FakeAiModelResourceConfiguration.class)
            );
        }

        @Test
        @DeployApi({ "/apis/log_request_policy.json" })
        void should_flag_request_if_prompt_violation_detected(HttpClient client, VertxTestContext context) {
            wiremock.stubFor(get("/endpoint").willReturn(aResponse().withStatus(200)));

            // When
            var clientAsserts = client
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
                .flatMap(this::toResult);

            // Then
            then(clientAsserts).subscribe(
                result ->
                    context
                        .verify(() -> {
                            assertThat(result.statusCode()).isEqualTo(200);
                            assertThat(result.metrics())
                                .extracting(Metrics::getAdditionalMetrics)
                                .asInstanceOf(InstanceOfAssertFactories.SET)
                                .areExactly(1, keyword("keyword_action", "request-logged"))
                                .areExactly(1, keyword("keyword_content_violations", "toxic"));
                        })
                        .completeNow(),
                context::failNow
            );
        }

        @Test
        @DeployApi({ "/apis/log_request_policy.json" })
        void should_ignore_flagging_request_if_prompt_violation_not_detected(HttpClient client, VertxTestContext context) {
            wiremock.stubFor(get("/endpoint").willReturn(aResponse().withStatus(200)));

            // When
            var clientAsserts = client
                .rxRequest(HttpMethod.GET, "/log-request")
                .flatMap(request ->
                    request.rxSend(
                        """
                        {
                          "model": "GPT-2000",
                          "date": "01-01-2025",
                          "prompt": "This is super friendly message"
                        }
                        """
                    )
                )
                .flatMap(this::toResult);

            then(clientAsserts).subscribe(
                result ->
                    context
                        .verify(() -> {
                            assertThat(result.statusCode()).isEqualTo(200);
                            assertThat(result.metrics())
                                .extracting(Metrics::getAdditionalMetrics)
                                .asInstanceOf(InstanceOfAssertFactories.SET)
                                .isEmpty();
                        })
                        .completeNow(),
                context::failNow
            );
        }

        @Test
        @DeployApi({ "/apis/block_request_policy.json" })
        void should_block_request_if_prompt_violation_detected(HttpClient client, VertxTestContext context) {
            wiremock.stubFor(get("/endpoint").willReturn(aResponse().withStatus(200)));

            // When
            var clientAsserts = client
                .rxRequest(HttpMethod.GET, "/block-request")
                .flatMap(request ->
                    request.rxSend(
                        """
                        {
                          "model": "GPT-2000",
                          "date": "01-01-2025",
                          "prompt": "Nobody asked for your bullsh*t response."
                        }
                        """
                    )
                )
                .flatMap(this::toResult);

            then(clientAsserts).subscribe(
                result ->
                    context
                        .verify(() -> {
                            assertThat(result.statusCode()).isEqualTo(400);
                            assertThat(result.responseBody()).hasToString("AI prompt validation detected. Reason: [toxic]");
                            assertThat(result.metrics())
                                .extracting(Metrics::getAdditionalMetrics)
                                .asInstanceOf(InstanceOfAssertFactories.SET)
                                .areExactly(1, keyword("keyword_action", "request-blocked"))
                                .areExactly(1, keyword("keyword_content_violations", "toxic"));
                        })
                        .completeNow(),
                context::failNow
            );
        }

        @Test
        @DeployApi({ "/apis/custom_sensitivity_threshold.json" })
        void should_change_sensitivity_if_custom_sensitivity_threshold_provided(HttpClient client, VertxTestContext context) {
            wiremock.stubFor(get("/endpoint").willReturn(aResponse().withStatus(200)));

            // When
            var clientAsserts = client
                .rxRequest(HttpMethod.GET, "/block-request")
                .flatMap(request ->
                    request.rxSend(
                        """
                        {
                          "model": "GPT-2000",
                          "date": "01-01-2025",
                          "prompt": "Nobody asked for your bullsh*t response."
                        }
                        """
                    )
                )
                .flatMap(this::toResult);

            // Then
            then(clientAsserts).subscribe(
                result ->
                    context
                        .verify(() -> {
                            assertThat(result.statusCode()).isEqualTo(400);
                            assertThat(result.responseBody().toString()).contains(
                                "AI prompt validation detected. Reason:",
                                "toxic",
                                "obscene"
                            );
                            assertThat(result.metrics())
                                .extracting(Metrics::getAdditionalMetrics)
                                .asInstanceOf(InstanceOfAssertFactories.SET)
                                .areExactly(1, keyword("keyword_action", "request-blocked"))
                                .areExactly(1, keyword("keyword_content_violations", "toxic,obscene"));
                        })
                        .completeNow(),
                context::failNow
            );
        }

        @Test
        @DeployApi({ "/apis/missing_resource.json" })
        void should_return_error_if_resource_is_not_configured_properly(HttpClient client, VertxTestContext context) {
            wiremock.stubFor(get("/endpoint").willReturn(aResponse().withStatus(200)));

            var clientAsserts = client
                .rxRequest(HttpMethod.GET, "/missing-resource")
                .flatMap(request ->
                    request.rxSend(
                        """
                        {
                          "model": "GPT-2000",
                          "date": "01-01-2025",
                          "prompt": "Nobody asked for your bullsh*t response."
                        }
                        """
                    )
                )
                .flatMap(this::toResult);

            then(clientAsserts).subscribe(
                result ->
                    context
                        .verify(() -> {
                            assertThat(result.statusCode()).isEqualTo(500);
                            assertThat(result.responseBody()).hasToString("AI Model Text Classification resource incorrectly configured");
                        })
                        .completeNow(),
                context::failNow
            );
        }

        @Test
        @DeployApi({ "/apis/broken_content_check_list.json" })
        void should_handle_parsing_incorrectly_formatted_list(HttpClient client, VertxTestContext context) {
            wiremock.stubFor(get("/endpoint").willReturn(aResponse().withStatus(200)));

            var clientAsserts = client
                .rxRequest(HttpMethod.GET, "/broken-content-check")
                .flatMap(request ->
                    request.rxSend(
                        """
                        {
                          "model": "GPT-2000",
                          "date": "01-01-2025",
                          "prompt": "This is some friendly prompt"
                        }
                        """
                    )
                )
                .flatMap(this::toResult);

            then(clientAsserts).subscribe(
                result -> context.verify(() -> assertThat(result.statusCode()).isEqualTo(200)).completeNow(),
                context::failNow
            );
        }

        @Test
        @DeployApi({ "/apis/log_request_policy.json" })
        void should_return_an_error_when_inference_fail(HttpClient client, VertxTestContext context) {
            wiremock.stubFor(get("/endpoint").willReturn(aResponse().withStatus(200)));

            var clientAsserts = client
                .rxRequest(HttpMethod.GET, "/log-request")
                .flatMap(request ->
                    request.rxSend(
                        """
                        {
                          "model": "GPT-2000",
                          "date": "01-01-2025",
                          "prompt": "not ready. Nobody asked for your bullsh*t response."
                        }
                        """
                    )
                )
                .flatMap(this::toResult);

            then(clientAsserts).subscribe(
                result ->
                    context
                        .verify(() -> {
                            assertThat(result.statusCode()).isEqualTo(503);
                            assertThat(result.responseBody()).hasToString("Model is not ready");
                        })
                        .completeNow(),
                context::failNow
            );
        }

        @Test
        @DeployApi({ "/apis/block_request_policy_empty_contentChecks.json" })
        void should_block_request_if_prompt_violation_detected_and_empty_contentChecks(HttpClient client, VertxTestContext context) {
            wiremock.stubFor(get("/endpoint").willReturn(aResponse().withStatus(200)));

            // When
            var clientAsserts = client
                .rxRequest(HttpMethod.GET, "/block-request-empty-contentChecks")
                .flatMap(request ->
                    request.rxSend(
                        """
                        {
                          "model": "GPT-2000",
                          "date": "01-01-2025",
                          "prompt": "Nobody asked for your bullsh*t response."
                        }
                        """
                    )
                )
                .flatMap(this::toResult);

            // Then
            then(clientAsserts).subscribe(
                result ->
                    context
                        .verify(() -> {
                            assertThat(result.metrics())
                                .extracting(Metrics::getAdditionalMetrics)
                                .asInstanceOf(InstanceOfAssertFactories.SET)
                                .areExactly(1, keyword("keyword_action", "request-blocked"))
                                .areExactly(1, keyword("keyword_content_violations", "toxic"));
                            assertThat(result.statusCode()).isEqualTo(400);
                            assertThat(result.responseBody()).hasToString("AI prompt validation detected. Reason: [toxic]");
                        })
                        .completeNow(),
                context::failNow
            );
        }
    }

    @Nested
    @DeployApi({ "/apis/block_request_policy.json", "/apis/log_request_policy.json" })
    class WithRealAiResource extends AbstractAiPromptGuardRailsPolicyIntegrationTest {

        @Override
        public void configureResources(Map<String, ResourcePlugin> resources) {
            super.configureResources(resources);

            resources.putIfAbsent(
                "ai-model-text-classification",
                ResourceBuilder.build(
                    "ai-model-text-classification",
                    TextClassificationAiModelResource.class,
                    TextClassificationAiModelConfiguration.class
                )
            );
        }

        @Test
        void should_flag_request_if_prompt_violation_detected(HttpClient client, VertxTestContext context) {
            wiremock.stubFor(get("/endpoint").willReturn(aResponse().withStatus(200)));

            // When
            var clientAsserts = client
                .rxRequest(HttpMethod.GET, "/log-request")
                .flatMap(request ->
                    request.rxSend(
                        """
                        {
                          "model": "GPT-2000",
                          "date": "01-01-2025",
                          "prompt": "Nobody asked for your bullsh*t response."
                        }
                        """
                    )
                )
                .flatMap(this::toResult);

            // Then
            then(clientAsserts).subscribe(
                response ->
                    context
                        .verify(() -> {
                            assertThat(response.statusCode()).isEqualTo(200);
                            assertThat(response.metrics())
                                .extracting(Metrics::getAdditionalMetrics)
                                .asInstanceOf(InstanceOfAssertFactories.SET)
                                .areExactly(1, keyword("keyword_action", "request-logged"))
                                .areExactly(1, keyword("keyword_content_violations", "toxic"));
                        })
                        .completeNow(),
                context::failNow
            );
        }

        @Test
        void should_block_request_if_prompt_violation_detected(HttpClient client, VertxTestContext context) {
            wiremock.stubFor(get("/endpoint").willReturn(aResponse().withStatus(200)));

            // When
            var clientAsserts = client
                .rxRequest(HttpMethod.GET, "/block-request")
                .flatMap(request ->
                    request.rxSend(
                        """
                        {
                          "model": "GPT-2000",
                          "date": "01-01-2025",
                          "prompt": "Nobody asked for your bullsh*t response."
                        }
                        """
                    )
                )
                .flatMap(this::toResult);

            // Then
            then(clientAsserts).subscribe(
                tuple ->
                    context
                        .verify(() -> {
                            assertThat(tuple.statusCode()).isEqualTo(400);
                            assertThat(tuple.responseBody()).hasToString("AI prompt validation detected. Reason: [toxic]");
                            assertThat(tuple.metrics())
                                .extracting(Metrics::getAdditionalMetrics)
                                .asInstanceOf(InstanceOfAssertFactories.SET)
                                .areExactly(1, keyword("keyword_action", "request-blocked"))
                                .areExactly(1, keyword("keyword_content_violations", "toxic"));
                        })
                        .completeNow(),
                context::failNow
            );
        }
    }
}

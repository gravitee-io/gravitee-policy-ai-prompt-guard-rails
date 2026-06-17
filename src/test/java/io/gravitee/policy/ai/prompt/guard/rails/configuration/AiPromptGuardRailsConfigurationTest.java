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
package io.gravitee.policy.ai.prompt.guard.rails.configuration;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.gravitee.definition.jackson.datatype.GraviteeMapper;
import io.gravitee.gateway.reactive.api.context.llm.LlmRequestInspector;
import java.util.Objects;
import lombok.SneakyThrows;
import org.assertj.core.api.Condition;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@ExtendWith(SoftAssertionsExtension.class)
class AiPromptGuardRailsConfigurationTest {

    private static final GraviteeMapper MAPPER = new GraviteeMapper();

    @Test
    void should_deserialize_a_full_configuration_without_promptPreset(SoftAssertions softly) {
        var configuration = deserialize(
            """
            {
              "resourceName": "my-classifier",
              "promptLocation": "$.messages",
              "contentChecks": "TOXIC,OBSCENE",
              "sensitivityThreshold": 0.8,
              "requestPolicy": "BLOCK_REQUEST"
            }
            """
        );

        softly
            .assertThat(configuration)
            .has(resourceName("my-classifier"))
            .has(promptQuery(new LlmRequestInspector.PromptQuery.CustomPrompt("$.messages")))
            .has(contentChecks("TOXIC,OBSCENE"))
            .has(sensitivityThreshold(0.8))
            .has(requestPolicy(RequestPolicy.BLOCK_REQUEST));
    }

    @Test
    void should_deserialize_a_full_configuration_all_prompts(SoftAssertions softly) {
        var configuration = deserialize(
            """
            {
              "resourceName": "my-classifier",
              "promptPreset": "ALL_PROMPTS",
              "promptLocation": "$.messages",
              "contentChecks": "TOXIC,OBSCENE",
              "sensitivityThreshold": 0.8,
              "requestPolicy": "BLOCK_REQUEST"
            }
            """
        );

        softly
            .assertThat(configuration)
            .has(resourceName("my-classifier"))
            .has(promptQuery(new LlmRequestInspector.PromptQuery.AllPrompts()))
            .has(contentChecks("TOXIC,OBSCENE"))
            .has(sensitivityThreshold(0.8))
            .has(requestPolicy(RequestPolicy.BLOCK_REQUEST));
    }

    @Test
    void should_deserialize_a_full_configuration(SoftAssertions softly) {
        var configuration = deserialize(
            """
            {
              "resourceName": "my-classifier",
              "promptPreset": "CUSTOM_PROMPT",
              "promptLocation": "$.messages",
              "contentChecks": "TOXIC,OBSCENE",
              "sensitivityThreshold": 0.8,
              "requestPolicy": "BLOCK_REQUEST"
            }
            """
        );

        softly
            .assertThat(configuration)
            .has(resourceName("my-classifier"))
            .has(promptQuery(new LlmRequestInspector.PromptQuery.CustomPrompt("$.messages")))
            .has(contentChecks("TOXIC,OBSCENE"))
            .has(sensitivityThreshold(0.8))
            .has(requestPolicy(RequestPolicy.BLOCK_REQUEST));
    }

    @Test
    void should_deserialize_a_minimal_configuration_leaving_other_fields_null(SoftAssertions softly) {
        var configuration = deserialize(
            """
            {
              "requestPolicy": "LOG_REQUEST"
            }
            """
        );

        softly
            .assertThat(configuration)
            .has(resourceName(null))
            .has(promptQuery(new LlmRequestInspector.PromptQuery.AllPrompts()))
            .has(contentChecks(null))
            .has(sensitivityThreshold(null))
            .has(requestPolicy(RequestPolicy.LOG_REQUEST));
    }

    @ParameterizedTest
    @EnumSource(RequestPolicy.class)
    void should_deserialize_every_request_policy(RequestPolicy value, SoftAssertions softly) {
        var configuration = deserialize(
            """
            {
              "requestPolicy": "%s"
            }
            """.formatted(value.name())
        );

        softly.assertThat(configuration).has(requestPolicy(value));
    }

    @SneakyThrows(JsonProcessingException.class)
    private static AiPromptGuardRailsConfiguration deserialize(String json) {
        return MAPPER.readValue(json, AiPromptGuardRailsConfiguration.class);
    }

    private static Condition<AiPromptGuardRailsConfiguration> resourceName(String expected) {
        return new Condition<>(c -> Objects.equals(c.resourceName(), expected), "resourceName = %s", expected);
    }

    private static Condition<AiPromptGuardRailsConfiguration> promptQuery(LlmRequestInspector.PromptQuery expected) {
        return new Condition<>(c -> Objects.equals(c.getPromptQuery(), expected), "promptQuery = %s", expected);
    }

    private static Condition<AiPromptGuardRailsConfiguration> contentChecks(String expected) {
        return new Condition<>(c -> Objects.equals(c.contentChecks(), expected), "contentChecks = %s", expected);
    }

    private static Condition<AiPromptGuardRailsConfiguration> sensitivityThreshold(Double expected) {
        return new Condition<>(c -> Objects.equals(c.sensitivityThreshold(), expected), "sensitivityThreshold = %s", expected);
    }

    private static Condition<AiPromptGuardRailsConfiguration> requestPolicy(RequestPolicy expected) {
        return new Condition<>(c -> Objects.equals(c.requestPolicy(), expected), "requestPolicy = %s", expected);
    }
}

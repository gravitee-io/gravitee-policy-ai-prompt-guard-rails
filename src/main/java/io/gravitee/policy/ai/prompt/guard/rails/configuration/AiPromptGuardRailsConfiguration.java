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

import io.gravitee.gateway.reactive.api.context.llm.LlmRequestInspector;
import io.gravitee.policy.api.PolicyConfiguration;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

@Slf4j
public record AiPromptGuardRailsConfiguration(
    String resourceName,
    PromptPreset promptPreset,
    String promptLocation,
    String contentChecks,
    Double sensitivityThreshold,
    RequestPolicy requestPolicy
) implements PolicyConfiguration {
    private static final double DEFAULT_SENSITIVITY_THRESHOLD = 0.5;

    public List<String> parseContentChecks() {
        if (contentChecks == null || contentChecks.trim().isEmpty()) {
            log.warn("Configured content checks list is empty");
            return List.of();
        }
        return Arrays.stream(contentChecks.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    public double getSensitivityThreshold() {
        return sensitivityThreshold != null ? sensitivityThreshold : DEFAULT_SENSITIVITY_THRESHOLD;
    }

    public LlmRequestInspector.PromptQuery getPromptQuery() {
        // to keep the previous behavior
        if (StringUtils.hasText(promptLocation)) {
            return new LlmRequestInspector.PromptQuery.CustomPrompt(promptLocation);
        }
        return switch (promptPreset) {
            case LAST_USER_PROMPT -> new LlmRequestInspector.PromptQuery.LastUserPrompt();
            case ALL_USER_PROMPTS -> new LlmRequestInspector.PromptQuery.AllUserPrompts();
            case ALL_PROMPTS -> new LlmRequestInspector.PromptQuery.AllPrompts();
            case null, default -> new LlmRequestInspector.PromptQuery.CustomPrompt(promptLocation);
        };
    }

    public enum PromptPreset {
        LAST_USER_PROMPT,
        ALL_USER_PROMPTS,
        ALL_PROMPTS,
        CUSTOM_PROMPT,
    }
}

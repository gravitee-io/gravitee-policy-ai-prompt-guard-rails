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

import io.gravitee.gateway.reactive.api.context.llm.LlmPartCriteria;
import io.gravitee.policy.api.PolicyConfiguration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import lombok.CustomLog;
import org.springframework.util.StringUtils;

@CustomLog
public record AiPromptGuardRailsConfiguration(
    String resourceName,
    PromptPreset promptPreset,
    String promptLocation,
    String contentChecks,
    Double sensitivityThreshold,
    RequestPolicy requestPolicy
) implements PolicyConfiguration {
    private static final double DEFAULT_SENSITIVITY_THRESHOLD = 0.5;

    /**
     * What is inspected on an llm api: every message of the conversation, tool definitions excluded.
     * <p>
     * Held as a constant rather than rebuilt per request, as {@link LlmPartCriteria} asks: a criterion that
     * cannot select anything is rejected at construction, which is only useful if it fires at deployment time.
     */
    private static final List<LlmPartCriteria> ALL_CONVERSATION = List.of(
        new LlmPartCriteria(Set.of(LlmPartCriteria.Kind.PROMPT), null, null)
    );

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

    /**
     * Whether the prompt is read from the location expression rather than from the conversation the gateway
     * normalized. That is the only way in on a plain http api, which carries no conversation at all.
     */
    public boolean isCustomPrompt() {
        if (promptPreset == null) {
            return StringUtils.hasText(promptLocation);
        }
        return promptPreset == PromptPreset.CUSTOM_PROMPT;
    }

    /**
     * The parts of an llm request this policy inspects. Every preset but {@link PromptPreset#CUSTOM_PROMPT}
     * inspects the whole conversation: {@link PromptPreset#LAST_USER_PROMPT} and
     * {@link PromptPreset#ALL_USER_PROMPTS} are legacy values the configuration schema no longer offers.
     */
    public List<LlmPartCriteria> promptCriteria() {
        return ALL_CONVERSATION;
    }

    public enum PromptPreset {
        LAST_USER_PROMPT,
        ALL_USER_PROMPTS,
        ALL_PROMPTS,
        CUSTOM_PROMPT,
    }
}

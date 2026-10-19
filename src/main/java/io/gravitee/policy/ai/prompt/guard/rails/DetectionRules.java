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

import io.gravitee.policy.ai.prompt.guard.rails.configuration.AiPromptGuardRailsConfiguration;
import io.gravitee.policy.ai.prompt.guard.rails.configuration.RequestPolicy;
import io.gravitee.resource.ai_model.api.result.ClassifierResults;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The rules the classifications of a request are read against.
 *
 * @param sensitivityThreshold the score a label must exceed to be detected.
 * @param contentChecks the labels that can be detected, every label of the model when empty.
 * @param requestPolicy what is done with the request once a label is detected.
 */
record DetectionRules(double sensitivityThreshold, Set<String> contentChecks, RequestPolicy requestPolicy) {
    DetectionRules {
        contentChecks = Set.copyOf(contentChecks);
        Objects.requireNonNull(requestPolicy, "requestPolicy is required");
    }

    static DetectionRules of(AiPromptGuardRailsConfiguration configuration) {
        return new DetectionRules(
            configuration.getSensitivityThreshold(),
            Set.copyOf(configuration.parseContentChecks()),
            configuration.requestPolicy()
        );
    }

    /**
     * The verdict of a request no inference has been merged into yet.
     */
    Verdict noDetection() {
        return new Verdict(this, Set.of());
    }

    /**
     * The labels of an inference scored above the sensitivity threshold, among the content checks if any is
     * configured.
     */
    Set<String> detectedIn(ClassifierResults classifierResults) {
        return classifierResults
            .results()
            .stream()
            .filter(result -> result.score() > sensitivityThreshold)
            .map(ClassifierResults.ClassifierResult::label)
            .filter(label -> contentChecks.isEmpty() || contentChecks.contains(label))
            .collect(Collectors.toSet());
    }
}

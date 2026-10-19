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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The rules the classifications of a request are read against, built once from the configuration.
 * <p>
 * A label is detected in a request as soon as one of its inferences scores it above the sensitivity threshold,
 * whatever the other inferences score: the labels detected in a request are the union of the labels detected in
 * each of its inferences, so no assumption is made on the labels an inference reports.
 *
 * @param sensitivityThreshold the score a label must exceed to be detected.
 * @param contentChecks the labels that can be detected, every label of the model when empty.
 * @param requestPolicy what is done with the request once a label is detected.
 */
record DetectionRules(double sensitivityThreshold, Set<String> contentChecks, RequestPolicy requestPolicy) {
    DetectionRules {
        contentChecks = Set.copyOf(contentChecks);
        Objects.requireNonNull(requestPolicy, "A request policy is required");
    }

    static DetectionRules of(AiPromptGuardRailsConfiguration configuration) {
        return new DetectionRules(
            configuration.getSensitivityThreshold(),
            Set.copyOf(configuration.parseContentChecks()),
            configuration.requestPolicy()
        );
    }

    /**
     * The labels an inference scores above the sensitivity threshold, among the content checks if any is configured,
     * in the order the inference reports them.
     */
    Set<String> detectedIn(ClassifierResults classifierResults) {
        return classifierResults
            .results()
            .stream()
            .filter(result -> result.score() > sensitivityThreshold)
            .map(ClassifierResults.ClassifierResult::label)
            .filter(label -> contentChecks.isEmpty() || contentChecks.contains(label))
            .collect(Collectors.collectingAndThen(Collectors.toCollection(LinkedHashSet::new), Collections::unmodifiableSequencedSet));
    }

    /**
     * Whether no further inference can change the decision on a request in which {@code detected} are detected so
     * far: a blocked request is decided at the first label detected; a logged one once every content check is
     * detected. Without any content check, the labels of the model are unknown, so a logged request is never decided
     * before its last inference.
     */
    boolean isConclusive(Set<String> detected) {
        return switch (requestPolicy) {
            case BLOCK_REQUEST -> !detected.isEmpty();
            case LOG_REQUEST -> !contentChecks.isEmpty() && detected.containsAll(contentChecks);
        };
    }

    /**
     * What is done with a request in which {@code detected} are detected.
     */
    Decision decide(Set<String> detected) {
        if (detected.isEmpty()) {
            return new Decision.Pass();
        }
        return switch (requestPolicy) {
            case BLOCK_REQUEST -> new Decision.Block(detected);
            case LOG_REQUEST -> new Decision.Log(detected);
        };
    }
}

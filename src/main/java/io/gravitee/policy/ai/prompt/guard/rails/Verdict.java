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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What the classifications of a request add up to: the highest score of each label over the inferences merged so
 * far, read against the rules of the policy.
 * <p>
 * No assumption is made on the labels an inference reports: a classifier may report a score for some of its labels
 * only, so the labels it has not reported yet are never taken as absent.
 *
 * @param sensitivityThreshold the score a label must exceed to be detected.
 * @param contentChecks the labels that can be detected, every label of the model when empty.
 * @param requestPolicy what is done with the request once a label is detected.
 * @param highestScoreByLabel the highest score of each label over the inferences merged so far.
 */
record Verdict(
    double sensitivityThreshold,
    List<String> contentChecks,
    RequestPolicy requestPolicy,
    Map<String, Float> highestScoreByLabel
) {
    Verdict(double sensitivityThreshold, List<String> contentChecks, RequestPolicy requestPolicy) {
        this(sensitivityThreshold, contentChecks, requestPolicy, Map.of());
    }

    /**
     * The verdict of a request no inference has been merged into yet.
     */
    static Verdict of(AiPromptGuardRailsConfiguration configuration) {
        return new Verdict(configuration.getSensitivityThreshold(), configuration.parseContentChecks(), configuration.requestPolicy());
    }

    Verdict merge(ClassifierResults classifierResults) {
        var highest = new HashMap<>(highestScoreByLabel);
        classifierResults.results().forEach(result -> highest.merge(result.label(), result.score(), Math::max));
        return new Verdict(sensitivityThreshold, contentChecks, requestPolicy, highest);
    }

    /**
     * The labels scored above the sensitivity threshold, among the content checks if any is configured.
     */
    Set<String> detected() {
        return highestScoreByLabel
            .entrySet()
            .stream()
            .filter(score -> score.getValue() > sensitivityThreshold)
            .map(Map.Entry::getKey)
            .filter(label -> contentChecks.isEmpty() || contentChecks.contains(label))
            .collect(Collectors.toSet());
    }

    /**
     * Whether no further inference can change what is done with the request: a blocked request is decided at the
     * first label detected; a logged one once every content check is detected. Without any content check, the
     * labels of the model are unknown, so a logged request is never decided before its last inference.
     */
    boolean isConclusive() {
        return switch (requestPolicy) {
            case BLOCK_REQUEST -> !detected().isEmpty();
            case LOG_REQUEST -> !contentChecks.isEmpty() && detected().containsAll(contentChecks);
        };
    }
}

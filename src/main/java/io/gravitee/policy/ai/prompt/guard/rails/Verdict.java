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

import io.gravitee.resource.ai_model.api.result.ClassifierResults;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * What the classifications of a request add up to: the labels detected by any of the inferences merged so far.
 * A label detected in one window stays detected whatever the other windows score.
 *
 * @param rules the rules the classifications are read against.
 * @param detected the labels detected by the inferences merged so far.
 */
record Verdict(DetectionRules rules, Set<String> detected) {
    Verdict {
        Objects.requireNonNull(rules, "rules are required");
        detected = Collections.unmodifiableSet(new HashSet<>(detected));
    }

    Verdict merge(ClassifierResults classifierResults) {
        var newlyDetected = rules.detectedIn(classifierResults);
        if (detected.containsAll(newlyDetected)) {
            return this;
        }
        var union = new HashSet<>(detected);
        union.addAll(newlyDetected);
        return new Verdict(rules, union);
    }

    /**
     * Whether no further inference can change what is done with the request: a blocked request is decided at the
     * first label detected; a logged one once every content check is detected. Without any content check, the
     * labels of the model are unknown, and a classifier is not bound to report all of them, so a logged request is
     * never decided before its last inference.
     */
    boolean isConclusive() {
        return switch (rules.requestPolicy()) {
            case BLOCK_REQUEST -> !detected.isEmpty();
            case LOG_REQUEST -> !rules.contentChecks().isEmpty() && detected.containsAll(rules.contentChecks());
        };
    }
}

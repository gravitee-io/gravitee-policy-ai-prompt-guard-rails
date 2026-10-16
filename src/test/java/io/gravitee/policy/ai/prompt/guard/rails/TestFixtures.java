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

import java.util.stream.Collectors;
import java.util.stream.IntStream;

final class TestFixtures {

    private TestFixtures() {}

    /**
     * A text of {@code length} code points, all different, so that a window cut from it tells where it starts.
     */
    static String distinctCodePoints(int length) {
        return IntStream.range(0, length)
            .map(index -> 0x4E00 + index)
            .mapToObj(Character::toString)
            .collect(Collectors.joining());
    }

    /**
     * The code points of {@code text} from {@code start} included to {@code end} excluded.
     */
    static String codePoints(String text, int start, int end) {
        return text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end));
    }
}

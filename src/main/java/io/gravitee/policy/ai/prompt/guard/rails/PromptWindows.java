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

import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Cuts a text into the windows a text classification model is given one at a time.
 * <p>
 * A classifier only reads the first tokens of its input (512 for the usual models) and silently drops the rest,
 * so a long text classified as a whole is only partially inspected. Each window is kept short enough for the
 * classifier to read it entirely, and overlaps the previous one so that a word straddling two windows is whole
 * in one of them.
 * <p>
 * Windows are counted in code points rather than in chars, so that a surrogate pair is never split in two.
 * <p>
 * <b>Precondition</b>: a window of {@value #WINDOW_SIZE} code points is assumed to fit the 512 tokens the model
 * reads. That holds for a WordPiece-like tokenizer, which never makes more tokens than code points, but not
 * necessarily for a byte-level BPE tokenizer, which can make several tokens of a single emoji or CJK character:
 * the end of such a window would then be silently dropped by the model.
 */
final class PromptWindows {

    static final int WINDOW_SIZE = 500;
    static final int WINDOW_OVERLAP = 50;

    private PromptWindows() {}

    /**
     * The windows of a text, from its start to its end. The text of a window is only built when it is consumed.
     * <p>
     * A text of at most {@value #WINDOW_SIZE} code points is a single window. A longer one is cut into windows of
     * exactly {@value #WINDOW_SIZE} code points, the first one at its start and the last one at its end, spread
     * evenly in between and overlapping by at least {@value #WINDOW_OVERLAP} code points.
     */
    static Stream<String> of(String text) {
        if (text.codePointCount(0, text.length()) <= WINDOW_SIZE) {
            return Stream.of(text);
        }

        var codePoints = text.codePoints().toArray();
        int length = codePoints.length;
        int count = Math.ceilDiv(length - WINDOW_OVERLAP, WINDOW_SIZE - WINDOW_OVERLAP);
        long lastStart = length - WINDOW_SIZE;
        return IntStream.range(0, count)
            .map(index -> Math.toIntExact(Math.ceilDiv(index * lastStart, count - 1)))
            .mapToObj(start -> new String(codePoints, start, WINDOW_SIZE));
    }
}

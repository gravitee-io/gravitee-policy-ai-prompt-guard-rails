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

import static io.gravitee.policy.ai.prompt.guard.rails.TestFixtures.codePoints;
import static io.gravitee.policy.ai.prompt.guard.rails.TestFixtures.distinctCodePoints;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.assertj.core.api.Condition;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PromptWindowsTest {

    private static final String TOXIC_WORD = "toxic";

    private static final Condition<String> ONE_WINDOW_LONG = new Condition<>(
        window -> window.codePointCount(0, window.length()) == 500,
        "500 code points long"
    );

    private static final Condition<String> WITHOUT_SPLIT_SURROGATE_PAIR = new Condition<>(
        window -> window.codePoints().noneMatch(codePoint -> codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE),
        "without any unpaired surrogate"
    );

    @Test
    void should_keep_a_short_text_in_a_single_window() {
        var text = "Tell me a joke";

        assertThat(PromptWindows.of(text)).as("windows of a short text").singleElement().isEqualTo(text);
    }

    @Test
    void should_keep_a_text_of_exactly_one_window_in_a_single_window() {
        var text = "a".repeat(500);

        assertThat(PromptWindows.of(text)).as("windows of a 500 code points text").singleElement().isEqualTo(text);
    }

    static Stream<Arguments> should_spread_full_windows_evenly_from_the_start_to_the_end_of_the_text() {
        return Stream.of(
            Arguments.of(950, List.of(new int[] { 0, 500 }, new int[] { 450, 950 })),
            Arguments.of(951, List.of(new int[] { 0, 500 }, new int[] { 225, 725 }, new int[] { 451, 951 })),
            Arguments.of(1000, List.of(new int[] { 0, 500 }, new int[] { 250, 750 }, new int[] { 500, 1000 }))
        );
    }

    @ParameterizedTest(name = "{0} code points")
    @MethodSource
    void should_spread_full_windows_evenly_from_the_start_to_the_end_of_the_text(int length, List<int[]> expectedBounds) {
        var text = distinctCodePoints(length);

        assertThat(PromptWindows.of(text))
            .as("windows of a %d code points text, from its start", length)
            .containsExactlyElementsOf(
                expectedBounds
                    .stream()
                    .map(bounds -> codePoints(text, bounds[0], bounds[1]))
                    .toList()
            );
    }

    @ParameterizedTest(name = "{0} code points")
    @ValueSource(ints = { 501, 949, 950, 951, 1000, 1351, 5813, 20_000 })
    void should_cut_full_windows_overlapping_by_at_least_50_code_points_from_the_start_to_the_end_of_the_text(int length) {
        var text = distinctCodePoints(length);

        var windows = PromptWindows.of(text).toList();
        var starts = windows.stream().mapToInt(text::indexOf).toArray();

        assertSoftly(softly -> {
            softly.assertThat(windows).as("windows of a %d code points text", length).are(ONE_WINDOW_LONG);
            softly.assertThat(starts).as("window starts").startsWith(0).endsWith(length - 500).isSorted();
            softly
                .assertThat(IntStream.range(1, starts.length).map(index -> starts[index - 1] + 500 - starts[index]))
                .as("overlaps between consecutive windows")
                .allMatch(overlap -> overlap >= 50);
        });
    }

    @ParameterizedTest(name = "toxic word starting at code point {0}")
    @ValueSource(ints = { 446, 448, 449, 450, 496, 497, 498, 899, 946, 948 })
    void should_catch_a_word_straddling_a_window_boundary_thanks_to_the_overlap(int wordStart) {
        var text = "a".repeat(wordStart) + TOXIC_WORD + "a".repeat(2000 - wordStart - TOXIC_WORD.length());

        assertThat(PromptWindows.of(text))
            .as("windows of a text holding '%s' at %d", TOXIC_WORD, wordStart)
            .haveAtLeastOne(new Condition<>(window -> window.contains(TOXIC_WORD), "containing the whole word '%s'", TOXIC_WORD));
    }

    @Test
    void should_slide_on_code_points_without_splitting_surrogate_pairs() {
        var text = "a😀".repeat(600);

        var windows = PromptWindows.of(text).toList();

        assertSoftly(softly -> {
            softly.assertThat(windows).as("windows of a text made of surrogate pairs").hasSize(3).are(ONE_WINDOW_LONG);
            softly.assertThat(windows).as("windows of a text made of surrogate pairs").are(WITHOUT_SPLIT_SURROGATE_PAIR);
            softly
                .assertThat(windows)
                .as("windows of a text made of surrogate pairs, from its start")
                .containsExactly(codePoints(text, 0, 500), codePoints(text, 350, 850), codePoints(text, 700, 1200));
        });
    }
}

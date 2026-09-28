package dev.nibin.buzzer.session.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoomCodeTest {

    @Test
    void theAlphabetHasNoLookAlikes() {
        assertThat(RoomCode.ALPHABET).hasSize(32).doesNotContain("0", "O", "1", "I");
    }

    @Test
    void randomCodesAreSixCharactersFromTheAlphabet() {
        Random random = new Random(42);
        for (int i = 0; i < 1_000; i++) {
            String code = RoomCode.random(random).value();
            assertThat(code).hasSize(6);
            assertThat(code.chars()).allMatch(c -> RoomCode.ALPHABET.indexOf(c) >= 0);
        }
    }

    @Test
    void randomCodesUseTheWholeAlphabet() {
        Random random = new Random(42);
        Set<Character> seen = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            for (char c : RoomCode.random(random).value().toCharArray()) {
                seen.add(c);
            }
        }
        assertThat(seen).hasSize(32);
    }

    @Test
    void theSameSeedGivesTheSameCodes() {
        assertThat(RoomCode.random(new Random(7))).isEqualTo(RoomCode.random(new Random(7)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ABCDE", "ABCDEFG", "abcdef", "ABCDE0", "ABCDEO", "ABCDE1", "ABCDEI", "ABC-EF"})
    void rejectsAnythingElse(String value) {
        assertThatThrownBy(() -> new RoomCode(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseForgivesCaseAndSurroundingSpaces() {
        assertThat(RoomCode.parse("  abc234 ")).contains(new RoomCode("ABC234"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "abc", "ABC 234", "ABCDE0", "ABCDEFG"})
    void parseGivesEmptyForWhatCantBeACode(String input) {
        assertThat(RoomCode.parse(input)).isEmpty();
    }

    @Test
    void parseGivesEmptyForNull() {
        assertThat(RoomCode.parse(null)).isEmpty();
    }
}

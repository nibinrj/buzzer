package dev.nibin.buzzer.identity.domain;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenSecretTest {

    @Test
    void generatesFortyThreeUrlSafeCharactersFrom32RandomBytes() {
        String value = RefreshTokenSecret.generate();

        assertThat(value).matches("[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(value)).hasSize(32);
    }

    @Test
    void generatesADifferentValueEveryTime() {
        Set<String> values = new HashSet<>();
        IntStream.range(0, 1_000).forEach(i -> values.add(RefreshTokenSecret.generate()));

        assertThat(values).hasSize(1_000);
    }

    @Test
    void hashIsStandardSha256InLowercaseHex() {
        // Test vector from FIPS 180-2: SHA-256("abc").
        assertThat(RefreshTokenSecret.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void hashIsDeterministicSoTokensCanBeLookedUpByIt() {
        String value = RefreshTokenSecret.generate();

        assertThat(RefreshTokenSecret.hash(value))
                .isEqualTo(RefreshTokenSecret.hash(value))
                .isNotEqualTo(RefreshTokenSecret.hash(value + "x"))
                .matches("[0-9a-f]{64}");
    }
}

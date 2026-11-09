package com.processing.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationRequestNormalizerTest {
    @ParameterizedTest(name = "terminal {0} -> {1}")
    @CsvSource({"TERM,TERM0000", "TERM0001,TERM0001", "TERM000123,TERM0001"})
    void shouldNormalizeTerminalIdWhenItIsProvided(String input, String expected) {
        // Arrange: input comes from the parameterized source.
        // Act
        String result = AuthorizationRequestNormalizer.normalizeTerminalId(input);

        // Assert
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest(name = "merchant {0} -> {1}")
    @CsvSource({
        "MERCH1,MERCH1000000000",
        "MERCH0000000001,MERCH0000000001",
        "MERCH00000000001,MERCH0000000001",
        "MERCH10000000001,MERCH1000000000",
        "OTHER00000000001,OTHER0000000000",
        "MERCH000000000001,MERCH0000000000"
    })
    void shouldNormalizeMerchantIdWhenItIsProvided(String input, String expected) {
        // Arrange: input comes from the parameterized source.
        // Act
        String result = AuthorizationRequestNormalizer.normalizeMerchantId(input);

        // Assert
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void shouldPreserveTerminalIdWhenItIsMissing(String input) {
        // Arrange: input comes from the parameterized source.
        // Act
        String result = AuthorizationRequestNormalizer.normalizeTerminalId(input);

        // Assert
        assertThat(result).isEqualTo(input);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void shouldPreserveMerchantIdWhenItIsMissing(String input) {
        // Arrange: input comes from the parameterized source.
        // Act
        String result = AuthorizationRequestNormalizer.normalizeMerchantId(input);

        // Assert
        assertThat(result).isEqualTo(input);
    }
}

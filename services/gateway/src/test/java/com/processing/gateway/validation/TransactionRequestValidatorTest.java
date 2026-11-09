package com.processing.gateway.validation;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionRequestValidatorTest {
    @ParameterizedTest(name = "amount={0}")
    @ValueSource(strings = {"1", "1000"})
    void shouldAcceptTransactionWhenRequestIsValid(String amount) {
        // Arrange
        TransactionRequestValidator validator = new TransactionRequestValidator();
        AuthorizationRequest request = request("0100", "4000001234567890", new BigDecimal(amount), "643", "5411");

        // Act / Assert
        assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "amount={0}")
    @ValueSource(strings = {"-1", "0"})
    void shouldRejectAmountWhenItIsZeroOrNegative(BigDecimal amount) {
        // Arrange
        TransactionRequestValidator validator = new TransactionRequestValidator();
        AuthorizationRequest request = request("0100", "4000001234567890", amount, "643", "5411");

        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request)).isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'amount' must be > 0");
    }

    @Test
    void shouldRejectAmountWhenItIsMissing() {
        // Arrange
        TransactionRequestValidator validator = new TransactionRequestValidator();
        AuthorizationRequest request = request("0100", "4000001234567890", null, "643", "5411");

        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request)).isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'amount' is required");
    }

    @ParameterizedTest(name = "pan={0}")
    @ValueSource(strings = {"400000123456789", "40000012345678901", "400000123456789X"})
    void shouldRejectPanWhenFormatIsInvalid(String pan) {
        // Arrange
        TransactionRequestValidator validator = new TransactionRequestValidator();
        AuthorizationRequest request = request("0100", pan, BigDecimal.ONE, "643", "5411");

        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request)).isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'pan' must be exactly 16 digits");
    }

    @ParameterizedTest(name = "mti={0}, currency={1}, mcc={2}")
    @CsvSource(delimiter = '|', value = {
        "0200|643|5411|Field 'mti' must be '0100'",
        "0100|64|5411|Field 'currencyCode' must be exactly 3 characters",
        "0100|6430|5411|Field 'currencyCode' must be exactly 3 characters",
        "0100|643|541|Field 'mcc' must be exactly 4 digits",
        "0100|643|54111|Field 'mcc' must be exactly 4 digits",
        "0100|643|ABCD|Field 'mcc' must be exactly 4 digits"
    })
    void shouldRejectRequestWhenContractFieldsAreInvalid(String mti, String currency, String mcc, String message) {
        // Arrange
        TransactionRequestValidator validator = new TransactionRequestValidator();
        AuthorizationRequest request = request(mti, "4000001234567890", BigDecimal.ONE, currency, mcc);

        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request)).isInstanceOf(TransactionValidationException.class)
                .hasMessage(message);
    }

    @ParameterizedTest(name = "required field {1}, value=[{2}]")
    @MethodSource("missingStringFields")
    void shouldRejectRequestWhenRequiredStringFieldIsMissing(int fieldIndex, String field, String value) {
        // Arrange
        TransactionRequestValidator validator = new TransactionRequestValidator();
        AuthorizationRequest request = requestWithStringField(fieldIndex, value);

        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request)).isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field '" + field + "' is required");
    }

    @Test
    void shouldRejectRequestWhenTransmissionTimeIsMissing() {
        // Arrange
        TransactionRequestValidator validator = new TransactionRequestValidator();
        AuthorizationRequest valid = request("0100", "4000001234567890", BigDecimal.ONE, "643", "5411");
        AuthorizationRequest request = new AuthorizationRequest(valid.mti(), valid.stan(), valid.pan(),
                valid.processingCode(), valid.amount(), valid.currencyCode(), null, valid.terminalId(),
                valid.terminalType(), valid.merchantId(), valid.mcc(), valid.acquirerId(), valid.issuerId());

        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request)).isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'transmissionDateTime' is required");
    }

    @Test
    void shouldRejectRequestWhenBodyIsMissing() {
        // Arrange
        TransactionRequestValidator validator = new TransactionRequestValidator();

        // Act / Assert
        assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(TransactionValidationException.class)
                .hasMessage("Request body is required");
    }

    private static Stream<Arguments> missingStringFields() {
        List<String> names = List.of("mti", "stan", "pan", "processingCode", "currencyCode",
                "terminalId", "merchantId", "mcc", "acquirerId");
        return IntStream.range(0, names.size()).boxed().flatMap(index -> Stream.of(null, "", " ")
                .map(value -> Arguments.of(index, names.get(index), value)));
    }

    private AuthorizationRequest requestWithStringField(int index, String value) {
        String[] fields = {"0100", "000001", "4000001234567890", "000000", "643",
                "TERM0001", "MERCH0000000001", "5411", "ACQ001"};
        fields[index] = value;
        return new AuthorizationRequest(fields[0], fields[1], fields[2], fields[3], BigDecimal.ONE, fields[4],
                Instant.parse("2026-10-15T10:00:00Z"), fields[5], "POS", fields[6], fields[7], fields[8], null);
    }

    private AuthorizationRequest request(String mti, String pan, BigDecimal amount, String currency, String mcc) {
        return new AuthorizationRequest(mti, "000001", pan, "000000", amount, currency,
                Instant.parse("2026-10-15T10:00:00Z"), "TERM0001", "POS", "MERCH0000000001", mcc, "ACQ001", null);
    }
}

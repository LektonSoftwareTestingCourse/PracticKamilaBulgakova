package com.processing.apitests;

import com.processing.apitests.support.ApiTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static com.processing.apitests.support.Api.TRANSACTIONS;
import static com.processing.apitests.support.Api.gatewayTransaction;
import static com.processing.apitests.support.Requests.TEST_BIN;
import static com.processing.apitests.support.Requests.transaction;
import static com.processing.apitests.support.Requests.unusedPan;
import static com.processing.apitests.support.Requests.with;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

class GatewayTransactionTest extends ApiTest {
    @Test
    void approvesAndReturnsAuthorizationSchema() {
        Map<String, Object> request = transaction(cards.create());

        gatewayTransaction().body(request)
                .when().post(TRANSACTIONS)
                // docs/api-spec.md §3 and AuthorizationResponse; MTI discrepancy is in the report.
                .then().statusCode(200).header("Content-Type", containsString("application/json"))
                .body(matchesJsonSchemaInClasspath("schemas/approved-authorization.json"))
                .body("stan", equalTo(request.get("stan")))
                .body("mti", equalTo("0100"))
                .body("responseCode", equalTo("00"))
                .body("status", equalTo("APPROVED"))
                .body("rrn", matchesPattern("[0-9]{12}"))
                .body("authCode", matchesPattern("[A-Z0-9]{6}"))
                .body("declineReason", nullValue())
                .body("processingTimeMs", greaterThanOrEqualTo(0));
    }

    @Test
    void acceptsSmallestMinorUnitWithoutTerminalType() {
        Map<String, Object> request = transaction(cards.create());
        request.remove("terminalType");
        request.put("amount", 1);

        gatewayTransaction().body(request)
                .when().post(TRANSACTIONS)
                .then().statusCode(200)
                .body("stan", equalTo(request.get("stan")))
                .body("status", equalTo("APPROVED"));
    }

    @ParameterizedTest(name = "{0} is required, value class {1}")
    @MethodSource("missingRequiredFields")
    void rejectsMissingRequiredField(String field, String valueClass) {
        Map<String, Object> request = transaction(unusedPan(TEST_BIN));
        switch (valueClass) {
            case "omitted" -> request.remove(field);
            case "null" -> request.put(field, null);
            case "blank" -> request.put(field, " ");
            default -> throw new IllegalArgumentException(valueClass);
        }

        gatewayTransaction().body(request)
                .when().post(TRANSACTIONS)
                // TransactionRequestValidator.requireValue
                .then().statusCode(400)
                .body("error", equalTo("VALIDATION_ERROR"))
                .body("message", equalTo("Field '" + field + "' is required"))
                .body("serviceName", equalTo("gateway"))
                .body("timestamp", notNullValue())
                .body("retryAfterMs", nullValue());
    }

    static Stream<Arguments> missingRequiredFields() {
        Stream<String> texts = Stream.of("mti", "stan", "pan", "processingCode", "currencyCode",
                "terminalId", "merchantId", "mcc", "acquirerId");
        Stream<Arguments> textCases = texts.flatMap(field -> Stream.of("omitted", "null", "blank")
                .map(kind -> Arguments.of(field, kind)));
        Stream<Arguments> otherCases = Stream.of("amount", "transmissionDateTime")
                .flatMap(field -> Stream.of("omitted", "null").map(kind -> Arguments.of(field, kind)));
        return Stream.concat(textCases, otherCases);
    }

    @ParameterizedTest(name = "{0} rejects [{1}]")
    @MethodSource("invalidValues")
    void rejectsOneInvalidValueAtATime(String field, Object value, String message) {
        Map<String, Object> request = with(transaction(unusedPan(TEST_BIN)), field, value);

        gatewayTransaction().body(request)
                .when().post(TRANSACTIONS)
                // TransactionRequestValidator
                .then().statusCode(400).body("error", equalTo("VALIDATION_ERROR"))
                .body("message", equalTo(message))
                .body("serviceName", equalTo("gateway"));
    }

    static Stream<Arguments> invalidValues() {
        return Stream.of(
                Arguments.of("mti", "0110", "Field 'mti' must be '0100'"),
                Arguments.of("pan", "400000000000000", "Field 'pan' must be exactly 16 digits"),
                Arguments.of("pan", "40000000000000000", "Field 'pan' must be exactly 16 digits"),
                Arguments.of("pan", "400000000000000A", "Field 'pan' must be exactly 16 digits"),
                Arguments.of("amount", 0, "Field 'amount' must be > 0"),
                Arguments.of("amount", -1, "Field 'amount' must be > 0"),
                Arguments.of("currencyCode", "64", "Field 'currencyCode' must be exactly 3 characters"),
                Arguments.of("currencyCode", "6430", "Field 'currencyCode' must be exactly 3 characters"),
                Arguments.of("mcc", "541", "Field 'mcc' must be exactly 4 digits"),
                Arguments.of("mcc", "54110", "Field 'mcc' must be exactly 4 digits"),
                Arguments.of("mcc", "541A", "Field 'mcc' must be exactly 4 digits"));
    }
}

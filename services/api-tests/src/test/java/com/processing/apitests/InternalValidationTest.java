package com.processing.apitests;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static com.processing.apitests.support.Api.AUTHORIZE;
import static com.processing.apitests.support.Api.ROLLBACK;
import static com.processing.apitests.support.Api.ROUTE;
import static com.processing.apitests.support.Api.authorization;
import static com.processing.apitests.support.Api.routing;
import static com.processing.apitests.support.Requests.TEST_BIN;
import static com.processing.apitests.support.Requests.nextRrn;
import static com.processing.apitests.support.Requests.transaction;
import static com.processing.apitests.support.Requests.unusedPan;
import static com.processing.apitests.support.Requests.with;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

class InternalValidationTest {
    @ParameterizedTest(name = "Authorization rejects {0} = [{1}]")
    @MethodSource("invalidAuthorizationFields")
    void rejectsInvalidAuthorizationRequest(String field, Object invalid) {
        Map<String, Object> request = with(transaction(unusedPan(TEST_BIN)), "issuerId", "ISS001");
        request.put(field, invalid);

        authorization().body(request)
                .when().post(AUTHORIZE)
                // Known defect: Spring's default error body does not identify the field or rule.
                .then().statusCode(400).body("error", equalTo("Bad Request"))
                .body("path", equalTo(AUTHORIZE)).body("status", equalTo(400)).body("message", nullValue());
    }

    static Stream<Arguments> invalidAuthorizationFields() {
        Stream<Arguments> blank = Stream.of("mti", "stan", "pan", "processingCode", "currencyCode",
                        "terminalId", "merchantId", "mcc", "acquirerId", "issuerId")
                .map(field -> Arguments.of(field, ""));
        Map<String, Integer> lengths = Map.of("mti", 4, "stan", 6, "pan", 16,
                "processingCode", 6, "currencyCode", 3, "terminalId", 8, "merchantId", 15, "mcc", 4);
        Stream<Arguments> wrongLength = lengths.entrySet().stream()
                .flatMap(field -> Stream.of(field.getValue() - 1, field.getValue() + 1)
                        .map(length -> Arguments.of(field.getKey(), "0".repeat(length))));
        return Stream.of(blank, wrongLength, Stream.of(Arguments.of("amount", null),
                Arguments.of("amount", -1), Arguments.of("transmissionDateTime", null),
                Arguments.of("transmissionDateTime", "not-a-date"))).flatMap(values -> values);
    }

    @ParameterizedTest(name = "Rollback rejects {0} = [{1}]")
    @MethodSource("invalidRollbackFields")
    void rejectsInvalidAuthorizationRollback(String field, Object invalid) {
        Map<String, Object> request = with(Map.of("pan", unusedPan(TEST_BIN), "rrn", nextRrn(), "amount", 1),
                field, invalid);

        authorization().body(request)
                .when().post(ROLLBACK)
                // Known defect: the default error body omits the invalid field and its rule.
                .then().statusCode(400).body("error", equalTo("Bad Request"))
                .body("path", equalTo(ROLLBACK)).body("status", equalTo(400)).body("message", nullValue());
    }

    static Stream<Arguments> invalidRollbackFields() {
        return Stream.of(Arguments.of("pan", null),
                Arguments.of("pan", "1".repeat(15)), Arguments.of("pan", "1".repeat(17)),
                Arguments.of("pan", "1".repeat(15) + "A"), Arguments.of("rrn", null),
                Arguments.of("rrn", "1".repeat(11)), Arguments.of("rrn", "1".repeat(13)),
                Arguments.of("rrn", "1".repeat(11) + "A"), Arguments.of("amount", null),
                Arguments.of("amount", 0), Arguments.of("amount", -1));
    }

    @Test
    void rejectsMalformedSwitchBody() {
        routing().body("{")
                .when().post(ROUTE)
                .then().statusCode(400).body("path", equalTo(ROUTE)).body("error", equalTo("Bad Request"));
    }

    @Test
    @Tag("switch-missing-pan")
    void documentsSwitchDeclineForMissingPan() {
        Map<String, Object> request = transaction(unusedPan(TEST_BIN));
        request.remove("pan");

        routing().body(request)
                .when().post(ROUTE)
                // RouteController and RouteService do not apply the Gateway validator.
                .then().statusCode(200).body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("14")).body("declineReason", equalTo("CARD_NOT_FOUND"));
    }
}

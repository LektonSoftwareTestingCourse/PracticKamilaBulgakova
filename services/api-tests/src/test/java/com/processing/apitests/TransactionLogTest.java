package com.processing.apitests;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.processing.apitests.support.ApiTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static com.processing.apitests.support.Api.LOG;
import static com.processing.apitests.support.Api.SEARCH;
import static com.processing.apitests.support.Api.TRANSACTIONS;
import static com.processing.apitests.support.Api.gateway;
import static com.processing.apitests.support.Api.gatewayTransaction;
import static com.processing.apitests.support.Api.logger;
import static com.processing.apitests.support.Requests.TEST_BIN;
import static com.processing.apitests.support.Requests.logEntry;
import static com.processing.apitests.support.Requests.transaction;
import static com.processing.apitests.support.Requests.unusedPan;
import static com.processing.apitests.support.Requests.with;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

class TransactionLogTest extends ApiTest {
    @Test
    void returnsOriginalRecordOnExactRedelivery() {
        Map<String, Object> entry = logEntry(cards.create());
        String id = (String) entry.get("id");
        store(entry);

        logger().body(entry)
                .when().post(LOG)
                // InternalTransactionController returns 200 for identical redelivery.
                .then().statusCode(200).body("id", equalTo(id))
                .body("stan", equalTo(entry.get("stan")))
                .body("amount", equalTo(entry.get("amount")));
        assertSingleOriginal(entry);
    }

    @Test
    void rejectsConflictingRedeliveryWithoutReplacingOriginal() {
        Map<String, Object> entry = logEntry(cards.create());
        store(entry);

        logger().body(with(entry, "amount", 20_000))
                .when().post(LOG)
                .then().statusCode(409).body("error", equalTo("Transaction conflict"))
                .body("message", notNullValue()).body("serviceName", equalTo("transaction-logger"));
        assertSingleOriginal(entry);
    }

    @Test
    void approvedTransactionReachesSearchThroughGateway() {
        String pan = cards.create();
        Map<String, Object> request = transaction(pan);

        String rrn = gatewayTransaction().body(request)
                .when().post(TRANSACTIONS)
                .then().statusCode(200).body("responseCode", equalTo("00")).extract().path("rrn");
        String matching = "transactions.findAll { it.stan == '%s' }".formatted(request.get("stan"));

        await().atMost(ofSeconds(15)).pollInterval(ofMillis(500)).untilAsserted(() ->
                gateway().queryParam("pan", pan)
                        .when().get(SEARCH)
                        .then().statusCode(200)
                        .body(matching, hasSize(1))
                        .body(matching + "[0].id", matchesPattern("[0-9a-f-]{36}"))
                        .body(matching + "[0].rrn", equalTo(rrn))
                        .body(matching + "[0].status", equalTo("APPROVED"))
                        .body(matching + "[0].amount", equalTo(request.get("amount")))
                        .body(matching + "[0].declineReason", nullValue()));
    }

    @ParameterizedTest(name = "Logger rejects {0} = [{1}]")
    @MethodSource("invalidLogFields")
    void rejectsInvalidLogEntry(String field, Object invalid, String rule) {
        Map<String, Object> entry = with(logEntry(unusedPan(TEST_BIN)), field, invalid);

        logger().body(entry)
                .when().post(LOG)
                // TransactionRequest validation and Logger GlobalExceptionHandler.
                .then().statusCode(400).body("error", equalTo("Validation error"))
                .body("message", containsString(field + ": " + rule))
                .body("serviceName", equalTo("transaction-logger"));
    }

    static Stream<Arguments> invalidLogFields() {
        Stream<Arguments> missing = Stream.of("id", "amount", "status", "transmissionDateTime", "createdAt")
                .map(field -> Arguments.of(field, null, "must not be null"));
        Stream<Arguments> blank = Stream.of("mti", "stan", "pan", "processingCode", "currencyCode",
                        "terminalId", "merchantId", "mcc", "acquirerId")
                .map(field -> Arguments.of(field, "", "must not be blank"));
        return Stream.of(missing, blank, Stream.of(
                Arguments.of("mti", "01000", "size must be between 0 and 4"),
                Arguments.of("stan", "12345", "stan must be exactly 6 digits"),
                Arguments.of("stan", "1234567", "stan must be exactly 6 digits"),
                Arguments.of("stan", "12345A", "stan must be exactly 6 digits"),
                Arguments.of("rrn", "0".repeat(13), "size must be between 0 and 12"),
                Arguments.of("pan", "0".repeat(15), "PAN number must contain exactly 16 digits"),
                Arguments.of("pan", "0".repeat(17), "PAN number must contain exactly 16 digits"),
                Arguments.of("pan", "0".repeat(15) + "A", "PAN number must contain exactly 16 digits"),
                Arguments.of("processingCode", "12345", "processingCode must be exactly 6 digits"),
                Arguments.of("processingCode", "1234567", "processingCode must be exactly 6 digits"),
                Arguments.of("processingCode", "12345A", "processingCode must be exactly 6 digits"),
                Arguments.of("amount", 0, "must be greater than 0"),
                Arguments.of("amount", -1, "must be greater than 0"),
                Arguments.of("currencyCode", "64", "currencyCode must be exactly 3 digits"),
                Arguments.of("currencyCode", "6430", "currencyCode must be exactly 3 digits"),
                Arguments.of("currencyCode", "64A", "currencyCode must be exactly 3 digits"),
                Arguments.of("terminalId", "A".repeat(9), "size must be between 0 and 8"),
                Arguments.of("terminalType", "A".repeat(11), "size must be between 0 and 10"),
                Arguments.of("merchantId", "A".repeat(16), "size must be between 0 and 15"),
                Arguments.of("mcc", "541", "mcc must be exactly 4 digits"),
                Arguments.of("mcc", "54110", "mcc must be exactly 4 digits"),
                Arguments.of("mcc", "541A", "mcc must be exactly 4 digits"),
                Arguments.of("acquirerId", "A".repeat(11), "size must be between 0 and 10"),
                Arguments.of("issuerId", "A".repeat(11), "size must be between 0 and 10"),
                Arguments.of("acquiringFee", -1, "must be greater than or equal to 0"),
                Arguments.of("declineReason", "A".repeat(101), "size must be between 0 and 100"),
                Arguments.of("authCode", "A".repeat(7), "size must be between 0 and 6"),
                Arguments.of("processingTimeMs", -1, "must be greater than or equal to 0")))
                .flatMap(values -> values);
    }

    @ParameterizedTest
    @CsvSource({"id,not-a-uuid", "status,UNKNOWN", "createdAt,not-a-date",
        "transmissionDateTime,not-a-date", "amount,not-a-number"})
    void documentsGenericMessageForMalformedLogValue(String field, String invalid) {
        Map<String, Object> entry = with(logEntry(unusedPan(TEST_BIN)), field, invalid);

        logger().body(entry)
                .when().post(LOG)
                .then().statusCode(400).body("error", equalTo("Invalid request body"))
                // Known defect: handleMalformedJson returns the same message for each field.
                .body("message", equalTo("Request body is malformed or contains unsupported values"))
                .body("serviceName", equalTo("transaction-logger"));
    }

    @Test
    void documentsInternalErrorForUnsupportedContentType() throws JsonProcessingException {
        String entry = new JsonMapper().writeValueAsString(logEntry(unusedPan(TEST_BIN)));

        logger().contentType("text/plain").body(entry)
                .when().post(LOG)
                // Logger GlobalExceptionHandler handles an unsupported media type as a generic 500.
                .then().statusCode(500).body("error", equalTo("Internal server error"))
                .body("message", equalTo("Unexpected transaction logger error"))
                .body("serviceName", equalTo("transaction-logger")).body("retryAfterMs", equalTo("0"));
    }

    private void assertSingleOriginal(Map<String, Object> entry) {
        String matching = "transactions.findAll { it.id == '%s' }".formatted(entry.get("id"));
        gateway().queryParam("pan", entry.get("pan"))
                .when().get(SEARCH)
                .then().statusCode(200).body("total", equalTo(1))
                .body(matching, hasSize(1)).body(matching + "[0].amount", equalTo(entry.get("amount")));
    }

    static void store(Map<String, Object> entry) {
        logger().body(entry)
                .when().post(LOG)
                .then().statusCode(201).body("id", equalTo(entry.get("id"))).body("status", equalTo("stored"));
    }
}

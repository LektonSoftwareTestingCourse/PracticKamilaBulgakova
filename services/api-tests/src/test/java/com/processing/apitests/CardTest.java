package com.processing.apitests;

import com.processing.apitests.support.ApiTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.processing.apitests.support.Api.CARDS;
import static com.processing.apitests.support.Api.cardManagement;
import static com.processing.apitests.support.Api.gateway;
import static com.processing.apitests.support.Requests.BALANCE;
import static com.processing.apitests.support.Requests.DAILY_LIMIT;
import static com.processing.apitests.support.Requests.MONTHLY_LIMIT;
import static com.processing.apitests.support.Requests.TEST_BIN;
import static com.processing.apitests.support.Requests.card;
import static com.processing.apitests.support.Requests.nextRrn;
import static com.processing.apitests.support.Requests.unusedPan;
import static com.processing.apitests.support.Requests.with;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.isIn;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.oneOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CardTest extends ApiTest {
    private static final String BY_PAN = CARDS + "/{pan}";

    @Test
    void documentsStaleGatewayCacheAfterCardDeletion() {
        String pan = cards.create();

        gateway().pathParam("pan", pan)
                .when().get(BY_PAN)
                .then().statusCode(200).header("X-Cache", equalTo("MISS"))
                .body("bin", equalTo(TEST_BIN)).body("availableBalance", equalTo(BALANCE))
                .body("dailyLimit", equalTo(DAILY_LIMIT)).body("monthlyLimit", equalTo(MONTHLY_LIMIT))
                .body("cardholderName", equalTo("API TESTER"))
                // CardModel serializes YearMonth as yyyy-MM; compare with the contract in the report.
                .body("expiryDate", matchesPattern("[0-9]{4}-(0[1-9]|1[0-2])"))
                .body("issuerId", notNullValue());

        gateway().pathParam("pan", pan)
                .when().get(BY_PAN)
                .then().statusCode(200).header("X-Cache", equalTo("HIT")).body("pan", equalTo(pan));

        gateway().pathParam("pan", pan).body(Map.of("status", "BLOCKED", "availableBalance", 50_000))
                .when().patch(BY_PAN)
                .then().statusCode(200).body("pan", equalTo(pan)).body("status", equalTo("BLOCKED"))
                .body("availableBalance", equalTo(50_000)).body("dailyLimit", equalTo(DAILY_LIMIT));

        await().atMost(ofSeconds(10)).pollInterval(ofMillis(200)).untilAsserted(() ->
                gateway().pathParam("pan", pan)
                        .when().get(BY_PAN)
                        .then().statusCode(200).body("status", equalTo("BLOCKED")));
        gateway().pathParam("pan", pan)
                .when().delete(BY_PAN)
                .then().statusCode(204).body(emptyOrNullString());
        cards.forget(pan);
        cardManagement().pathParam("pan", pan)
                .when().get(BY_PAN)
                .then().statusCode(404).body("error", equalTo("CardNotFoundException"))
                .body("serviceName", equalTo("card-management"));
        gateway().pathParam("pan", pan)
                .when().get(BY_PAN)
                // Known defect: ResponseCachingGatewayFilterFactory ignores DELETE responses with status 204.
                .then().statusCode(200).header("X-Cache", equalTo("HIT"))
                .body("pan", equalTo(pan)).body("status", equalTo("BLOCKED"));
    }

    @Test
    void filtersCardsByStatusBinIssuerAndCreationWindow() {
        String first = Instant.now().minusSeconds(1).toString();
        String pan = cards.create();
        String issuer = cards.issuer(pan);
        String last = Instant.now().plusSeconds(1).toString();
        cards.patch(pan, Map.of("status", "INACTIVE"));

        gateway().queryParams(Map.of("status", "INACTIVE", "bin", TEST_BIN, "issuerId", issuer,
                        "startDate", first, "endDate", last, "limit", 100, "offset", 0))
                .when().get(CARDS)
                .then().statusCode(200).body("total", greaterThanOrEqualTo(1))
                .body("cards.findAll { it.pan == '%s' }".formatted(pan), hasSize(1));
    }

    @Test
    void returnsEmptyPageForMissingIssuer() {
        gateway().queryParams(Map.of("issuerId", "NOISSUER01", "limit", 1, "offset", 0))
                .when().get(CARDS)
                .then().statusCode(200).body("total", equalTo(0)).body("cards", hasSize(0));
    }

    @Test
    void defaultsToTenCardsWithZeroOffset() {
        String bin = cards.registerBin();
        List<String> pans = new ArrayList<>();
        for (int index = 0; index < 11; index++) {
            pans.add(cards.create(with(card(), "bin", bin)));
        }

        List<String> firstPage = gateway().queryParam("bin", bin)
                .when().get(CARDS)
                .then().statusCode(200).body("total", equalTo(11))
                .body("cards.pan", hasSize(10)).body("cards.pan", everyItem(isIn(pans)))
                .extract().jsonPath().getList("cards.pan");
        assertEquals(10, new HashSet<>(firstPage).size());

        // CardCriteriaBuilderJpaRepositoryImpl does not define an order.
        gateway().queryParam("bin", bin).queryParam("offset", 10)
                .when().get(CARDS)
                .then().statusCode(200).body("total", equalTo(11))
                .body("cards.pan", hasSize(1)).body("cards.pan", everyItem(isIn(pans)));
    }

    @Test
    void documentsOffsetWraparoundInCardSearch() {
        String bin = cards.registerBin();
        String pan = cards.create(with(card(), "bin", bin));

        gateway().queryParams(Map.of("bin", bin, "offset", Integer.MAX_VALUE))
                .when().get(CARDS)
                .then().statusCode(200).body("total", equalTo(1)).body("cards", hasSize(0));
        gateway().queryParams(Map.of("bin", bin, "offset", 4_294_967_296L))
                .when().get(CARDS)
                // Known defect: narrowing this offset to int wraps to zero.
                .then().statusCode(200).body("total", equalTo(1)).body("cards.pan", equalTo(List.of(pan)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PATCH", "DELETE", "reserve", "rollback"})
    void reportsMissingCardFromCardManagement(String operation) {
        String pan = unusedPan(TEST_BIN);
        var request = gateway().pathParam("pan", pan);
        Response response = switch (operation) {
            case "PATCH" -> request.body(Map.of("status", "BLOCKED")).when().patch(BY_PAN);
            case "reserve", "rollback" -> request.body(Map.of("amount", 1, "rrn", nextRrn(), "pan", pan))
                    .when().post(BY_PAN + "/" + operation);
            default -> request.when().request(operation, BY_PAN);
        };

        response.then().statusCode(404).body("error", equalTo("CardNotFoundException"))
                .body("message", equalTo("Card with PAN " + pan.substring(0, 4)
                        + "********" + pan.substring(12) + " was not found"))
                .body("serviceName", equalTo("card-management"));
    }

    @Test
    void documentsCardCreationGeneratesPanEvenWhenRequestContainsPan() {
        String requestedPan = unusedPan("999999");
        Map<String, Object> request = with(card(), "pan", requestedPan);

        // CreateCardRequest has no PAN field, so the documented conflict by input PAN cannot be triggered.
        String first = cards.create(request);
        String second = cards.create(request);

        assertEquals(TEST_BIN, first.substring(0, 6));
        assertEquals(TEST_BIN, second.substring(0, 6));
        assertNotEquals(first, second);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PATCH", "DELETE", "reserve", "rollback"})
    void rejectsMalformedPathPan(String operation) {
        var request = gateway().pathParam("pan", "40000000000000A");
        Response response = switch (operation) {
            case "PATCH" -> request.body(Map.of("status", "BLOCKED")).when().patch(BY_PAN);
            case "reserve", "rollback" -> request.body(Map.of("amount", 1, "rrn", nextRrn(),
                            "pan", unusedPan(TEST_BIN)))
                    .when().post(BY_PAN + "/" + operation);
            default -> request.when().request(operation, BY_PAN);
        };

        response.then().statusCode(400).body("message", containsString("PAN number must contain exactly 16 digits"))
                .body("serviceName", notNullValue());
    }

    @ParameterizedTest
    @MethodSource("invalidCreateFields")
    void rejectsInvalidCardCreationField(String field, Object value, String message) {
        Map<String, Object> request = with(card(), field, value);

        gateway().body(request)
                .when().post(CARDS)
                // CreateCardRequest and Card Management GlobalExceptionHandler
                .then().statusCode(400).body("error", equalTo("MethodArgumentNotValidException"))
                .body("message", containsString(message)).body("serviceName", notNullValue());
    }

    static Stream<Arguments> invalidCreateFields() {
        return Stream.of(
                Arguments.of("bin", null, "must not be blank"),
                Arguments.of("bin", "40000", "BIN number must contain exactly 6 digits"),
                Arguments.of("bin", "4000000", "BIN number must contain exactly 6 digits"),
                Arguments.of("bin", "40000A", "BIN number must contain exactly 6 digits"),
                Arguments.of("cardholderName", null, "must not be blank"),
                Arguments.of("cardholderName", "1", "Cardholder"),
                Arguments.of("currencyCode", null, "must not be blank"),
                Arguments.of("currencyCode", "64", "The length must be exactly 3 symbols"),
                Arguments.of("currencyCode", "6430", "The length must be exactly 3 symbols"),
                Arguments.of("currencyCode", "64A", "Value can contain digits only"),
                Arguments.of("dailyLimit", -1, "Value can not be negative"),
                Arguments.of("monthlyLimit", -1, "Value can not be negative"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"dailyLimit", "monthlyLimit"})
    void rejectsNegativeCardPatchValues(String field) {
        String pan = cards.create();

        gateway().pathParam("pan", pan).body(Map.of(field, -1))
                .when().patch(BY_PAN)
                .then().statusCode(400).body("error", equalTo("MethodArgumentNotValidException"))
                // Known defect: the handler returns the rule without the field name.
                .body("message", equalTo("Value can not be negative"));
    }

    @Test
    void generatesOneCard() {
        Response generated = gateway().body(Map.of("count", 1, "bins", List.of(TEST_BIN)))
                .when().post(CARDS + "/generate");
        if (generated.statusCode() == 201) {
            generated.jsonPath().<String>getList("cards.pan").forEach(cards::remember);
        }

        generated.then().statusCode(201).body("generated", equalTo(1)).body("cards", hasSize(1))
                .body("cards[0].bin", equalTo(TEST_BIN)).body("cards[0].pan", matchesPattern("[0-9]{16}"));
    }

    @Test
    void documentsAcceptedNegativeInitialAndPatchedBalance() {
        // These balance fields have no NotNegative constraint in the two request DTOs.
        String pan = cards.create(with(card(), "initialBalance", -1));

        gateway().pathParam("pan", pan)
                .when().get(BY_PAN)
                .then().statusCode(200).body("availableBalance", equalTo(-1));
        gateway().pathParam("pan", pan).body(Map.of("availableBalance", -2))
                .when().patch(BY_PAN)
                .then().statusCode(200).body("availableBalance", equalTo(-2));
    }

    @Test
    void rejectsGeneratedCountOverConfiguredMaximum() {
        gateway().body(Map.of("count", 10_001, "bins", List.of(TEST_BIN)))
                .when().post(CARDS + "/generate")
                .then().statusCode(400).body("error", equalTo("CardGenerationLimitException"))
                .body("message", containsString("10000"));
    }

    @ParameterizedTest
    @MethodSource("invalidGeneration")
    void rejectsInvalidGeneration(Map<String, Object> request, String message) {
        gateway().body(request)
                .when().post(CARDS + "/generate")
                .then().statusCode(400).body("message", containsString(message))
                .body("serviceName", notNullValue());
    }

    static Stream<Arguments> invalidGeneration() {
        return Stream.of(
                Arguments.of(Map.of("count", 0, "bins", List.of(TEST_BIN)), "must be greater than or equal to 1"),
                Arguments.of(Map.of("count", 1), "must not be empty"),
                Arguments.of(Map.of("count", 1, "bins", List.of()), "must not be empty"),
                Arguments.of(Map.of("count", 1, "bins", List.of("40000")), "BIN number must contain exactly 6 digits"));
    }

    @Test
    void rejectsWellFormedUnregisteredBinOnCreate() {
        String bin = "999999";

        gateway().body(with(card(), "bin", bin))
                .when().post(CARDS)
                // BinIssuerServiceImpl and Card Management GlobalExceptionHandler.
                .then().statusCode(404).body("error", equalTo("BinNotFoundException"))
                .body("message", equalTo("BIN not found: Unknown BIN: " + bin))
                .body("serviceName", equalTo("card-management"));
    }

    @Test
    void rejectsWellFormedUnregisteredBinOnGenerate() {
        String bin = "999999";

        gateway().body(Map.of("count", 1, "bins", List.of(bin)))
                .when().post(CARDS + "/generate")
                .then().statusCode(404).body("error", equalTo("BinNotFoundException"))
                .body("message", equalTo("BIN not found: Unknown BIN: " + bin))
                .body("serviceName", equalTo("card-management"));
    }

    @Test
    void bulkUpdatesOnlyRequestedFixture() {
        String pan = cards.create();
        String other = cards.create();

        gateway().body(Map.of("pans", List.of(pan), "status", "BLOCKED"))
                .when().post(CARDS + "/bulk-update")
                .then().statusCode(200).body("updated", equalTo(1));
        gateway().pathParam("pan", pan)
                .when().get(BY_PAN)
                .then().statusCode(200).body("status", equalTo("BLOCKED"));
        gateway().pathParam("pan", other)
                .when().get(BY_PAN)
                .then().statusCode(200).body("status", equalTo("ACTIVE"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsMissingOrConflictingBulkSelectors(boolean both) {
        Map<String, Object> request = both
                ? Map.of("status", "INACTIVE", "bins", List.of(TEST_BIN), "pans", List.of(unusedPan(TEST_BIN)))
                : Map.of("status", "INACTIVE");

        gateway().body(request)
                .when().post(CARDS + "/bulk-update")
                .then().statusCode(400).body("error", equalTo("IllegalArgumentException"))
                .body("message", containsString("bin or pans must be provided"));
    }

    @Test
    void bulkUpdatesOnlyCardsInRequestedBin() {
        String bin = cards.registerBin();
        String first = cards.create(with(card(), "bin", bin));
        String second = cards.create(with(card(), "bin", bin));
        String other = cards.create();

        gateway().body(Map.of("bins", List.of(bin), "status", "BLOCKED"))
                .when().post(CARDS + "/bulk-update")
                .then().statusCode(200).body("updated", equalTo(2));
        for (String pan : List.of(first, second, other)) {
            gateway().pathParam("pan", pan)
                    .when().get(BY_PAN)
                    .then().statusCode(200).body("status", equalTo(pan.equals(other) ? "ACTIVE" : "BLOCKED"));
        }
    }

    @ParameterizedTest
    @MethodSource("invalidBulkFields")
    void reportsInvalidBulkUpdateFields(Map<String, Object> request, int status, String error, String message) {
        // Expected 500 responses are tested at the source to avoid opening Gateway's shared circuit breaker.
        var api = status == 500 ? cardManagement() : gateway();

        api.body(request)
                .when().post(CARDS + "/bulk-update")
                .then().statusCode(status).body("error", equalTo(error))
                .body("message", containsString(message)).body("serviceName", equalTo("card-management"));
    }

    static Stream<Arguments> invalidBulkFields() {
        String validation = "MethodArgumentNotValidException";
        return Stream.of(
                Arguments.of(Map.of("bins", List.of(TEST_BIN)), 400, validation, "must not be null"),
                // Known defect: JSON conversion of an unsupported status returns 500.
                Arguments.of(Map.of("status", "UNKNOWN", "bins", List.of(TEST_BIN)), 500,
                        "HttpMessageNotReadableException", "UNKNOWN"),
                Arguments.of(Map.of("status", "BLOCKED", "bins", List.of("40000")),
                        400, validation, "BIN number must contain exactly 6 digits"),
                Arguments.of(Map.of("status", "BLOCKED", "pans", List.of("40000000000000A")),
                        400, validation, "PAN number must contain exactly 16 digits"));
    }

    @Test
    void reservesAndRollsBackFundsAndRejectsDuplicates() {
        String pan = cards.create();
        Map<String, Object> request = Map.of("amount", 10_000, "rrn", nextRrn(), "pan", pan);

        gateway().pathParam("pan", pan).body(request)
                .when().post(BY_PAN + "/reserve")
                .then().statusCode(200).body("availableBalance", equalTo(BALANCE - 10_000));
        gateway().pathParam("pan", pan).body(request)
                .when().post(BY_PAN + "/reserve")
                .then().statusCode(409).body("error", equalTo("ReservationAlreadyExistsException"))
                .body("message", containsString((String) request.get("rrn")));
        gateway().pathParam("pan", pan).body(request)
                .when().post(BY_PAN + "/rollback")
                .then().statusCode(200).body("availableBalance", equalTo(BALANCE));
        gateway().pathParam("pan", pan).body(request)
                .when().post(BY_PAN + "/rollback")
                .then().statusCode(409).body("error", equalTo("RollbackAlreadySatisfiedException"))
                .body("message", notNullValue());
    }

    @Test
    void rejectsReservationBeyondAvailableBalance() {
        gateway().pathParam("pan", cards.create()).body(Map.of("amount", BALANCE + 1, "rrn", nextRrn()))
                .when().post(BY_PAN + "/reserve")
                .then().statusCode(402).body("error", equalTo("InsufficientFundsException"))
                .body("message", notNullValue());
    }

    @ParameterizedTest
    @MethodSource("invalidAdjustments")
    void rejectsInvalidReserveAndRollbackRequests(String field, Object value, String rule) {
        String pan = cards.create();
        Map<String, Object> request = with(Map.of("amount", 1, "rrn", nextRrn(), "pan", pan), field, value);

        if (field.equals("amount") && value == null) {
            cardManagement().pathParam("pan", pan).body(request)
                    .when().post(BY_PAN + "/reserve")
                    // Known defect: a missing reserve amount reaches Card.startReservation.
                    .then().statusCode(500).body("error", equalTo("NullPointerException"))
                    .body("serviceName", equalTo("card-management"));
        } else {
            gateway().pathParam("pan", pan).body(request)
                    .when().post(BY_PAN + "/reserve")
                    .then().statusCode(400).body("message", equalTo(rule))
                    .body("error", equalTo("MethodArgumentNotValidException"));
        }
        gateway().pathParam("pan", pan).body(request)
                .when().post(BY_PAN + "/rollback")
                .then().statusCode(400).body("message", equalTo(rule))
                .body("error", equalTo("MethodArgumentNotValidException"));
    }

    static Stream<Arguments> invalidAdjustments() {
        return Stream.of(
                Arguments.of("rrn", null, "must not be blank"),
                Arguments.of("rrn", "1".repeat(11), "RRN number must contain exactly 12 digits"),
                Arguments.of("rrn", "1".repeat(13), "RRN number must contain exactly 12 digits"),
                Arguments.of("rrn", "1".repeat(11) + "X", "RRN number must contain exactly 12 digits"),
                Arguments.of("amount", null, "must not be null"),
                Arguments.of("amount", 0, "must be greater than 0"),
                Arguments.of("amount", -1, "must be greater than 0"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "400000000000000", "40000000000000000", "400000000000000X"})
    void rejectsInvalidPanInRollbackBody(String bodyPan) {
        String pathPan = cards.create();

        String formatRule = "PAN number must contain exactly 16 digits";

        gateway().pathParam("pan", pathPan).body(Map.of("amount", 1, "rrn", nextRrn(), "pan", bodyPan))
                .when().post(BY_PAN + "/rollback")
                .then().statusCode(400).body("error", equalTo("MethodArgumentNotValidException"))
                // Known defect: for empty PAN, only one of two violations is returned in unspecified order.
                .body("message", bodyPan.isEmpty()
                        ? oneOf(formatRule, "must not be blank") : equalTo(formatRule));
    }

    @Test
    void reportsUnknownReservationOnExistingCard() {
        String pan = cards.create();

        gateway().pathParam("pan", pan).body(Map.of("amount", 1, "rrn", nextRrn(), "pan", pan))
                .when().post(BY_PAN + "/rollback")
                .then().statusCode(404).body("error", equalTo("ReservationNotFoundException"))
                .body("message", notNullValue());
    }

    @ParameterizedTest
    @MethodSource("invalidCardFilters")
    void reportsInvalidCardFilters(String field, Object value, int status, String error, String message) {
        var api = status == 500 ? cardManagement() : gateway();

        api.queryParam(field, value)
                .when().get(CARDS)
                .then().statusCode(status).body("error", equalTo(error))
                .body("message", containsString(message)).body("serviceName", equalTo("card-management"));
    }

    static Stream<Arguments> invalidCardFilters() {
        String constraint = "ConstraintViolationException";
        Stream<Arguments> invalidValues = Stream.of(
                Arguments.of("limit", 0, 400, constraint, "limit"),
                Arguments.of("offset", -1, 400, constraint, "offset"),
                // Known defect: conversion to int turns this offset into Integer.MIN_VALUE.
                Arguments.of("offset", 2_147_483_648L, 500, "InvalidDataAccessApiUsageException",
                        "first-result value cannot be negative : -2147483648"),
                Arguments.of("bin", "40000", 400, constraint, "BIN number must contain exactly 6 digits"),
                Arguments.of("issuerId", "A".repeat(11), 400, constraint, "issuerId"),
                Arguments.of("issuerId", "iss001", 400, constraint, "issuerId"));
        // Known defect: type conversion errors are handled as internal errors.
        Stream<Arguments> invalidTypes = Map.of("status", "UNKNOWN", "startDate", "not-a-date",
                        "endDate", "not-a-date", "limit", "NaN", "offset", "NaN").entrySet().stream()
                .map(param -> Arguments.of(param.getKey(), param.getValue(), 500,
                        "MethodArgumentTypeMismatchException", "'" + param.getKey() + "'"));
        return Stream.concat(invalidValues, invalidTypes);
    }

    @Test
    void rejectsCardPageLimitAboveServiceMaximum() {
        gateway().queryParam("limit", 10_001)
                .when().get(CARDS)
                .then().statusCode(413).body("error", equalTo("TooLargeLimitException"))
                .body("message", containsString("10000"));
    }
}

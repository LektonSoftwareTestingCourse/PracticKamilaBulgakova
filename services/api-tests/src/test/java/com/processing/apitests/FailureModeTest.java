package com.processing.apitests;

import com.processing.apitests.support.ApiTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.processing.apitests.support.Api.AUTHORIZE;
import static com.processing.apitests.support.Api.CARDS;
import static com.processing.apitests.support.Api.LOG;
import static com.processing.apitests.support.Api.ROLLBACK;
import static com.processing.apitests.support.Api.SEARCH;
import static com.processing.apitests.support.Api.TRANSACTIONS;
import static com.processing.apitests.support.Api.authorization;
import static com.processing.apitests.support.Api.cardManagement;
import static com.processing.apitests.support.Api.gateway;
import static com.processing.apitests.support.Api.gatewayTransaction;
import static com.processing.apitests.support.Api.logger;
import static com.processing.apitests.support.Requests.BALANCE;
import static com.processing.apitests.support.Requests.TEST_BIN;
import static com.processing.apitests.support.Requests.card;
import static com.processing.apitests.support.Requests.logEntry;
import static com.processing.apitests.support.Requests.nextRrn;
import static com.processing.apitests.support.Requests.transaction;
import static com.processing.apitests.support.Requests.unusedPan;
import static com.processing.apitests.support.Requests.with;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

class FailureModeTest extends ApiTest {
    private static final String TERMINAL = "/api/simulator/terminal";
    private static final String MERCHANT = "/api/simulator/merchant";

    @Test
    @Tag("broker-outage")
    void rollsBackAndRemainsAbsentAfterRabbitMqRecovery() throws IOException {
        String pan = cards.create();
        Map<String, Object> request = transaction(pan);
        String stan = (String) request.get("stan");

        cardManagement().pathParam("pan", pan)
                .when().get(CARDS + "/{pan}")
                .then().statusCode(200).body("availableBalance", equalTo(BALANCE));

        gatewayTransaction().body(request)
                .when().post(TRANSACTIONS)
                // RouteService returns a system error after rolling back the approved reservation.
                .then().statusCode(200).body("responseCode", equalTo("96"))
                .body("status", equalTo("DECLINED")).body("declineReason", equalTo("System error"))
                .body("authCode", nullValue());

        await().atMost(ofSeconds(15)).pollInterval(ofMillis(500))
                .untilAsserted(() -> assertRolledBack(pan, stan));

        Path signals = Path.of(System.getProperty("api.test.brokerSignals", "target/broker"));
        Files.createDirectories(signals);
        Files.createFile(signals.resolve("restart-requested"));
        await().atMost(ofSeconds(540)).pollInterval(ofMillis(500))
                .until(() -> Files.exists(signals.resolve("broker-healthy")));

        String controlPan = cards.create();
        Map<String, Object> control = transaction(controlPan);
        String controlStan = (String) control.get("stan");
        gatewayTransaction().body(control)
                .when().post(TRANSACTIONS)
                .then().statusCode(200).body("responseCode", equalTo("00"));
        await().atMost(ofSeconds(20)).pollInterval(ofMillis(500)).untilAsserted(() ->
                gateway().queryParam("pan", controlPan)
                        .when().get(SEARCH)
                        .then().statusCode(200).body(byStan(controlStan), hasSize(1)));
        await().during(ofSeconds(5)).atMost(ofSeconds(15)).pollInterval(ofMillis(500))
                .untilAsserted(() -> assertRolledBack(pan, stan));
    }

    private void assertRolledBack(String pan, String stan) {
        gateway().queryParam("pan", pan)
                .when().get(SEARCH)
                .then().statusCode(200).body(byStan(stan), hasSize(0));
        cardManagement().pathParam("pan", pan)
                .when().get(CARDS + "/{pan}")
                .then().statusCode(200).body("availableBalance", equalTo(BALANCE));
    }

    private String byStan(String stan) {
        return "transactions.findAll { it.stan == '%s' }".formatted(stan);
    }

    @Test
    @Tag("authorization-down")
    void switchDeclinesWhenAuthorizationCannotBeReached() {
        String pan = cards.create();
        Map<String, Object> request = transaction(pan);

        gatewayTransaction().body(request)
                .when().post(TRANSACTIONS)
                // RouteService catches AuthorizationException after retries and returns a business decline.
                .then().statusCode(200).body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("05")).body("declineReason", equalTo("Authorization service unavailable"))
                .body("stan", equalTo(request.get("stan"))).body("authCode", nullValue());
        cardManagement().pathParam("pan", pan)
                .when().get(CARDS + "/{pan}")
                .then().statusCode(200).body("availableBalance", equalTo(BALANCE));
    }

    @Test
    @Tag("switch-down")
    void gatewayReportsSwitchConnectionRefusedAndRateLimit() {
        Map<String, Object> request = transaction(unusedPan(TEST_BIN));

        gatewayTransaction().body(request)
                .when().post(TRANSACTIONS)
                // DownstreamErrorFilter and docs/api-spec.md §3
                .then().statusCode(503).body("error", equalTo("SERVICE_UNAVAILABLE"))
                .body("serviceName", equalTo("switch")).body("message", notNullValue());
        gatewayTransaction().body(transaction(unusedPan(TEST_BIN)))
                .when().post(TRANSACTIONS)
                // TransactionRateLimitFilter; this test phase has a one-token bucket.
                .then().statusCode(429).header("Retry-After", equalTo("1"))
                .body("error", equalTo("RATE_LIMIT_EXCEEDED")).body("retryAfterMs", equalTo(1000));
        gateway()
                .when().get("/health")
                .then().statusCode(503).body("status", equalTo("degraded"))
                .body("services.switch", equalTo("unavailable"));
    }

    @Test
    @Tag("switch-dns")
    void documentsGatewayInternalErrorForUnresolvableSwitch() {
        gatewayTransaction().body(transaction(unusedPan(TEST_BIN)))
                .when().post(TRANSACTIONS)
                // Known defect: DownstreamExceptionUtils does not recognize UnknownHostException.
                .then().statusCode(500).body("error", equalTo("Internal Server Error"))
                .body("status", equalTo(500)).body("serviceName", nullValue());
    }

    @ParameterizedTest
    @Tag("openapi-faults")
    @ValueSource(strings = {"/switch-docs", "/logger-docs", "/terminal-docs", "/merchant-docs", "/cards-docs"})
    void gatewayReportsMalformedUpstreamOpenApiDocument(String path) {
        gateway()
                .when().get(path)
                // OpenApiGatewayFilterFactory maps an upstream JSON parse failure to 502.
                .then().statusCode(502).body("error", equalTo("Bad Gateway"))
                .body("message", equalTo("Error reading OpenApi json response"))
                .body("serviceName", equalTo("gateway"));
    }

    @Test
    @Tag("downstream-down")
    void terminalReports502WhenCardProviderReturnsHttpError() {
        gateway().body(Map.of("count", 1, "scenario", "normal", "tps", 1))
                .when().post("/api/simulator/terminal/run")
                .then().statusCode(502).body("error", equalTo("Gateway communication error"))
                .body("message", containsString("503")).body("serviceName", equalTo("terminal-simulator"));
    }

    @Test
    @Tag("downstream-down")
    void terminalReports503WhenCardProviderClosesConnection() {
        gateway().body(Map.of("count", 1, "scenario", "mixed", "tps", 1))
                .when().post("/api/simulator/terminal/run")
                .then().statusCode(503).body("error", equalTo("Resource access"))
                .body("message", notNullValue()).body("serviceName", equalTo("terminal-simulator"));
    }

    @ParameterizedTest
    @Tag("downstream-down")
    @CsvSource({"NORMAL,502,Gateway communication error,503", "BLOCKED,503,Resource access,I/O error"})
    void documentsContinuousStartBlockedAfterCardProviderError(String type, int status, String error, String message) {
        Map<String, Object> request = Map.of("tps", 1, "transactionType", type);

        try {
            gateway().body(request)
                    .when().post(TERMINAL + "/start-continuous")
                    .then().statusCode(status).body("error", equalTo(error))
                    .body("message", containsString(message))
                    .body("serviceName", equalTo("terminal-simulator"));
            gateway().body(request)
                    .when().post(TERMINAL + "/start-continuous")
                    // Known defect: startContinuous leaves its running flag set after the first failure.
                    .then().statusCode(422).body("error", equalTo("Illegal state"))
                    .body("message", containsString("already running"));
        } finally {
            gateway().when().post(TERMINAL + "/stop")
                    .then().statusCode(200).body(emptyOrNullString());
        }
    }

    @ParameterizedTest
    @Tag("terminal-errors")
    @CsvSource({"run,normal,500,Unexpected internal server error",
        "run,mixed,404,Http connection error",
        "start-continuous,NORMAL,500,Unexpected internal server error",
        "start-continuous,BLOCKED,404,Http connection error"})
    void terminalReportsUnexpectedAndClientErrorsFromCardProvider(
            String endpoint, String scenarioOrType, int status, String error) {
        Map<String, Object> request = endpoint.equals("run")
                ? Map.of("count", 1, "tps", 1, "scenario", scenarioOrType)
                : Map.of("tps", 1, "transactionType", scenarioOrType);

        try {
            gateway().body(request)
                    .when().post(TERMINAL + "/" + endpoint)
                    .then().statusCode(status).body("error", equalTo(error))
                    .body("message", notNullValue()).body("serviceName", equalTo("terminal-simulator"));
        } finally {
            if (endpoint.equals("start-continuous")) {
                gateway().when().post(TERMINAL + "/stop")
                        .then().statusCode(200).body(emptyOrNullString());
            }
        }
    }

    @Test
    @Tag("downstream-down")
    void merchantReports502WhenCardProviderReturnsHttpError() {
        gateway().body(Map.of("count", 1, "scenario", "grocery", "tps", 1))
                .when().post("/api/simulator/merchant/run")
                .then().statusCode(502).body("error", equalTo("External service error"))
                .body("message", containsString("Card fixture unavailable"))
                .body("serviceName", equalTo("card-management"));
    }

    @ParameterizedTest
    @Tag("downstream-down")
    @CsvSource({"2,500,Internal service error,Unexpected error",
        "3,404,Resource not found,Cards not found"})
    void merchantDistinguishesInvalidCardResponseFromNoCards(
            int count, int status, String error, String message) {
        gateway().body(Map.of("count", count, "scenario", "grocery", "tps", 1))
                .when().post(MERCHANT + "/run")
                .then().statusCode(status).body("error", equalTo(error))
                .body("message", containsString(message))
                .body("serviceName", equalTo("Merchant acquirer simulator"));
    }

    @Test
    @Tag("authorization-faults")
    void distinguishesReserveAndRollbackFailures() {
        // The fixture returns an active card, a reserve conflict and a rejected rollback for this PAN.
        String pan = unusedPan("999902");
        Map<String, Object> request = with(transaction(pan), "issuerId", "ISS001");

        authorization().body(request)
                .when().post(AUTHORIZE)
                .then().statusCode(503).body("responseCode", equalTo("96"))
                .body("declineReason", equalTo("RESERVATION_FAILED"));
        authorization().body(Map.of("pan", pan, "rrn", nextRrn(), "amount", 1))
                .when().post(ROLLBACK)
                .then().statusCode(503).body("responseCode", equalTo("96"))
                .body("declineReason", equalTo("ROLLBACK_FAILED"));
    }

    @Test
    @Tag("authorization-faults")
    void distinguishesInsufficientFundsAtReserveFromPreCheck() {
        Map<String, Object> request = with(transaction(unusedPan("999901")), "issuerId", "ISS001");

        authorization().body(request)
                .when().post(AUTHORIZE)
                .then().statusCode(422).body("responseCode", equalTo("51"))
                .body("declineReason", equalTo("INSUFFICIENT_FUNDS"));
    }

    @ParameterizedTest
    @Tag("authorization-faults")
    @CsvSource({"999904,404,14,CARD_NOT_FOUND", "999905,503,96,SERVICE_UNAVAILABLE",
        "999906,503,96,SERVICE_UNAVAILABLE"})
    void distinguishesMissingCardAndUnavailableServiceAtReserve(
            String bin, int status, String code, String reason) {
        Map<String, Object> request = with(transaction(unusedPan(bin)), "issuerId", "ISS001");

        authorization().body(request)
                .when().post(AUTHORIZE)
                .then().statusCode(status).body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo(code)).body("declineReason", equalTo(reason));
    }

    @Test
    @Tag("authorization-faults")
    void reportsUnknownReasonWhenProviderReturnsNoStatus() {
        Map<String, Object> request = with(transaction(unusedPan("999900")), "issuerId", "ISS001");

        authorization().body(request)
                .when().post(AUTHORIZE)
                .then().statusCode(400).body("responseCode", equalTo("05"))
                .body("declineReason", equalTo("UNKNOWN_REASON"));
    }

    @Test
    @Tag("card-down")
    void authorizationAndRollbackReportUnavailableCardService() {
        String pan = unusedPan("999903");
        Map<String, Object> request = with(transaction(pan), "issuerId", "ISSUER01");

        authorization().body(request)
                .when().post(AUTHORIZE)
                .then().statusCode(503).body("responseCode", equalTo("96"))
                .body("declineReason", equalTo("SERVICE_UNAVAILABLE"));
        authorization().body(Map.of("pan", pan, "rrn", nextRrn(), "amount", 1))
                .when().post(ROLLBACK)
                .then().statusCode(503).body("responseCode", equalTo("96"))
                .body("declineReason", equalTo("SERVICE_UNAVAILABLE"));
    }

    @Test
    @Tag("postgres-down")
    void documentsLoggerInternalErrorWhenDatabaseCannotOpenTransaction() {
        Map<String, Object> entry = logEntry(unusedPan(TEST_BIN));

        logger().body(entry)
                .when().post(LOG)
                // Known defect: the failure to open a transaction goes to the generic handler.
                .then().statusCode(500).body("error", equalTo("Internal server error"))
                .body("message", equalTo("Unexpected transaction logger error"))
                .body("serviceName", equalTo("transaction-logger"))
                .body("retryAfterMs", equalTo("0"));
    }

    @ParameterizedTest
    @Tag("postgres-down")
    @ValueSource(strings = {SEARCH, "/api/transactions/export", "/api/dashboard/stats",
        "/api/dashboard/recent", "/api/dashboard/charts"})
    void documentsLoggerReadErrorsWhenDatabaseCannotOpenTransaction(String path) {
        gateway()
                .when().get(path)
                .then().statusCode(500).body("error", equalTo("Internal server error"))
                .body("message", equalTo("Unexpected transaction logger error"))
                .body("serviceName", equalTo("transaction-logger"))
                .body("retryAfterMs", equalTo("0"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @Tag("card-down")
    @MethodSource("cardOperations")
    void gatewayReportsUnavailableCardService(String method, String path, Map<String, Object> body) {
        cardRequest(gateway(), body)
                .when().request(method, path)
                .then().statusCode(503).body("error", equalTo("SERVICE_UNAVAILABLE"))
                .body("serviceName", equalTo("cardManagement"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @Tag("postgres-down")
    @MethodSource("cardOperations")
    void cardServiceReportsDatabaseFailure(String method, String path, Map<String, Object> body) {
        cardRequest(cardManagement(), body)
                .when().request(method, path)
                .then().statusCode(500).body("error", notNullValue())
                .body("message", notNullValue()).body("serviceName", equalTo("card-management"));
    }

    static Stream<Arguments> cardOperations() {
        String pan = unusedPan(TEST_BIN);
        String byPan = CARDS + "/" + pan;
        return Stream.of(
                Arguments.of("GET", CARDS, null),
                Arguments.of("POST", CARDS, card()),
                Arguments.of("GET", byPan, null),
                Arguments.of("PATCH", byPan, Map.of("status", "BLOCKED")),
                Arguments.of("DELETE", byPan, null),
                Arguments.of("POST", byPan + "/reserve", Map.of("rrn", nextRrn(), "amount", 1)),
                Arguments.of("POST", byPan + "/rollback", Map.of("pan", pan, "rrn", nextRrn(), "amount", 1)),
                Arguments.of("POST", CARDS + "/generate", Map.of("count", 1, "bins", List.of(TEST_BIN))),
                Arguments.of("POST", CARDS + "/bulk-update",
                        Map.of("status", "BLOCKED", "pans", List.of(pan))));
    }

    private RequestSpecification cardRequest(RequestSpecification spec, Map<String, Object> body) {
        return body == null ? spec : spec.body(body);
    }

    @Test
    @Tag("postgres-down")
    void merchantFeeReportsDatabaseFailure() {
        Map<String, Object> request = transaction(unusedPan(TEST_BIN));
        request.keySet().retainAll(List.of("pan", "stan", "terminalId", "transmissionDateTime", "amount"));

        gateway().body(request)
                .when().post(MERCHANT + "/fee")
                .then().statusCode(500).body("error", equalTo("Internal service error"))
                .body("message", containsString("Unexpected error"))
                .body("serviceName", equalTo("Merchant acquirer simulator"));
    }

    @Test
    @Tag("postgres-down")
    void documentsAuthorizationInternalErrorWhenTransactionCannotStart() {
        Map<String, Object> request = with(transaction(unusedPan("999902")), "issuerId", "ISS001");

        authorization().body(request)
                .when().post(AUTHORIZE)
                // Known defect: the transaction fails before the service can return DB_UNAVAILABLE.
                .then().statusCode(500).body("error", equalTo("Internal Server Error"))
                .body("path", equalTo(AUTHORIZE)).body("status", equalTo(500))
                .body("responseCode", nullValue()).body("declineReason", nullValue());
    }
}

package com.processing.apitests;

import com.processing.apitests.support.ApiTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static com.processing.apitests.support.Api.SEARCH;
import static com.processing.apitests.support.Api.gateway;
import static com.processing.apitests.support.Requests.transaction;
import static com.processing.apitests.support.Requests.with;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SimulatorTest extends ApiTest {
    private static final String TERMINAL = "/api/simulator/terminal";
    private static final String MERCHANT = "/api/simulator/merchant";

    @ParameterizedTest
    @ValueSource(strings = {"normal", "mixed", "high_value", "night_time", "declines_test"})
    void runsTerminalScenarios(String scenario) {
        cards.create();
        if (List.of("mixed", "declines_test").contains(scenario)) {
            cards.patch(cards.create(), Map.of("status", "BLOCKED"));
        }
        Map<String, Object> request = Map.of("count", 1, "scenario", scenario, "tps", 1);

        gateway().body(request)
                .when().post(TERMINAL + "/run")
                .then().statusCode(200).body("totalSubmitted", equalTo(1))
                .body("transactions", hasSize(1)).body("transactions[0].stan", notNullValue())
                .body("elapsedMs", greaterThanOrEqualTo(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"grocery", "electronics", "restaurant", "travel"})
    void runsMerchantScenarios(String scenario) {
        cards.create();
        Map<String, Object> request = Map.of("count", 1, "scenario", scenario, "tps", 1);

        gateway().body(request)
                .when().post(MERCHANT + "/run")
                .then().statusCode(200).body("totalSubmitted", equalTo(1))
                .body("transactions", hasSize(1)).body("transactions[0].status", notNullValue())
                .body("elapsedMs", greaterThanOrEqualTo(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"count", "tps", "scenario"})
    void rejectsInvalidTerminalRunParameter(String field) {
        Object invalid = field.equals("scenario") ? "unexpected" : 0;
        Map<String, Object> request = with(Map.of("count", 1, "tps", 1, "scenario", "normal"), field, invalid);

        gateway().body(request)
                .when().post(TERMINAL + "/run")
                .then().statusCode(400).body("message", containsString(
                        field.equals("scenario") ? "unexpected" : field))
                .body("serviceName", equalTo("terminal-simulator"));
    }

    @Test
    void documentsTerminalRejectingExampleWithoutTps() {
        // docs/api-spec.md §4 gives this example without the required tps field.
        gateway().body(Map.of("count", 50, "scenario", "normal"))
                .when().post(TERMINAL + "/run")
                .then().statusCode(400).body("error", equalTo("Validation failed"))
                .body("message", containsString("tps"))
                .body("message", containsString("must be greater than or equal to 1"))
                .body("serviceName", equalTo("terminal-simulator"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"count", "tps", "scenario"})
    void rejectsInvalidMerchantRunParameter(String field) {
        Object invalid = field.equals("scenario") ? "unexpected" : 0;
        Map<String, Object> request = with(Map.of("count", 1, "tps", 1, "scenario", "grocery"), field, invalid);

        gateway().body(request)
                .when().post(MERCHANT + "/run")
                .then().statusCode(400).body("message", containsString(field.equals("scenario") ? "unexpected" : field))
                .body("serviceName", equalTo("Merchant acquirer simulator"));
    }

    @ParameterizedTest
    @ValueSource(strings = {TERMINAL, MERCHANT})
    void rejectsMissingSimulatorScenario(String simulator) {
        Map<String, Integer> request = Map.of("count", 1, "tps", 1);
        String service = simulator.equals(TERMINAL) ? "terminal-simulator" : "Merchant acquirer simulator";

        gateway().body(request)
                .when().post(simulator + "/run")
                .then().statusCode(400).body("message", containsString("scenario"))
                .body("serviceName", equalTo(service));
    }

    @Test
    void merchantUsesDefaultTpsWhenOmitted() {
        cards.create();

        gateway().body(Map.of("count", 1, "scenario", "grocery"))
                .when().post(MERCHANT + "/run")
                .then().statusCode(200).body("totalSubmitted", equalTo(1))
                .body("transactions", hasSize(1)).body("transactions[0].responseCode", notNullValue());
    }

    @Test
    void startsAndStopsContinuousSimulation() {
        cards.create();
        Map<String, Object> request = Map.of("tps", 1, "transactionType", "NORMAL");

        try {
            gateway().body(request)
                    .when().post(TERMINAL + "/start-continuous")
                    .then().statusCode(200).body(emptyOrNullString());
            gateway().body(request)
                    .when().post(TERMINAL + "/start-continuous")
                    .then().statusCode(422).body("error", equalTo("Illegal state"))
                    .body("message", containsString("already running"));
        } finally {
            gateway()
                    .when().post(TERMINAL + "/stop")
                    .then().statusCode(200).body(emptyOrNullString());
        }
    }

    @Test
    void documentsContinuousStartStuckUntilExplicitStop() {
        cards.create();
        Map<String, Object> request = Map.of("tps", 1, "transactionType", "BLOCKED");

        try {
            gateway().body(request)
                    .when().post(TERMINAL + "/start-continuous")
                    .then().statusCode(422).body("error", equalTo("Illegal state"))
                    .body("message", equalTo("No BLOCKED cards available"));

            cards.patch(cards.create(), Map.of("status", "BLOCKED"));
            gateway().body(request)
                    .when().post(TERMINAL + "/start-continuous")
                    // Known defect: an unsuccessful start leaves the running flag set.
                    .then().statusCode(422).body("error", equalTo("Illegal state"))
                    .body("message", containsString("already running"));

            gateway().when().post(TERMINAL + "/stop")
                    .then().statusCode(200).body(emptyOrNullString());
            gateway().body(request)
                    .when().post(TERMINAL + "/start-continuous")
                    .then().statusCode(200).body(emptyOrNullString());
        } finally {
            gateway().when().post(TERMINAL + "/stop")
                    .then().statusCode(200).body(emptyOrNullString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"tps", "transactionType"})
    void rejectsInvalidContinuousSettings(String field) {
        Object invalid = field.equals("tps") ? 0 : null;
        Map<String, Object> request = with(Map.of("tps", 1, "transactionType", "NORMAL"), field, invalid);

        gateway().body(request)
                .when().post(TERMINAL + "/start-continuous")
                .then().statusCode(400).body("message", containsString(field))
                .body("serviceName", equalTo("terminal-simulator"));
    }

    @Test
    void rejectsUnknownContinuousTransactionType() {
        gateway().body(Map.of("tps", 1, "transactionType", "UNSUPPORTED"))
                .when().post(TERMINAL + "/start-continuous")
                .then().statusCode(400).body("error", equalTo("Invalid request format"))
                .body("message", containsString("UNSUPPORTED")).body("serviceName", equalTo("terminal-simulator"));
    }

    @Test
    void reportsMissingBlockedCardForTerminalScenario() {
        cards.create();
        Map<String, Object> request = Map.of("count", 1, "scenario", "declines_test", "tps", 1);

        gateway().body(request)
                .when().post(TERMINAL + "/run")
                .then().statusCode(422).body("error", equalTo("Illegal state"))
                .body("message", equalTo("No BLOCKED cards available"))
                .body("serviceName", equalTo("terminal-simulator"));
    }

    @Test
    void reportsNoMerchantsForRequestedMcc() {
        cards.create();
        Map<String, Object> request = Map.of("count", 1, "scenario", "grocery",
                "tps", 1, "mccCodes", List.of("0000"));

        gateway().body(request)
                .when().post(MERCHANT + "/run")
                .then().statusCode(404).body("message", containsString("0000"))
                .body("serviceName", equalTo("Merchant acquirer simulator"));
    }

    @Test
    void documentsMerchantsPathWithoutGatewayRoute() {
        gateway()
                .when().get("/api/simulator/merchants")
                // SimulationController declares the path; Gateway application.yml uses singular merchant/**.
                .then().statusCode(404).body("path", equalTo("/api/simulator/merchants"));
    }

    @Test
    void documentsFeeLookupWithoutMatchingTransaction() {
        Map<String, Object> transaction = transaction(cards.create());
        Map<String, Object> fees = Map.of("pan", transaction.get("pan"), "stan", transaction.get("stan"),
                "transmissionDateTime", transaction.get("transmissionDateTime"),
                "terminalId", transaction.get("terminalId"), "amount", transaction.get("amount"));

        gateway().body(fees)
                .when().post(MERCHANT + "/fee")
                .then().statusCode(404).body("serviceName", equalTo("Merchant acquirer simulator"))
                .body("message", notNullValue());
    }

    @Test
    void retrievesFeeForMerchantSimulationTransaction() {
        String pan = cards.create();
        Map<String, Object> simulation = Map.of("count", 1, "scenario", "grocery", "tps", 1);

        String stan = gateway().body(simulation)
                .when().post(MERCHANT + "/run")
                .then().statusCode(200).body("transactions", hasSize(1)).extract().path("transactions[0].stan");
        String matching = "transactions.findAll { it.stan == '%s' }".formatted(stan);
        await().atMost(ofSeconds(15)).pollInterval(ofMillis(500)).untilAsserted(() ->
                gateway().queryParams(Map.of("pan", pan, "dateFrom",
                                LocalDate.now(ZoneOffset.UTC).minusDays(1).toString()))
                        .when().get(SEARCH)
                        .then().statusCode(200).body(matching, hasSize(1)));
        Map<String, Object> row = gateway().queryParam("pan", pan)
                .when().get(SEARCH)
                .then().statusCode(200).extract().path(matching + "[0]");
        Map<String, Object> request = Map.of("pan", pan, "stan", stan,
                "transmissionDateTime", row.get("transmissionDateTime"),
                "terminalId", row.get("terminalId"), "amount", row.get("amount"));

        Number fee = gateway().body(request)
                .when().post(MERCHANT + "/fee")
                .then().statusCode(200).body("acquirerFee", notNullValue()).extract().path("acquirerFee");
        assertEquals(((Number) row.get("acquiringFee")).doubleValue(), fee.doubleValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"pan", "stan", "terminalId", "transmissionDateTime"})
    void rejectsMissingFeeRequestField(String field) {
        Map<String, Object> request = transaction(cards.create());
        request.keySet().retainAll(List.of("pan", "stan", "terminalId", "transmissionDateTime", "amount"));
        request.remove(field);

        gateway().body(request)
                .when().post(MERCHANT + "/fee")
                .then().statusCode(400).body("message", containsString(field))
                .body("serviceName", equalTo("Merchant acquirer simulator"));
    }

    @ParameterizedTest
    @CsvSource({"pan,''", "stan,''", "terminalId,''", "transmissionDateTime,not-a-date", "amount,not-a-number"})
    void rejectsInvalidFeeFieldValue(String field, String value) {
        Map<String, Object> request = transaction(cards.create());
        request.keySet().retainAll(List.of("pan", "stan", "terminalId", "transmissionDateTime", "amount"));
        request.put(field, value);

        gateway().body(request)
                .when().post(MERCHANT + "/fee")
                .then().statusCode(400).body("error", equalTo("Invalid request"))
                .body("message", containsString(value.isEmpty() ? field + " : must not be blank" : value))
                .body("serviceName", equalTo("Merchant acquirer simulator"));
    }
}

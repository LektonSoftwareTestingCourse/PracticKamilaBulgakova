package com.processing.apitests;

import com.processing.apitests.support.ApiTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static com.processing.apitests.TransactionLogTest.store;
import static com.processing.apitests.support.Api.gateway;
import static com.processing.apitests.support.Api.logger;
import static com.processing.apitests.support.Requests.logEntry;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static io.restassured.path.json.config.JsonPathConfig.NumberReturnType.BIG_DECIMAL;
import static io.restassured.path.json.config.JsonPathConfig.jsonPathConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DashboardTest extends ApiTest {
    private static final String DASHBOARD = "/api/dashboard";

    @Test
    void includesStoredTransactionInStatsAndRecent() {
        Map<String, Object> entry = logEntry(cards.create());
        entry.put("createdAt", Instant.now().plus(3650, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS).toString());
        JsonPath before = stats();

        store(entry);
        JsonPath after = stats();
        String idFilter = "findAll { it.id == '%s' }".formatted(entry.get("id"));
        assertEquals(before.getLong("totalTransactions") + 1, after.getLong("totalTransactions"));
        assertEquals(before.getLong("approvedCount") + 1, after.getLong("approvedCount"));
        assertEquals(before.getLong("declinedCount"), after.getLong("declinedCount"));
        assertEquals(0, amount(after).compareTo(amount(before).add(new BigDecimal(entry.get("amount").toString()))));
        assertEquals((double) after.getLong("approvedCount") / after.getLong("totalTransactions"),
                after.getDouble("approvalRate"), 1e-9);
        gateway().queryParam("limit", 1)
                .when().get(DASHBOARD + "/recent")
                .then().statusCode(200).body("$", hasSize(1))
                .body("[0].id", equalTo(entry.get("id")));
        gateway().queryParam("limit", 500)
                .when().get(DASHBOARD + "/recent")
                .then().statusCode(200).body(idFilter, hasSize(1))
                .body(idFilter + "[0].status", equalTo("APPROVED"));
    }

    private JsonPath stats() {
        String response = gateway()
                .when().get(DASHBOARD + "/stats")
                .then().statusCode(200).body("totalAmount", notNullValue()).extract().asString();
        return new JsonPath(response).using(jsonPathConfig().numberReturnType(BIG_DECIMAL));
    }

    private BigDecimal amount(JsonPath stats) {
        return new BigDecimal(stats.get("totalAmount").toString());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 501})
    void rejectsRecentLimitOutsideBounds(int limit) {
        gateway().queryParam("limit", limit)
                .when().get(DASHBOARD + "/recent")
                .then().statusCode(400).body("message", containsString("limit"))
                .body("serviceName", equalTo("transaction-logger"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"hour", "day"})
    void returnsChartBucketsForStoredTransaction(String granularity) {
        Map<String, Object> entry = logEntry(cards.create());
        Instant time = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        entry.put("createdAt", time.toString());
        store(entry);
        Instant bucket = time.truncatedTo(granularity.equals("hour") ? ChronoUnit.HOURS : ChronoUnit.DAYS);

        String timestamp = gateway().queryParams(Map.of("granularity", granularity,
                        "from", time.toString(), "to", time.plusMillis(1).toString()))
                .when().get(DASHBOARD + "/charts")
                .then().statusCode(200).body("$", hasSize(1))
                .body("[0].total", equalTo(1)).body("[0].approved", equalTo(1))
                .body("[0].declined", equalTo(0)).body("[0].amount", equalTo(entry.get("amount")))
                .extract().path("[0].timestamp");
        assertEquals(bucket, Instant.parse(timestamp));
    }

    @Test
    void rejectsUnsupportedChartGranularity() {
        gateway().queryParam("granularity", "week")
                .when().get(DASHBOARD + "/charts")
                .then().statusCode(400).body("message", containsString("granularity must be hour or day"))
                .body("serviceName", equalTo("transaction-logger"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"from", "to"})
    void identifiesMalformedChartDate(String field) {
        gateway().queryParam(field, "not-a-date")
                .when().get(DASHBOARD + "/charts")
                .then().statusCode(400).body("error", equalTo("Validation error"))
                .body("message", containsString(field + ": Failed to convert"))
                .body("serviceName", equalTo("transaction-logger"));
    }

    @Test
    void documentsMissingRuleForReversedChartDates() {
        Map<String, String> range = Map.of("from", "2026-02-02T00:00:00Z", "to", "2026-02-01T00:00:00Z");
        gateway().queryParams(range)
                .when().get(DASHBOARD + "/charts")
                .then().statusCode(400).body("error", equalTo("Validation error"))
                // Known defect: the Logger handler drops validation errors on the whole date range.
                .body("message", equalTo(""))
                .body("serviceName", equalTo("transaction-logger"));
    }

    @Test
    void documentsInternalErrorForInvalidRecentLimitType() {
        logger().queryParam("limit", "NaN")
                .when().get(DASHBOARD + "/recent")
                // Known defect: query parameter conversion falls into the generic error handler.
                .then().statusCode(500).body("error", equalTo("Internal server error"))
                .body("message", equalTo("Unexpected transaction logger error"))
                .body("serviceName", equalTo("transaction-logger")).body("retryAfterMs", equalTo("0"));
    }
}

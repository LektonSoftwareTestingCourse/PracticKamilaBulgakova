package com.processing.apitests;

import com.processing.apitests.support.ApiTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.processing.apitests.TransactionLogTest.store;
import static com.processing.apitests.support.Api.SEARCH;
import static com.processing.apitests.support.Api.gateway;
import static com.processing.apitests.support.Requests.TEST_BIN;
import static com.processing.apitests.support.Requests.logEntry;
import static com.processing.apitests.support.Requests.uniqueMerchant;
import static com.processing.apitests.support.Requests.unusedPan;
import static com.processing.apitests.support.Requests.with;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TransactionSearchTest extends ApiTest {
    private static final int FIXTURE_WRITERS = 4;

    @Test
    void returnsEmptySearchForUnusedPan() {
        String pan = unusedPan(TEST_BIN);

        gateway().queryParam("pan", pan)
                .when().get(SEARCH)
                .then().statusCode(200).body("total", equalTo(0)).body("transactions", hasSize(0));
    }

    @Test
    void defaultsToFirstFiftyTransactions() {
        String pan = cards.create();
        Instant start = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        List<String> ids = storeVariants(Collections.nCopies(51, Map.of("pan", pan)), start, Duration.ofMillis(1));

        gateway().queryParam("pan", pan)
                .when().get(SEARCH)
                // TransactionFilter defaults to limit 50 and offset 0.
                .then().statusCode(200).body("total", equalTo(51))
                .body("transactions.id", equalTo(ids.reversed().subList(0, 50)));
        gateway().queryParam("pan", pan).queryParam("offset", 50)
                .when().get(SEARCH)
                .then().statusCode(200).body("total", equalTo(51))
                .body("transactions.id", equalTo(List.of(ids.getFirst())));
    }

    @Test
    void paginatesWithoutChangingFilteredTotalAndExportsMatchingRows() {
        String pan = cards.create();
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        Sample first = sample(pan, uniqueMerchant(), "ISSUER01", "5411", true, date.minusDays(1));
        Sample second = sample(pan, uniqueMerchant(), "ISSUER02", "5812", false, date);
        sample(unusedPan(TEST_BIN), uniqueMerchant(), "ISSUER01", "5411", true, date);

        gateway().queryParam("pan", pan)
                .when().get(SEARCH)
                .then().statusCode(200).body("total", equalTo(2))
                .body("transactions.id", equalTo(List.of(second.id(), first.id())));
        gateway().queryParams(Map.of("pan", pan, "limit", 1, "offset", 1))
                .when().get(SEARCH)
                .then().statusCode(200).body("total", equalTo(2))
                .body("transactions.id", equalTo(List.of(first.id())));
        gateway().queryParams(Map.of("pan", pan, "limit", 500, "offset", 2))
                .when().get(SEARCH)
                .then().statusCode(200).body("total", equalTo(2)).body("transactions", hasSize(0));
        String csv = gateway().queryParams(Map.of("pan", pan, "limit", 1, "offset", 2))
                .when().get("/api/transactions/export")
                .then().statusCode(200).header("Content-Type", containsString("text/csv"))
                .header("Content-Disposition", containsString("attachment"))
                .extract().asString();
        List<String> firstColumn = csv.lines().map(line -> line.split(",", 2)[0]).toList();
        assertEquals(List.of("id", second.id(), first.id()), firstColumn);
    }

    @ParameterizedTest
    @MethodSource("invalidFilters")
    void rejectsInvalidSearchAndExportParameters(String field, Object value, String message) {
        Map<String, Object> filters = Map.of(field, value, "pan", unusedPan(TEST_BIN));

        for (String path : List.of(SEARCH, "/api/transactions/export")) {
            gateway().queryParams(filters)
                    .when().get(path)
                    // TransactionFilter and Logger GlobalExceptionHandler.
                    .then().statusCode(400).body("error", equalTo("Validation error"))
                    .body("message", containsString(message))
                    .body("serviceName", equalTo("transaction-logger"));
        }
    }

    static Stream<Arguments> invalidFilters() {
        return Stream.of(
                Arguments.of("limit", 0, "limit: "),
                Arguments.of("limit", 501, "limit: limit must not exceed 500"),
                Arguments.of("offset", -1, "offset: "),
                Arguments.of("status", "UNKNOWN", "status: "),
                Arguments.of("dateFrom", "2026-02-30", "dateFrom: "),
                Arguments.of("dateTo", "yesterday", "dateTo: "));
    }

    @Test
    void documentsMissingRuleForReversedSearchDates() {
        Map<String, String> dates = Map.of("dateFrom", "2026-02-02", "dateTo", "2026-02-01");

        for (String path : List.of(SEARCH, "/api/transactions/export")) {
            gateway().queryParams(dates)
                    .when().get(path)
                    .then().statusCode(400).body("error", equalTo("Validation error"))
                    // Known defect: the field-only handler drops the rule for the date range.
                    .body("message", equalTo("")).body("serviceName", equalTo("transaction-logger"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "dateFrom", "dateTo", "merchantId", "issuerId", "mcc", "declineReason"})
    void appliesEachFilterToMatchingAndNonMatchingRows(String field) {
        String pan = cards.create();
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        Sample expected = sample(pan, uniqueMerchant(), "ISSUER01", "5411", false, date);
        sample(pan, uniqueMerchant(), "ISSUER02", "5812", true,
                field.equals("dateTo") ? date.plusDays(1) : date.minusDays(1));
        Object filter = switch (field) {
            case "status" -> "DECLINED";
            case "dateFrom", "dateTo" -> date.toString();
            case "merchantId" -> expected.merchant();
            case "issuerId" -> expected.issuer();
            case "mcc" -> expected.mcc();
            case "declineReason" -> "funds";
            default -> throw new IllegalArgumentException(field);
        };

        gateway().queryParams(Map.of("pan", pan, field, filter))
                .when().get(SEARCH)
                .then().statusCode(200).body("total", equalTo(1))
                .body("transactions.id", equalTo(List.of(expected.id())));
    }

    @ParameterizedTest(name = "Pairwise filter row {0}")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15})
    void combinesAllFilterAndPaginationPairs(int row) {
        String firstPan = cards.create();
        String secondPan = cards.create();
        String firstMerchant = uniqueMerchant();
        String secondMerchant = uniqueMerchant();
        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        String pan = level(row, 1) ? firstPan : secondPan;
        boolean filterStatus = level(row, 2);
        LocalDate from = level(row, 3) ? day.minusDays(1) : day;
        LocalDate to = level(row, 4) ? day : day.plusDays(1);
        String merchant = level(row, 5) ? firstMerchant : secondMerchant;
        String issuer = level(row, 6) ? "ISSUER01" : "ISSUER02";
        String mcc = level(row, 7) ? "5411" : "5812";
        boolean filterReason = level(row, 8);
        int limit = level(row, 9) ? 1 : 2;
        int offset = level(row, 10) ? 0 : 1;
        Map<String, Object> filters = new HashMap<>(Map.of("pan", pan,
                "dateFrom", from.toString(), "dateTo", to.toString(), "merchantId", merchant,
                "issuerId", issuer, "mcc", mcc, "limit", limit, "offset", offset));
        if (filterStatus) {
            filters.put("status", "DECLINED");
        }
        if (filterReason) {
            filters.put("declineReason", "funds");
        }

        Map<String, Object> fixture = Map.of("pan", pan, "status", "DECLINED",
                "transmissionDateTime", midnight(day), "merchantId", merchant, "issuerId", issuer,
                "mcc", mcc, "declineReason", "insufficient funds");
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        int matchingCount = 3;
        List<Map<String, Object>> variants = new ArrayList<>(Collections.nCopies(matchingCount, fixture));
        variants.addAll(List.of(
                with(fixture, "pan", pan.equals(firstPan) ? secondPan : firstPan),
                with(fixture, "transmissionDateTime", midnight(from.minusDays(1))),
                with(fixture, "transmissionDateTime", midnight(to.plusDays(1))),
                with(fixture, "merchantId", merchant.equals(firstMerchant) ? secondMerchant : firstMerchant),
                with(fixture, "issuerId", issuer.equals("ISSUER01") ? "ISSUER02" : "ISSUER01"),
                with(fixture, "mcc", mcc.equals("5411") ? "5812" : "5411")));
        int otherReasonIndex = variants.size();
        variants.add(with(fixture, "declineReason", "card blocked"));
        variants.add(with(fixture, "status", "APPROVED"));
        List<String> stored = storeVariants(variants, createdAt, Duration.ofSeconds(1));
        List<String> matching = new ArrayList<>(stored.subList(0, matchingCount));
        if (!filterReason) {
            matching.add(stored.get(otherReasonIndex));
            if (!filterStatus) {
                matching.add(stored.getLast());
            }
        }
        List<String> ids = matching.reversed().stream().skip(offset).limit(limit).toList();

        gateway().queryParams(filters)
                .when().get(SEARCH)
                .then().statusCode(200).body("total", equalTo(matching.size()))
                .body("transactions.id", equalTo(ids));
    }

    private List<String> storeVariants(List<Map<String, Object>> variants, Instant start, Duration step) {
        try (var writers = Executors.newFixedThreadPool(FIXTURE_WRITERS)) {
            List<CompletableFuture<String>> writes = IntStream.range(0, variants.size())
                    .mapToObj(index -> CompletableFuture.supplyAsync(
                            () -> storeVariant(variants.get(index), start.plus(step.multipliedBy(index))), writers))
                    .toList();
            return writes.stream().map(CompletableFuture::join).toList();
        }
    }

    private String storeVariant(Map<String, Object> fields, Instant createdAt) {
        Map<String, Object> entry = logEntry((String) fields.get("pan"));
        entry.putAll(fields);
        entry.put("createdAt", createdAt.toString());
        if ("APPROVED".equals(entry.get("status"))) {
            entry.remove("declineReason");
        } else {
            entry.remove("authCode");
        }
        store(entry);
        return (String) entry.get("id");
    }

    private String midnight(LocalDate day) {
        return day.atStartOfDay().toInstant(ZoneOffset.UTC).toString();
    }

    private static boolean level(int row, int column) {
        // Distinct, nonzero binary masks give all four value pairs for any two columns.
        return Integer.bitCount(row & column) % 2 == 0;
    }

    private Sample sample(String pan, String merchant, String issuer, String mcc, boolean approved, LocalDate day) {
        Map<String, Object> entry = logEntry(pan);
        String status = approved ? "APPROVED" : "DECLINED";
        String timestamp = day.atStartOfDay().toInstant(ZoneOffset.UTC).toString();
        entry.putAll(Map.of("merchantId", merchant, "issuerId", issuer, "mcc", mcc,
                "status", status, "transmissionDateTime", timestamp, "createdAt", timestamp));
        if (!approved) {
            entry.put("declineReason", "insufficient funds");
            entry.remove("authCode");
        }
        store(entry);
        return new Sample((String) entry.get("id"), pan, merchant, issuer, mcc, status, day);
    }

    private record Sample(String id, String pan, String merchant, String issuer, String mcc, String status,
                          LocalDate day) {
    }
}

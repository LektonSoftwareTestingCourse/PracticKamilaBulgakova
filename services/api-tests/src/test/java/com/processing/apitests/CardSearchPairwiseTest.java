package com.processing.apitests;

import com.processing.apitests.support.ApiTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static com.processing.apitests.support.Api.CARDS;
import static com.processing.apitests.support.Api.cardManagement;
import static com.processing.apitests.support.Api.gateway;
import static com.processing.apitests.support.Requests.TEST_BIN;
import static com.processing.apitests.support.Requests.card;
import static com.processing.apitests.support.Requests.with;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.isIn;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CardSearchPairwiseTest extends ApiTest {
    @ParameterizedTest(name = "Card search pairwise row {0}")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    void coversPairsOfCardSearchParameterValues(int row) {
        String status = firstLevel(row, 1) ? "ACTIVE" : "INACTIVE";
        boolean filterBin = firstLevel(row, 2);
        boolean filterIssuer = firstLevel(row, 3);
        String issuer = "ISS001"; // V5.2__init_bin_issuers_table.sql maps TEST_BIN to ISS001.
        CardSample before = createSample(TEST_BIN, status);
        CardSample first = createSample(TEST_BIN, status);
        CardSample second = createSample(TEST_BIN, status);
        CardSample third = createSample(TEST_BIN, status);
        CardSample otherBin = createSample("400001", status);
        CardSample otherStatus = createSample(TEST_BIN, status.equals("ACTIVE") ? "INACTIVE" : "ACTIVE");
        CardSample after = createSample(TEST_BIN, status);

        boolean narrowStart = firstLevel(row, 4);
        boolean narrowEnd = firstLevel(row, 5);
        Instant from = narrowStart ? first.createdAt() : before.createdAt();
        Instant to = narrowEnd ? otherStatus.createdAt() : after.createdAt();
        int limit = firstLevel(row, 6) ? 1 : 2;
        int offset = firstLevel(row, 7) ? 0 : 1;
        Map<String, Object> filters = new HashMap<>(
                Map.of("status", status, "startDate", from.toString(), "endDate", to.toString()));
        if (filterBin) {
            filters.put("bin", TEST_BIN);
        }
        if (filterIssuer) {
            filters.put("issuerId", issuer);
        }
        List<CardSample> expected = new ArrayList<>(List.of(first, second, third));
        if (!narrowStart) {
            expected.add(before);
        }
        if (!narrowEnd) {
            expected.add(after);
        }
        if (!filterBin && !filterIssuer) {
            expected.add(otherBin);
        }
        List<String> all = expected.stream().map(CardSample::pan).toList();
        gateway().queryParams(filters).queryParam("limit", 10_000).queryParam("offset", 0)
                .when().get(CARDS)
                .then().statusCode(200).body("total", equalTo(expected.size()))
                .body("cards.pan", containsInAnyOrder(all.toArray(String[]::new)));

        // Card Management does not guarantee order, so check membership, size and uniqueness.
        int pageSize = Math.min(limit, all.size() - offset);
        List<String> page = gateway().queryParams(filters).queryParam("limit", limit).queryParam("offset", offset)
                .when().get(CARDS)
                .then().statusCode(200).body("total", equalTo(expected.size()))
                .body("cards.pan", hasSize(pageSize)).body("cards.pan", everyItem(isIn(all)))
                .extract().jsonPath().getList("cards.pan");
        assertEquals(pageSize, new HashSet<>(page).size());
    }

    private CardSample createSample(String bin, String status) {
        String pan = cards.create(with(card(), "bin", bin));
        if (!status.equals("ACTIVE")) {
            cards.patch(pan, Map.of("status", status));
        }
        String createdAt = cardManagement().pathParam("pan", pan)
                .when().get(CARDS + "/{pan}")
                .then().statusCode(200).body("status", equalTo(status))
                .extract().path("createdAt");
        return new CardSample(pan, Instant.parse(createdAt));
    }

    private static boolean firstLevel(int row, int column) {
        // Distinct nonzero three-bit masks cover all four pairs of values in eight rows.
        return Integer.bitCount(row & column) % 2 == 0;
    }

    private record CardSample(String pan, Instant createdAt) {
    }
}

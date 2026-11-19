package com.processing.apitests;

import com.processing.apitests.support.ApiTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.processing.apitests.support.Api.AUTHORIZE;
import static com.processing.apitests.support.Api.authorization;
import static com.processing.apitests.support.Api.binLookup;
import static com.processing.apitests.support.Requests.card;
import static com.processing.apitests.support.Requests.transaction;
import static com.processing.apitests.support.Requests.with;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;

class BinLookupTest extends ApiTest {
    private static final String BIN = "/api/bin/{bin}";

    @ParameterizedTest
    @CsvSource({
        "400000,ISS001,Test Bank 1", "400001,ISS002,Test Bank 2",
        "400002,ISS003,Test Bank 3", "400003,ISS004,Test Bank 4", "400004,ISS005,Test Bank 5"
    })
    void resolvesRegisteredBin(String bin, String issuerId, String issuerName) {
        binLookup().pathParam("bin", bin)
                .when().get(BIN)
                // BinLookupController.BIN_TABLE
                .then().statusCode(200).body("bin", equalTo(bin))
                .body("issuerId", equalTo(issuerId)).body("issuerName", equalTo(issuerName));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1})
    void acceptsDelayAroundZero(int delay) {
        binLookup().pathParam("bin", "400000").queryParam("delay", delay).queryParam("fail", false)
                .when().get(BIN)
                .then().statusCode(200).body("issuerId", equalTo("ISS001"));
    }

    @ParameterizedTest
    @CsvSource({"delay,abc", "delay,2147483648", "fail,abc"})
    void rejectsInvalidQueryParameterTypes(String parameter, String value) {
        binLookup().pathParam("bin", "400000").queryParam(parameter, value)
                .when().get(BIN)
                .then().statusCode(400).body("error", equalTo("Bad Request"))
                .body("path", equalTo("/api/bin/400000"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"999999", "40000", "40000A"})
    void returnsNotFoundForBinOutsideLookupTable(String bin) {
        binLookup().pathParam("bin", bin)
                .when().get(BIN)
                .then().statusCode(404).body(emptyOrNullString());
    }

    @Test
    void returnsSimulatedFailureEvenForRegisteredBin() {
        binLookup().pathParam("bin", "400000").queryParam("fail", true)
                .when().get(BIN)
                .then().statusCode(500).body(emptyOrNullString());
    }

    @Test
    void authorizesExistingCardWhenBinLookupHasNoMatch() {
        String bin = cards.registerBin();
        String pan = cards.create(with(card(), "bin", bin));
        binLookup().pathParam("bin", bin)
                .when().get(BIN)
                .then().statusCode(404).body(emptyOrNullString());

        authorization().body(with(transaction(pan), "issuerId", "ISS" + bin))
                .when().post(AUTHORIZE)
                // BinLookupClient treats lookup errors as optional.
                .then().statusCode(200).body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"));
    }
}

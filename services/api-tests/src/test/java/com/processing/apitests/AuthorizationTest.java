package com.processing.apitests;

import com.processing.apitests.support.ApiTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static com.processing.apitests.support.Api.AUTHORIZE;
import static com.processing.apitests.support.Api.CARDS;
import static com.processing.apitests.support.Api.ROLLBACK;
import static com.processing.apitests.support.Api.ROUTE;
import static com.processing.apitests.support.Api.TRANSACTIONS;
import static com.processing.apitests.support.Api.authorization;
import static com.processing.apitests.support.Api.gateway;
import static com.processing.apitests.support.Api.gatewayTransaction;
import static com.processing.apitests.support.Api.routing;
import static com.processing.apitests.support.Requests.BALANCE;
import static com.processing.apitests.support.Requests.TEST_BIN;
import static com.processing.apitests.support.Requests.nextRrn;
import static com.processing.apitests.support.Requests.transaction;
import static com.processing.apitests.support.Requests.unusedPan;
import static com.processing.apitests.support.Requests.with;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

class AuthorizationTest extends ApiTest {
    @ParameterizedTest
    @CsvSource({
        "BLOCKED,05,CARD_BLOCKED",
        "INACTIVE,05,CARD_INACTIVE",
        "EXPIRED,54,CARD_EXPIRED"
    })
    void separatesDeclinesWithSameCode(String status, String code, String reason) {
        String pan = cards.create();
        String issuer = cards.issuer(pan);
        cards.patch(pan, Map.of("status", status));

        declines(gatewayTransaction(), TRANSACTIONS, transaction(pan), 200, code, reason);
        declines(authorization(), AUTHORIZE, with(transaction(pan), "issuerId", issuer), 403, code, reason);
    }

    @Test
    void declinesExpiredTransmissionDateWithActiveCard() {
        String pan = cards.create();
        Map<String, Object> request = with(transaction(pan), "transmissionDateTime", "2099-01-01T00:00:00Z");

        declines(gatewayTransaction(), TRANSACTIONS, request, 200, "54", "CARD_EXPIRED");
        declines(authorization(), AUTHORIZE, with(request, "issuerId", cards.issuer(pan)), 403, "54", "CARD_EXPIRED");
    }

    @Test
    void declinesMissingCardOnKnownBin() {
        String pan = unusedPan(TEST_BIN);
        String issuer = cards.issuer(cards.create());

        declines(gatewayTransaction(), TRANSACTIONS, transaction(pan), 200, "14", "CARD_NOT_FOUND");
        declines(authorization(), AUTHORIZE, with(transaction(pan), "issuerId", issuer), 404, "14", "CARD_NOT_FOUND");
    }

    @Test
    void declinesInsufficientFundsBeforeCheckingLimits() {
        String pan = cards.create();

        declines(gatewayTransaction(), TRANSACTIONS, with(transaction(pan), "amount", BALANCE + 1),
                200, "51", "INSUFFICIENT_FUNDS");
        declines(authorization(), AUTHORIZE,
                with(with(transaction(pan), "amount", BALANCE + 1), "issuerId", cards.issuer(pan)),
                422, "51", "INSUFFICIENT_FUNDS");
    }

    @Test
    void declinesOverDailyLimit() {
        String pan = cards.create();
        cards.patch(pan, Map.of("dailyLimit", 10_000));

        declines(gatewayTransaction(), TRANSACTIONS, with(transaction(pan), "amount", 10_001),
                200, "61", "EXCEEDS_AMOUNT_LIMIT");
        declines(authorization(), AUTHORIZE,
                with(with(transaction(pan), "amount", 10_001), "issuerId", cards.issuer(pan)),
                400, "61", "EXCEEDS_AMOUNT_LIMIT");
    }

    @Test
    void declinesOverMonthlyLimitOnAnotherDay() {
        String pan = cards.create();
        String issuer = cards.issuer(pan);
        cards.patch(pan, Map.of("dailyLimit", 10_000, "monthlyLimit", 10_000));
        Map<String, Object> firstDay = with(transaction(pan), "transmissionDateTime", "2025-06-01T12:00:00Z");
        Map<String, Object> secondDay = with(transaction(pan), "transmissionDateTime", "2025-06-02T12:00:00Z");

        authorization().body(with(firstDay, "issuerId", issuer))
                .when().post(AUTHORIZE)
                .then().statusCode(200).body("responseCode", equalTo("00"));
        declines(gatewayTransaction(), TRANSACTIONS, secondDay, 200, "61", "EXCEEDS_AMOUNT_LIMIT");
        declines(authorization(), AUTHORIZE, with(secondDay, "issuerId", issuer),
                400, "61", "EXCEEDS_AMOUNT_LIMIT");
    }

    @Test
    void declinesUnknownBinAtSwitch() {
        String pan = unusedPan("999999");

        declines(gatewayTransaction(), TRANSACTIONS, transaction(pan), 200, "14", "CARD_NOT_FOUND");
        declines(routing(), ROUTE, transaction(pan), 200, "14", "CARD_NOT_FOUND");
    }

    @Test
    void treatsDeletedCardAsNotFound() {
        String pan = cards.create();
        String issuer = cards.issuer(pan);
        gateway().pathParam("pan", pan)
                .when().delete(CARDS + "/{pan}")
                .then().statusCode(204);
        cards.forget(pan);

        declines(gatewayTransaction(), TRANSACTIONS, transaction(pan), 200, "14", "CARD_NOT_FOUND");
        declines(authorization(), AUTHORIZE, with(transaction(pan), "issuerId", issuer),
                404, "14", "CARD_NOT_FOUND");
    }

    @Test
    void routesDirectlyThroughSwitch() {
        Map<String, Object> request = transaction(cards.create());

        routing().body(request)
                .when().post(ROUTE)
                // RouteController and docs/api-spec.md §7
                .then().statusCode(200)
                .body("responseCode", equalTo("00"))
                .body("stan", equalTo(request.get("stan")))
                .body("rrn", matchesPattern("[0-9]{12}"));
    }

    @Test
    void authorizesAndRollsBackOnce() {
        String pan = cards.create();
        Map<String, Object> request = with(transaction(pan), "issuerId", cards.issuer(pan));

        String rrn = authorization().body(request)
                .when().post(AUTHORIZE)
                .then().statusCode(200).body("responseCode", equalTo("00"))
                .body("rrn", matchesPattern("[0-9]{12}")).extract().path("rrn");
        Map<String, Object> rollback = Map.of("pan", pan, "rrn", rrn, "amount", request.get("amount"));
        authorization().body(rollback)
                .when().post(ROLLBACK)
                .then().statusCode(200).body("rrn", equalTo(rrn))
                .body("responseCode", equalTo("00")).body("status", equalTo("APPROVED"));
        authorization().body(rollback)
                .when().post(ROLLBACK)
                .then().statusCode(409).body("responseCode", equalTo("05"))
                .body("declineReason", equalTo("ALREADY_ROLLED_BACK"));
    }

    @Test
    void reportsMissingRollbackTransaction() {
        Map<String, Object> request = Map.of("pan", cards.create(), "rrn", nextRrn(), "amount", 10_000);

        authorization().body(request)
                .when().post(ROLLBACK)
                .then().statusCode(404).body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("TRANSACTION_NOT_FOUND"));
    }

    private void declines(RequestSpecification api, String path, Map<String, Object> request,
                          int http, String code, String reason) {
        api.body(request)
                .when().post(path)
                // AuthControllerImpl, DeclineOutcome and Switch RouteController.
                .then().statusCode(http).body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo(code)).body("declineReason", equalTo(reason))
                .body("authCode", nullValue());
    }
}

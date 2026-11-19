package com.processing.apitests.support;

import io.restassured.response.Response;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static com.processing.apitests.support.Api.CARDS;
import static com.processing.apitests.support.Api.cardManagement;
import static com.processing.apitests.support.Api.gateway;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class CardFixtures {
    private static final String BIN_REGISTRATIONS = "/api/bins";
    private final Set<String> created = new LinkedHashSet<>();
    private final Set<String> registeredBins = new LinkedHashSet<>();

    public String registerBin() {
        String bin = Integer.toString(ThreadLocalRandom.current().nextInt(900_000, 990_000));
        Response response = cardManagement().body(Map.of("bin", bin, "issuerId", "ISS" + bin))
                .when().post(BIN_REGISTRATIONS);
        if (response.statusCode() == 201) {
            registeredBins.add(bin);
        }
        response.then().statusCode(201).body("bin", equalTo(bin));
        return bin;
    }

    public String create() {
        return create(Requests.card());
    }

    public String create(Map<String, Object> request) {
        Response response = gateway().body(request).when().post(CARDS);
        if (response.statusCode() == 201) {
            created.add(response.jsonPath().getString("pan"));
        }
        return response.then().statusCode(201)
                .body("pan", matchesPattern("[0-9]{16}"))
                .body("status", equalTo("ACTIVE"))
                .extract().path("pan");
    }

    public void patch(String pan, Map<String, ?> fields) {
        gateway().pathParam("pan", pan).body(fields)
                .when().patch(CARDS + "/{pan}")
                .then().statusCode(200).body("pan", equalTo(pan));
    }

    public String issuer(String pan) {
        return gateway().pathParam("pan", pan)
                .when().get(CARDS + "/{pan}")
                .then().statusCode(200).extract().path("issuerId");
    }

    public void remember(String pan) {
        created.add(pan);
    }

    public void forget(String pan) {
        created.remove(pan);
    }

    public void clean() {
        Set<String> failed = new LinkedHashSet<>();
        for (String pan : created) {
            int status = gateway().pathParam("pan", pan).when().delete(CARDS + "/{pan}").statusCode();
            if (status != 204 && status != 404) {
                failed.add(pan + " (" + status + ")");
            }
        }
        for (String bin : registeredBins) {
            int status = cardManagement().pathParam("bin", bin)
                    .when().delete(BIN_REGISTRATIONS + "/{bin}").statusCode();
            if (status != 204 && status != 404) {
                failed.add("BIN " + bin + " (" + status + ")");
            }
        }
        assertTrue(failed.isEmpty(), () -> "Card Management cleanup failed: " + failed);
    }
}

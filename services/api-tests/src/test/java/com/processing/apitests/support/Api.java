package com.processing.apitests.support;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;
import static io.restassured.config.HttpClientConfig.httpClientConfig;
import static io.restassured.config.LogConfig.logConfig;

public final class Api {
    public static final String CARDS = "/api/cards";
    public static final String TRANSACTIONS = "/api/transactions";
    public static final String SEARCH = TRANSACTIONS + "/search";
    public static final String LOG = "/api/internal/log";
    public static final String AUTHORIZE = "/api/internal/authorize";
    public static final String ROLLBACK = "/api/internal/rollback";
    public static final String ROUTE = "/api/internal/route";

    private static final RestAssuredConfig STANDARD_HTTP = httpConfig(45_000);
    // A transaction crosses Gateway, Switch and their HTTP and broker dependencies.
    private static final RestAssuredConfig TRANSACTION_HTTP = httpConfig(90_000);
    private static final RequestSpecification GATEWAY = specification("gateway", "http://gateway:8080");
    private static final RequestSpecification LOGGER =
            specification("logger", "http://transaction-logger:8080");
    private static final RequestSpecification AUTHORIZATION =
            specification("authorization", "http://authorization:8080");
    private static final RequestSpecification SWITCH = specification("switch", "http://switch:8080");
    private static final RequestSpecification CARD_MANAGEMENT =
            specification("card-management", "http://card-management:8080");
    private static final RequestSpecification BIN_LOOKUP = specification("bin-lookup", "http://bin-lookup:8080");

    private Api() {
    }

    public static RequestSpecification gateway() {
        return given().spec(GATEWAY);
    }

    public static RequestSpecification gatewayTransaction() {
        return gateway().config(TRANSACTION_HTTP);
    }

    public static RequestSpecification logger() {
        return given().spec(LOGGER);
    }

    public static RequestSpecification authorization() {
        return given().spec(AUTHORIZATION);
    }

    public static RequestSpecification routing() {
        return given().spec(SWITCH).config(TRANSACTION_HTTP);
    }

    public static RequestSpecification cardManagement() {
        return given().spec(CARD_MANAGEMENT);
    }

    public static RequestSpecification binLookup() {
        return given().spec(BIN_LOOKUP);
    }

    private static RequestSpecification specification(String service, String defaultUri) {
        return new RequestSpecBuilder()
                .setBaseUri(System.getProperty("smp." + service + ".url", defaultUri))
                .setContentType(ContentType.JSON)
                .setAccept("*/*")
                .setConfig(STANDARD_HTTP)
                .build();
    }

    private static RestAssuredConfig httpConfig(int readTimeoutMs) {
        return RestAssuredConfig.config()
                .httpClient(httpClientConfig().setParam("http.connection.timeout", 5_000)
                        .setParam("http.socket.timeout", readTimeoutMs))
                .logConfig(logConfig().enableLoggingOfRequestAndResponseIfValidationFails());
    }
}

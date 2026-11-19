package com.processing.apitests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.processing.apitests.support.Api.gateway;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

class GatewayHealthTest {
    @Test
    void returnsAggregatedHealth() {
        gateway()
                .when().get("/health")
                // HealthController and HealthService
                .then().statusCode(200).body("status", equalTo("ok"))
                .body("service", equalTo("gateway")).body("version", notNullValue())
                .body("services.switch", equalTo("ok"));
    }

    @ParameterizedTest
    @CsvSource({
        "/v3/api-docs,/health",
        "/switch-docs,/api/transactions",
        "/logger-docs,/api/transactions/search",
        "/terminal-docs,/api/simulator/terminal/run",
        "/merchant-docs,/api/simulator/merchant/run",
        "/cards-docs,/api/cards"
    })
    void exposesOpenApiRoutes(String document, String operation) {
        gateway()
                .when().get(document)
                // Gateway application.yml and its OpenApi filter
                .then().statusCode(200).body("openapi", matchesPattern("3\\.[0-9]+\\.[0-9]+"))
                .body("paths.'%s'".formatted(operation), notNullValue());
    }

    @Test
    void servesSwaggerUi() {
        gateway()
                .when().get("/docs")
                .then().statusCode(200).header("Content-Type", containsString("text/html"))
                .body(containsString("Swagger UI"));
    }

    @Test
    void describesTerminalPathsFromControllerInLiveOpenApi() {
        gateway()
                .when().get("/terminal-docs")
                .then().statusCode(200)
                .body("paths.'/api/simulator/terminal/start-continuous'.post", notNullValue())
                .body("paths.'/api/simulator/terminal/stop'.post", notNullValue())
                .body("paths.'/api/simulator/terminal/start'", nullValue())
                .body("paths.'/api/simulator/terminal/status'", nullValue());
    }

    @Test
    void exposesGatewayActuatorEndpoints() {
        gateway().when().get("/actuator/health")
                .then().statusCode(200).body("status", equalTo("UP"));
        gateway().when().get("/actuator/info")
                .then().statusCode(200).header("Content-Type", containsString("json"))
                .body(notNullValue());
        gateway().when().get("/actuator/metrics")
                .then().statusCode(200).body("names", notNullValue());
        gateway().when().get("/actuator/prometheus")
                .then().statusCode(200).body(containsString("jvm_memory"));
    }
}

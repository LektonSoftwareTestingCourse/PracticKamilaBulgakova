package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.config.SwitchProperties;
import com.processing.exception.AuthorizationException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorizationClientTest {
    @Mock private RestClient restClient;
    @Mock private RestClient.RequestBodyUriSpec post;
    @Mock private RestClient.RequestBodySpec body;
    @Mock private RestClient.ResponseSpec responseSpec;

    private CircuitBreaker circuitBreaker;
    private AuthorizationClient client;

    @BeforeEach
    void setUp() {
        SwitchProperties properties = new SwitchProperties("1", Map.of(), "http://authorization",
                "http://logger", "http://merchant", null, new SwitchProperties.RetryProperties(3, List.of()), null);
        Retry retry = Retry.of("authorization", RetryConfig.custom().maxAttempts(3).waitDuration(Duration.ZERO).build());
        circuitBreaker = CircuitBreaker.ofDefaults("authorization");
        client = new AuthorizationClient(properties, restClient, retry, circuitBreaker);
    }

    @ParameterizedTest(name = "status={0}")
    @ValueSource(strings = {"APPROVED", "DECLINED"})
    void shouldReturnAuthorizationResponseWhenStatusIsRecognized(String status) {
        // Arrange
        stubPost("/api/internal/authorize", request());
        AuthorizationResponse expected = response(status);
        when(responseSpec.body(AuthorizationResponse.class)).thenReturn(expected);

        // Act
        AuthorizationResponse result = client.authorize(request());

        // Assert
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void shouldRejectWhenAuthorizationBodyIsMissingOrHasUnknownStatus(AuthorizationResponse response) {
        // Arrange
        stubPost("/api/internal/authorize", request());
        when(responseSpec.body(AuthorizationResponse.class)).thenReturn(response);

        // Act / Assert
        assertThatThrownBy(() -> client.authorize(request())).isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Invalid response from Authorization");
    }

    @Test
    void shouldRejectWhenCircuitBreakerIsOpen() {
        // Arrange
        circuitBreaker.transitionToOpenState();

        // Act / Assert
        assertThatThrownBy(() -> client.authorize(request())).isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Circuit breaker open");
    }

    @Test
    void shouldRecoverWhenAuthorizationSucceedsOnRetry() {
        // Arrange
        stubPost("/api/internal/authorize", request());
        AuthorizationResponse expected = response("APPROVED");
        when(responseSpec.body(AuthorizationResponse.class))
                .thenThrow(new IllegalStateException("temporary failure")).thenReturn(expected);

        // Act
        AuthorizationResponse result = client.authorize(request());

        // Assert
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("unavailableFailures")
    void shouldReportUnavailableWhenAllAttemptsFail(RuntimeException failure) {
        // Arrange
        stubPost("/api/internal/authorize", request());
        when(responseSpec.body(AuthorizationResponse.class)).thenThrow(failure);

        // Act / Assert
        assertThatThrownBy(() -> client.authorize(request())).isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("after 3 attempts").hasMessageContaining("offline");
    }

    @ParameterizedTest(name = "rollback status={0}")
    @ValueSource(strings = {"APPROVED", "DECLINED"})
    void shouldReturnRollbackResponseWhenStatusIsRecognized(String status) {
        // Arrange
        RollbackRequest outgoing = new RollbackRequest("900000000012",
                request().pan(), request().amount());
        stubPost("/api/internal/rollback", outgoing);
        RollbackResponse expected = new RollbackResponse("900000000012", "00", status, null, 0);
        when(responseSpec.body(RollbackResponse.class)).thenReturn(expected);

        // Act
        RollbackResponse result = client.rollback(request(), "900000000012");

        // Assert
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("invalidRollbackResponses")
    void shouldReturnNoRollbackResponseWhenBodyIsMissingOrInvalid(RollbackResponse response) {
        // Arrange
        RollbackRequest outgoing = new RollbackRequest("900000000012",
                request().pan(), request().amount());
        stubPost("/api/internal/rollback", outgoing);
        when(responseSpec.body(RollbackResponse.class)).thenReturn(response);

        // Act
        RollbackResponse result = client.rollback(request(), "900000000012");

        // Assert
        assertThat(result).isNull();
    }

    private void stubPost(String path, Object requestBody) {
        when(restClient.post()).thenReturn(post);
        when(post.uri("http://authorization" + path)).thenReturn(body);
        when(body.body(requestBody)).thenReturn(body);
        when(body.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);
    }

    private static Stream<AuthorizationResponse> invalidResponses() {
        return Stream.of(null, response("UNKNOWN"));
    }

    private static Stream<RollbackResponse> invalidRollbackResponses() {
        return Stream.of(null, new RollbackResponse("900000000012", "00", "UNKNOWN", null, 0));
    }

    private static Stream<RuntimeException> unavailableFailures() {
        return Stream.of(new IllegalStateException("offline"),
                new IllegalStateException("wrapped", new IllegalArgumentException("offline")));
    }

    private static AuthorizationResponse response(String status) {
        return new AuthorizationResponse("0110", "000001", "900000000012", "ABC123", "00", status, null, 0);
    }

    private AuthorizationRequest request() {
        return new AuthorizationRequest("0100", "000001", "4000001234567890", "000000", new BigDecimal("100"), "643",
                Instant.parse("2026-10-15T10:00:00Z"), "TERM0001", "POS", "MERCH0000000001", "5411", "ACQ001", "ISS001");
    }
}

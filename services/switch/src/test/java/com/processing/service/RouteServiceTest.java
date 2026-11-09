package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import com.processing.exception.AuthorizationException;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteServiceTest {
    private static final String PAN = "4000001234567890";
    private static final String RRN = "900000000012";

    @Mock private RoutingService routingService;
    @Mock private AuthorizationClient authorizationClient;
    @Mock private AcquiringFeeClient acquiringFeeClient;
    @Mock private LoggerClient loggerClient;
    private RouteService service;

    @BeforeEach
    void setUp() {
        service = new RouteService(routingService, authorizationClient, acquiringFeeClient, loggerClient);
    }

    @Test
    void shouldPublishEnrichedTransactionWhenAuthorizationApproves() {
        // Arrange
        AuthorizationRequest request = request(PAN);
        AuthorizationResponse approved = approvedResponse();
        when(routingService.getIssuerIdByPan(PAN)).thenReturn("ISS001");
        when(authorizationClient.authorize(request.withIssuerId("ISS001"))).thenReturn(approved);
        BigDecimal fee = new BigDecimal("2");
        when(acquiringFeeClient.fetchAcquiringFee(request.transmissionDateTime(), request.stan(), PAN,
                request.terminalId(), request.amount())).thenReturn(fee);
        when(loggerClient.log(any())).thenReturn(true);
        ArgumentCaptor<TransactionRequest> published = ArgumentCaptor.forClass(TransactionRequest.class);

        // Act
        AuthorizationResponse result = service.route(request);

        // Assert
        assertThat(result).isEqualTo(approved);
        verify(loggerClient).log(published.capture());
        TransactionRequest transaction = published.getValue();
        assertThat(transaction.pan()).isEqualTo(PAN);
        assertThat(transaction.stan()).isEqualTo(request.stan());
        assertThat(transaction.mti()).isEqualTo("0100");
        assertThat(transaction.processingCode()).isEqualTo(request.processingCode());
        assertThat(transaction.currencyCode()).isEqualTo(request.currencyCode());
        assertThat(transaction.terminalId()).isEqualTo(request.terminalId());
        assertThat(transaction.terminalType()).isEqualTo(request.terminalType());
        assertThat(transaction.merchantId()).isEqualTo(request.merchantId());
        assertThat(transaction.mcc()).isEqualTo(request.mcc());
        assertThat(transaction.acquirerId()).isEqualTo(request.acquirerId());
        assertThat(transaction.issuerId()).isEqualTo("ISS001");
        assertThat(transaction.rrn()).isEqualTo(RRN);
        assertThat(transaction.amount()).isEqualByComparingTo("100");
        assertThat(transaction.acquiringFee()).isEqualByComparingTo(fee);
        assertThat(transaction.status()).isEqualTo(TransactionStatus.APPROVED);
        assertThat(transaction.authCode()).isEqualTo(approved.authCode());
        assertThat(transaction.declineReason()).isNull();
        assertThat(transaction.processingTimeMs()).isZero();
        assertThat(transaction.transmissionDateTime()).isEqualTo(request.transmissionDateTime());
        assertThat(transaction.id()).isNotNull();
        assertThat(transaction.createdAt()).isNotNull();
        verify(authorizationClient, never()).rollback(any(), any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"40000", "9999991234567890"})
    void shouldDeclineWithCode14WhenBinCannotBeResolved(String pan) {
        // Arrange
        when(routingService.getIssuerIdByPan(pan)).thenThrow(new UnknownBinException("unknown"));

        // Act
        AuthorizationResponse result = service.route(request(pan));

        // Assert
        assertThat(result.status()).isEqualTo("DECLINED");
        assertThat(result.responseCode()).isEqualTo("14");
        verify(authorizationClient, never()).rollback(any(), any());
    }

    @Test
    void shouldDeclineWithCode05WhenAuthorizationIsUnavailable() {
        // Arrange
        when(routingService.getIssuerIdByPan(PAN)).thenReturn("ISS001");
        when(authorizationClient.authorize(any())).thenThrow(new AuthorizationException("000001", 3, "offline"));

        // Act
        AuthorizationResponse result = service.route(request(PAN));

        // Assert
        assertThat(result.status()).isEqualTo("DECLINED");
        assertThat(result.responseCode()).isEqualTo("05");
    }

    @ParameterizedTest(name = "rollback response={0}")
    @MethodSource("rollbackResponses")
    void shouldRollbackAndReturnCode96WhenPublicationOfApprovedPaymentFails(RollbackResponse rollback) {
        // Arrange
        AuthorizationRequest request = request(PAN);
        when(routingService.getIssuerIdByPan(PAN)).thenReturn("ISS001");
        when(authorizationClient.authorize(any())).thenReturn(approvedResponse());
        when(authorizationClient.rollback(request.withIssuerId("ISS001"), RRN)).thenReturn(rollback);

        // Act
        AuthorizationResponse result = service.route(request);

        // Assert
        assertThat(result.status()).isEqualTo("DECLINED");
        assertThat(result.responseCode()).isEqualTo("96");
        verify(authorizationClient).rollback(request.withIssuerId("ISS001"), RRN);
        verify(loggerClient).log(any());
    }

    @Test
    void shouldPreserveDeclineWithoutRollbackWhenPublicationFails() {
        // Arrange
        when(routingService.getIssuerIdByPan(PAN)).thenReturn("ISS001");
        AuthorizationResponse declined = declinedResponse();
        when(authorizationClient.authorize(any())).thenReturn(declined);
        ArgumentCaptor<TransactionRequest> published = ArgumentCaptor.forClass(TransactionRequest.class);

        // Act
        AuthorizationResponse result = service.route(request(PAN));

        // Assert
        assertThat(result).isEqualTo(declined);
        verify(loggerClient).log(published.capture());
        assertThat(published.getValue().status()).isEqualTo(TransactionStatus.DECLINED);
        assertThat(published.getValue().declineReason()).isEqualTo("CARD_BLOCKED");
        assertThat(published.getValue().authCode()).isNull();
        verify(authorizationClient, never()).rollback(any(), any());
    }

    private static Stream<RollbackResponse> rollbackResponses() {
        return Stream.of(new RollbackResponse(RRN, "00", "APPROVED", null, 0),
                new RollbackResponse(RRN, "05", "DECLINED", "failed", 0), null);
    }

    private AuthorizationResponse approvedResponse() {
        return new AuthorizationResponse("0110", "000001", RRN, "ABC123", "00", "APPROVED", null, null);
    }

    private AuthorizationResponse declinedResponse() {
        return new AuthorizationResponse("0110", "000001", null, null, "05", "DECLINED", "CARD_BLOCKED", 0);
    }

    private AuthorizationRequest request(String pan) {
        return new AuthorizationRequest("0100", "000001", pan, "000000", new BigDecimal("100"), "643",
                Instant.parse("2026-10-15T10:00:00Z"), "TERM0001", "POS", "MERCH0000000001", "5411", "ACQ001", null);
    }
}

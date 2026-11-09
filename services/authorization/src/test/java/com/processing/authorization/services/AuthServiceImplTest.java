package com.processing.authorization.services;

import com.processing.authorization.client.BinLookupClient;
import com.processing.authorization.client.CardManagementClient;
import com.processing.authorization.events.AuthServiceAuthorizeEvent;
import com.processing.authorization.events.AuthServiceRollbackEvent;
import com.processing.authorization.events.AuthorizationEventNotifier;
import com.processing.authorization.exceptions.CardNotFoundException;
import com.processing.authorization.exceptions.InsufficientFundsException;
import com.processing.authorization.exceptions.ReserveException;
import com.processing.authorization.exceptions.RollbackConflictException;
import com.processing.authorization.exceptions.RollbackFailureException;
import com.processing.authorization.exceptions.ServiceUnavailableException;
import com.processing.authorization.repositories.LimitUsageRepository;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.cardmanagement.CardModel;
import com.processing.common.dto.cardmanagement.CardModelStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {
    private static final String PAN = "4000001234567890";
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("1000");
    private static final BigDecimal DAILY_LIMIT = new BigDecimal("50000");
    private static final BigDecimal MONTHLY_LIMIT = new BigDecimal("200000");
    private static final Instant PAYMENT_TIME = Instant.parse("2026-10-15T10:00:00Z");

    @Mock private LimitUsageRepository limitUsageRepository;
    @Mock private AuthorizationEventNotifier eventNotifier;
    @Mock private BinLookupClient binLookupClient;
    @Mock private CardManagementClient cardManagementClient;

    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(limitUsageRepository, eventNotifier, binLookupClient, cardManagementClient);
    }

    @ParameterizedTest(name = "amount={0}, balance=1000")
    @ValueSource(strings = {"100", "999", "1000"})
    void shouldApproveAndReserveWhenCardIsActiveAndAmountDoesNotExceedBalance(String amount) {
        // Arrange
        CardModel card = stubCard(CardModelStatus.ACTIVE, YearMonth.of(2027, 12));
        stubLimits(request(amount), card, 1);

        // Act
        AuthorizationResponse response = service.authorize(request(amount), Instant.now());

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.responseCode()).isEqualTo("00");
        assertThat(response.rrn()).matches("[0-9]{12}");
        assertThat(response.authCode()).matches("[A-Z0-9]{6}");
        verify(cardManagementClient).reserve(new BigDecimal(amount), response.rrn(), PAN);
        verify(eventNotifier).notify(new AuthServiceAuthorizeEvent(PAN));
    }

    @ParameterizedTest(name = "status={0}, reason={1}")
    @CsvSource({"INACTIVE,CARD_INACTIVE", "BLOCKED,CARD_BLOCKED", "EXPIRED,CARD_EXPIRED"})
    void shouldDeclineWithoutReservationWhenCardIsNotActive(CardModelStatus status, String reason) {
        // Arrange
        stubCard(status, YearMonth.of(2027, 12));

        // Act
        AuthorizationResponse response = service.authorize(request("100"), Instant.now());

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo(reason);
        verify(cardManagementClient, never()).reserve(any(), any(), any());
    }

    @Test
    void shouldDeclineWithoutReservingWhenCardStatusIsMissing() {
        // Arrange
        stubCard(null, YearMonth.of(2027, 12));

        // Act
        AuthorizationResponse response = service.authorize(request("100"), Instant.now());

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo("UNKNOWN_REASON");
        assertThat(response.responseCode()).isEqualTo("05");
        verify(cardManagementClient, never()).reserve(any(), any(), any());
        verify(eventNotifier, never()).notify(any(AuthServiceAuthorizeEvent.class));
    }

    @Test
    void shouldDeclineWhenCardDoesNotExist() {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenThrow(new CardNotFoundException(PAN));

        // Act
        AuthorizationResponse response = service.authorize(request("100"), Instant.now());

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo("CARD_NOT_FOUND");
        assertThat(response.responseCode()).isEqualTo("14");
        verify(cardManagementClient, never()).reserve(any(), any(), any());
    }

    @Test
    void shouldDeclineWhenCardManagementIsUnavailable() {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenThrow(new ServiceUnavailableException("unavailable"));

        // Act
        AuthorizationResponse response = service.authorize(request("100"), Instant.now());

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(response.responseCode()).isEqualTo("96");
    }

    @Test
    void shouldDeclineWhenExpiryMonthPrecedesPaymentMonth() {
        // Arrange
        stubCard(CardModelStatus.ACTIVE, YearMonth.of(2026, 9));

        // Act
        AuthorizationResponse response = service.authorize(request("100"), Instant.now());

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo("CARD_EXPIRED");
        verify(cardManagementClient, never()).reserve(any(), any(), any());
    }

    @ParameterizedTest(name = "expiry={0}")
    @ValueSource(strings = {"2026-10", "2026-11"})
    void shouldApproveWhenExpiryMonthIsCurrentOrFuture(YearMonth expiry) {
        // Arrange
        CardModel card = stubCard(CardModelStatus.ACTIVE, expiry);
        stubLimits(request("100"), card, 1);

        // Act
        AuthorizationResponse response = service.authorize(request("100"), Instant.now());

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
    }

    @Test
    void shouldDeclineWhenAmountExceedsBalanceByOneKopeck() {
        // Arrange
        stubCard(CardModelStatus.ACTIVE, YearMonth.of(2027, 12));

        // Act
        AuthorizationResponse response = service.authorize(request("1001"), Instant.now());

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        verify(cardManagementClient, never()).reserve(any(), any(), any());
    }

    @Test
    void shouldDeclineWhenLimitUpdateAffectsNoRows() {
        // Arrange
        CardModel card = stubCard(CardModelStatus.ACTIVE, YearMonth.of(2027, 12));
        stubLimits(request("100"), card, 0);

        // Act
        AuthorizationResponse response = service.authorize(request("100"), Instant.now());

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("61");
    }

    @Test
    void shouldApproveWhenPaymentIsOnLastDayOfExpiryMonth() {
        // Arrange
        CardModel card = stubCard(CardModelStatus.ACTIVE, YearMonth.of(2026, 10));
        AuthorizationRequest request = request("100", Instant.parse("2026-10-31T23:59:59Z"));
        stubLimits(request, card, 1);

        // Act
        AuthorizationResponse response = service.authorize(request, Instant.now());

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        verify(cardManagementClient).reserve(request.amount(), response.rrn(), PAN);
    }

    @Test
    void shouldDeclineWhenPaymentIsOnFirstDayAfterExpiryMonth() {
        // Arrange
        stubCard(CardModelStatus.ACTIVE, YearMonth.of(2026, 10));
        AuthorizationRequest request = request("100", Instant.parse("2026-11-01T00:00:00Z"));

        // Act
        AuthorizationResponse response = service.authorize(request, Instant.now());

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo("CARD_EXPIRED");
        assertThat(response.responseCode()).isEqualTo("54");
        verify(cardManagementClient, never()).reserve(any(), any(), any());
    }

    @Test
    void shouldDeclineWithoutReservingWhenLimitRepositoryIsUnavailable() {
        // Arrange
        CardModel card = stubCard(CardModelStatus.ACTIVE, YearMonth.of(2027, 12));
        AuthorizationRequest request = request("100");
        when(limitUsageRepository.upsertLimitUsage(PAN, PAYMENT_TIME.atZone(ZoneOffset.UTC).toLocalDate(),
                request.amount(), card.dailyLimit(), card.monthlyLimit()))
                .thenThrow(new DataAccessResourceFailureException("offline"));

        // Act
        AuthorizationResponse response = service.authorize(request, Instant.now());

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo("DB_UNAVAILABLE");
        assertThat(response.responseCode()).isEqualTo("96");
        verify(cardManagementClient, never()).reserve(any(), any(), any());
        verify(eventNotifier, never()).notify(any(AuthServiceAuthorizeEvent.class));
    }

    @Test
    void shouldApproveWhenLimitUpdateSucceedsAfterDuplicateKey() {
        // Arrange
        CardModel card = stubCard(CardModelStatus.ACTIVE, YearMonth.of(2027, 12));
        AuthorizationRequest request = request("100");
        when(limitUsageRepository.upsertLimitUsage(PAN, PAYMENT_TIME.atZone(ZoneOffset.UTC).toLocalDate(),
                request.amount(), card.dailyLimit(), card.monthlyLimit()))
                .thenThrow(new DuplicateKeyException("concurrent insert")).thenReturn(1);

        // Act
        AuthorizationResponse response = service.authorize(request, Instant.now());

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        verify(cardManagementClient).reserve(request.amount(), response.rrn(), PAN);
        verify(eventNotifier).notify(new AuthServiceAuthorizeEvent(PAN));
    }

    @ParameterizedTest(name = "reserve failure={1}")
    @MethodSource("reservationFailures")
    void shouldDeclineWhenReservationFails(RuntimeException failure, String reason, String code) {
        // Arrange
        CardModel card = stubCard(CardModelStatus.ACTIVE, YearMonth.of(2027, 12));
        AuthorizationRequest request = request("100");
        stubLimits(request, card, 1);
        doThrow(failure).when(cardManagementClient).reserve(eq(request.amount()), anyString(), eq(PAN));

        // Act
        AuthorizationResponse response = service.authorize(request, Instant.now());

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo(reason);
        assertThat(response.responseCode()).isEqualTo(code);
        verify(cardManagementClient).reserve(eq(request.amount()), anyString(), eq(PAN));
        verify(eventNotifier, never()).notify(any(AuthServiceAuthorizeEvent.class));
    }

    @Test
    void shouldApproveRollbackWhenCardManagementAcceptsIt() {
        // Arrange
        RollbackRequest request = rollbackRequest();

        // Act
        RollbackResponse response = service.rollback(request, Instant.now());

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.responseCode()).isEqualTo("00");
        assertThat(response.rrn()).isEqualTo(request.rrn());
        assertThat(response.declineReason()).isNull();
        verify(cardManagementClient).rollback(request);
        verify(eventNotifier).notify(new AuthServiceRollbackEvent(PAN));
    }

    @ParameterizedTest(name = "rollback failure={1}")
    @MethodSource("rollbackFailures")
    void shouldDeclineRollbackWhenCardManagementRejectsIt(RuntimeException failure, String reason, String code) {
        // Arrange
        RollbackRequest request = rollbackRequest();
        doThrow(failure).when(cardManagementClient).rollback(request);

        // Act
        RollbackResponse response = service.rollback(request, Instant.now());

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo(code);
        assertThat(response.declineReason()).isEqualTo(reason);
        assertThat(response.rrn()).isEqualTo(request.rrn());
        verify(cardManagementClient).rollback(request);
        verify(eventNotifier, never()).notify(any(AuthServiceRollbackEvent.class));
    }

    private static Stream<Arguments> reservationFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException(PAN), "CARD_NOT_FOUND", "14"),
                Arguments.of(new InsufficientFundsException("balance changed"), "INSUFFICIENT_FUNDS", "51"),
                Arguments.of(new ServiceUnavailableException("offline"), "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new ReserveException("reservation rejected"), "RESERVATION_FAILED", "96"));
    }

    private static Stream<Arguments> rollbackFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException(PAN), "TRANSACTION_NOT_FOUND", "14"),
                Arguments.of(new ServiceUnavailableException("offline"), "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new RollbackConflictException("duplicate"), "ALREADY_ROLLED_BACK", "05"),
                Arguments.of(new RollbackFailureException("failed"), "ROLLBACK_FAILED", "96"),
                Arguments.of(new IllegalStateException("unexpected failure"), "UNKNOWN_REASON", "05"));
    }

    private RollbackRequest rollbackRequest() {
        return new RollbackRequest("900000000012", PAN, new BigDecimal("100"));
    }

    private void stubLimits(AuthorizationRequest request, CardModel card, int updatedRows) {
        when(limitUsageRepository.upsertLimitUsage(PAN, request.transmissionDateTime().atZone(ZoneOffset.UTC).toLocalDate(),
                request.amount(), card.dailyLimit(), card.monthlyLimit())).thenReturn(updatedRows);
    }

    private CardModel stubCard(CardModelStatus status, YearMonth expiry) {
        CardModel card = new CardModel(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                PAN, "400000", "KAMILA BULGAKOVA", expiry, status, "643",
                DAILY_LIMIT, MONTHLY_LIMIT, INITIAL_BALANCE, "ISS001", PAYMENT_TIME);
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.empty());
        return card;
    }

    private AuthorizationRequest request(String amount) {
        return request(amount, PAYMENT_TIME);
    }

    private AuthorizationRequest request(String amount, Instant paymentTime) {
        return new AuthorizationRequest("0100", "000001", PAN, "000000", new BigDecimal(amount), "643",
                paymentTime, "TERM0001", "POS", "MERCH0000000001", "5411", "ACQ001", "ISS001");
    }
}

package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.CardEventNotifier;
import com.processing.cardmanagement.events.CardServiceReserveEvent;
import com.processing.cardmanagement.events.CardServiceRollbackEvent;
import com.processing.cardmanagement.exceptions.CardNotFoundException;
import com.processing.cardmanagement.exceptions.InsufficientFundsException;
import com.processing.cardmanagement.exceptions.ReservationAlreadyExistsException;
import com.processing.cardmanagement.exceptions.ReservationNotFoundException;
import com.processing.cardmanagement.exceptions.RollbackAlreadySatisfiedException;
import com.processing.cardmanagement.models.Card;
import com.processing.cardmanagement.models.CardStatus;
import com.processing.cardmanagement.models.Reservation;
import com.processing.cardmanagement.models.ReservationRollback;
import com.processing.cardmanagement.models.ReservationStatus;
import com.processing.cardmanagement.options.CardServiceDefaults;
import com.processing.cardmanagement.options.CardServiceSettings;
import com.processing.cardmanagement.repositories.CardRepository;
import com.processing.cardmanagement.repositories.ReservationRepository;
import com.processing.cardmanagement.repositories.ReservationRollbackRepository;
import com.processing.cardmanagement.services.retries.RetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardServiceImplTest {
    private static final String PAN = "4000001234567890";
    private static final String RRN = "900000000003";
    private static final BigDecimal AMOUNT = new BigDecimal("100");
    private static final Instant CREATED_AT = Instant.parse("2026-10-15T10:00:00Z");

    @Mock private CardRepository cardRepository;
    @Mock private ReservationRepository reservationRepository;
    @Mock private ReservationRollbackRepository rollbackRepository;
    @Mock private CardServiceSettings settings;
    @Mock private CardServiceDefaults defaults;
    @Mock private PanGenerator panGenerator;
    @Mock private CardEventNotifier eventNotifier;
    @Mock private BinIssuerService binIssuerService;
    @Mock private RetryService retryService;
    @Mock private TransactionRunner transactionRunner;
    private CardServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CardServiceImpl(cardRepository, reservationRepository, rollbackRepository, settings, defaults,
                panGenerator, eventNotifier, binIssuerService, retryService, transactionRunner);
        when(transactionRunner.runSupplier(any())).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(0).get());
    }

    @Test
    void shouldPersistReservationAndReducedBalanceWhenPaymentIsAllowed() {
        // Arrange
        Card original = card("1000");
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(original));
        when(reservationRepository.isUnique(RRN, PAN)).thenReturn(true);
        when(reservationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cardRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ArgumentCaptor<Reservation> saved = ArgumentCaptor.forClass(Reservation.class);

        // Act
        Card result = service.reserve(PAN, AMOUNT, RRN);

        // Assert
        assertThat(result.availableBalance()).isEqualByComparingTo("900");
        assertThat(original.availableBalance()).isEqualByComparingTo("1000");
        verify(cardRepository).update(result);
        verify(reservationRepository).save(saved.capture());
        assertThat(saved.getValue().pan()).isEqualTo(PAN);
        assertThat(saved.getValue().rrn()).isEqualTo(RRN);
        assertThat(saved.getValue().reservationAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(saved.getValue().status()).isEqualTo(ReservationStatus.RESERVED);
        verify(eventNotifier).notifyListeners(new CardServiceReserveEvent(PAN, RRN, AMOUNT));
    }

    @Test
    void shouldRejectWithoutSavingWhenReservationAlreadyExists() {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card("1000")));
        when(reservationRepository.isUnique(RRN, PAN)).thenReturn(false);

        // Act / Assert
        assertThatThrownBy(() -> service.reserve(PAN, AMOUNT, RRN)).isInstanceOf(ReservationAlreadyExistsException.class);
        verify(reservationRepository, never()).save(any());
        verify(cardRepository, never()).update(any());
        verifyNoInteractions(eventNotifier);
    }

    @Test
    void shouldRejectWithoutSavingWhenCardDoesNotExist() {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.empty());

        // Act / Assert
        assertThatThrownBy(() -> service.reserve(PAN, AMOUNT, RRN)).isInstanceOf(CardNotFoundException.class);
        verify(cardRepository, never()).update(any());
        verify(reservationRepository, never()).save(any());
        verifyNoInteractions(rollbackRepository, eventNotifier);
    }

    @Test
    void shouldRejectWithoutSavingWhenBalanceIsInsufficient() {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card("99")));

        // Act / Assert
        assertThatThrownBy(() -> service.reserve(PAN, AMOUNT, RRN)).isInstanceOf(InsufficientFundsException.class);
        verify(cardRepository, never()).update(any());
        verify(reservationRepository, never()).save(any());
        verifyNoInteractions(rollbackRepository, eventNotifier);
    }

    @Test
    void shouldPersistRollbackAndRestoredBalanceWhenReservationExists() {
        // Arrange
        Card original = card("900");
        Reservation reservation = reservation(ReservationStatus.RESERVED);
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(original));
        when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.of(reservation));
        when(rollbackRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reservationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cardRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ArgumentCaptor<ReservationRollback> rollback = ArgumentCaptor.forClass(ReservationRollback.class);
        ArgumentCaptor<Reservation> updated = ArgumentCaptor.forClass(Reservation.class);

        // Act
        Card result = service.rollback(PAN, AMOUNT, RRN);

        // Assert
        assertThat(result.availableBalance()).isEqualByComparingTo("1000");
        assertThat(original.availableBalance()).isEqualByComparingTo("900");
        verify(cardRepository).update(result);
        verify(rollbackRepository).save(rollback.capture());
        assertThat(rollback.getValue().pan()).isEqualTo(PAN);
        assertThat(rollback.getValue().rrn()).isEqualTo(RRN);
        assertThat(rollback.getValue().rollbackAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(rollback.getValue().reservationId()).isEqualTo(reservation.id());
        verify(reservationRepository).save(updated.capture());
        assertThat(updated.getValue().id()).isEqualTo(reservation.id());
        assertThat(updated.getValue().status()).isEqualTo(ReservationStatus.ROLLED_BACK);
        verify(eventNotifier).notifyListeners(new CardServiceRollbackEvent(PAN, RRN, AMOUNT));
    }

    @Test
    void shouldRejectRollbackWithoutSavingWhenReservationDoesNotExist() {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card("900")));
        when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.empty());

        // Act / Assert
        assertThatThrownBy(() -> service.rollback(PAN, AMOUNT, RRN)).isInstanceOf(ReservationNotFoundException.class);
        verify(reservationRepository, never()).save(any());
        verify(cardRepository, never()).update(any());
        verifyNoInteractions(rollbackRepository, eventNotifier);
    }

    @Test
    void shouldRejectRollbackWithoutSavingWhenReservationWasAlreadyRolledBack() {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card("900")));
        when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN))
                .thenReturn(Optional.of(reservation(ReservationStatus.ROLLED_BACK)));

        // Act / Assert
        assertThatThrownBy(() -> service.rollback(PAN, AMOUNT, RRN)).isInstanceOf(RollbackAlreadySatisfiedException.class);
        verify(reservationRepository, never()).save(any());
        verify(cardRepository, never()).update(any());
        verifyNoInteractions(rollbackRepository, eventNotifier);
    }

    private Card card(String balance) {
        return new Card(UUID.fromString("00000000-0000-0000-0000-000000000001"), PAN, "400000",
                "KAMILA BULGAKOVA", YearMonth.of(2027, 12), CardStatus.ACTIVE, "643",
                new BigDecimal("1000"), new BigDecimal("2000"), new BigDecimal(balance), "ISS001", CREATED_AT);
    }

    private Reservation reservation(ReservationStatus status) {
        return new Reservation(UUID.fromString("00000000-0000-0000-0000-000000000002"),
                PAN, AMOUNT, RRN, status, CREATED_AT, CREATED_AT);
    }
}

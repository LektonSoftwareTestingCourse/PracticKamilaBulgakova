package com.processing.cardmanagement.models;

import com.processing.cardmanagement.exceptions.InsufficientFundsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardTest {
    private static final String PAN = "4000001234567890";
    private static final String RRN = "900000000003";
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("1000");
    private static final BigDecimal DAILY_LIMIT = new BigDecimal("1000");
    private static final BigDecimal MONTHLY_LIMIT = new BigDecimal("2000");

    @ParameterizedTest(name = "reserve={0}, balance=1000")
    @ValueSource(strings = {"999", "1000"})
    void shouldCreateReservationWhenAmountDoesNotExceedBalance(String amount) {
        // Arrange
        Card card = cardWithBalance(CardStatus.ACTIVE, INITIAL_BALANCE);

        // Act
        Reservation reservation = card.startReservation(new BigDecimal(amount), RRN);

        // Assert
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(reservation.reservationAmount()).isEqualByComparingTo(amount);
        assertThat(reservation.pan()).isEqualTo(PAN);
        assertThat(reservation.rrn()).isEqualTo(RRN);
    }

    @ParameterizedTest(name = "reserve={0}, remaining={1}")
    @CsvSource({"999,1", "1000,0"})
    void shouldSubtractFromCopyWhenReservationMatchesCard(String amount, String remaining) {
        // Arrange
        Card card = cardWithBalance(CardStatus.ACTIVE, INITIAL_BALANCE);
        Reservation reservation = new Reservation(PAN, new BigDecimal(amount), RRN);

        // Act
        Card reserved = card.withReservation(reservation);

        // Assert
        assertThat(reserved.availableBalance()).isEqualByComparingTo(remaining);
        assertThat(card.availableBalance()).isEqualByComparingTo(INITIAL_BALANCE);
        assertThat(reserved.pan()).isEqualTo(PAN);
    }

    @Test
    void shouldRejectWhenAmountExceedsBalanceByOneKopeck() {
        // Arrange
        Card card = cardWithBalance(CardStatus.ACTIVE, INITIAL_BALANCE);

        // Act / Assert
        assertThatThrownBy(() -> card.startReservation(new BigDecimal("1001"), RRN))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(card.availableBalance()).isEqualByComparingTo(INITIAL_BALANCE);
    }

    @ParameterizedTest(name = "status={0}")
    @EnumSource(value = CardStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "ACTIVE")
    void shouldRejectReservationWhenCardIsNotActive(CardStatus status) {
        // Arrange
        Card card = cardWithBalance(status, INITIAL_BALANCE);

        // Act / Assert
        assertThatThrownBy(() -> card.startReservation(BigDecimal.ONE, RRN)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest(name = "daily={0}, monthly={1}")
    @CsvSource({"-1,2000", "1000,-1", "2001,2000"})
    void shouldRejectLimitsWhenTheyAreNegativeOrInconsistent(String daily, String monthly) {
        // Arrange
        Card card = cardWithBalance(CardStatus.ACTIVE, INITIAL_BALANCE);

        // Act / Assert
        assertThatThrownBy(() -> card.withData(CardStatus.ACTIVE,
                new BigDecimal(daily), new BigDecimal(monthly), INITIAL_BALANCE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Card cardWithBalance(CardStatus status, BigDecimal balance) {
        return new Card(UUID.fromString("00000000-0000-0000-0000-000000000001"), PAN, "400000",
                "KAMILA BULGAKOVA", YearMonth.of(2027, 12), status, "643",
                DAILY_LIMIT, MONTHLY_LIMIT, balance, "ISS001",
                Instant.parse("2026-10-15T10:00:00Z"));
    }
}

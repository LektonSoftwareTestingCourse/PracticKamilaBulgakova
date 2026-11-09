package com.processing.service;

import com.processing.config.SwitchProperties;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingServiceTest {
    @ParameterizedTest(name = "pan={0}, issuer={1}")
    @CsvSource({"4000001234567890,ISS001", "4000011234567890,ISS002", "4000041234567890,ISS005"})
    void shouldResolveIssuerWhenBinIsKnown(String pan, String issuer) {
        // Arrange
        RoutingService service = routingService();

        // Act
        String result = service.getIssuerIdByPan(pan);

        // Assert
        assertThat(result).isEqualTo(issuer);
    }

    @ParameterizedTest(name = "invalid pan={0}")
    @NullAndEmptySource
    @ValueSource(strings = {"40000", "9999991234567890"})
    void shouldRejectWhenPanIsMissingShortOrHasUnknownBin(String pan) {
        // Arrange
        RoutingService service = routingService();

        // Act / Assert
        assertThatThrownBy(() -> service.getIssuerIdByPan(pan)).isInstanceOf(UnknownBinException.class);
    }

    private RoutingService routingService() {
        SwitchProperties properties = new SwitchProperties("1", Map.of(
                "400000", "ISS001", "400001", "ISS002", "400004", "ISS005"), null, null, null, null, null, null);
        return new RoutingService(properties);
    }
}

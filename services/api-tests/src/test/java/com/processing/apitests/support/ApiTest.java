package com.processing.apitests.support;

import org.junit.jupiter.api.AfterEach;

public abstract class ApiTest {
    protected final CardFixtures cards = new CardFixtures();

    @AfterEach
    void removeFixtureCards() {
        cards.clean();
    }
}

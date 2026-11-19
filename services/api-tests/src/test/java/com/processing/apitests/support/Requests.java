package com.processing.apitests.support;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

public final class Requests {
    public static final String TEST_BIN = "400000";
    public static final int BALANCE = 200_000;
    public static final int DAILY_LIMIT = 150_000;
    public static final int MONTHLY_LIMIT = 180_000;

    private static final AtomicInteger TRACE =
            new AtomicInteger(ThreadLocalRandom.current().nextInt(1_000_000));

    private Requests() {
    }

    public static Map<String, Object> card() {
        return new LinkedHashMap<>(Map.of(
                "bin", TEST_BIN, "cardholderName", "API TESTER", "currencyCode", "643",
                "dailyLimit", DAILY_LIMIT, "monthlyLimit", MONTHLY_LIMIT, "initialBalance", BALANCE));
    }

    public static Map<String, Object> transaction(String pan) {
        Map<String, Object> request = new LinkedHashMap<>(Map.of(
                "mti", "0100", "stan", nextStan(), "pan", pan, "processingCode", "000000",
                "amount", 10_000, "currencyCode", "643", "transmissionDateTime", Instant.now().toString(),
                "terminalId", "TERM0001", "terminalType", "POS", "merchantId", uniqueMerchant()));
        request.put("mcc", "5411");
        request.put("acquirerId", "ACQ001");
        return request;
    }

    public static Map<String, Object> logEntry(String pan) {
        Map<String, Object> request = transaction(pan);
        request.put("id", UUID.randomUUID().toString());
        request.put("rrn", nextRrn());
        request.put("status", "APPROVED");
        request.put("authCode", "A1B2C3");
        request.put("processingTimeMs", 1);
        String timestamp = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
        request.put("transmissionDateTime", timestamp);
        request.put("createdAt", timestamp);
        return request;
    }

    public static String nextStan() {
        return String.format(Locale.ROOT, "%06d", Math.floorMod(TRACE.getAndIncrement(), 1_000_000));
    }

    public static String nextRrn() {
        return String.format(Locale.ROOT, "%012d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
    }

    public static String uniqueMerchant() {
        return "MERCH" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
    }

    public static String unusedPan(String bin) {
        return bin + String.format(Locale.ROOT, "%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L));
    }

    public static Map<String, Object> with(Map<String, Object> original, String field, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(original);
        copy.put(field, value);
        return copy;
    }
}

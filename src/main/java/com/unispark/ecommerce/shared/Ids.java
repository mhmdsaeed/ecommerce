package com.unispark.ecommerce.shared;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Entity ids: UUID version 7 (RFC 9562), generated in Java (SPEC 5). The 48-bit millisecond timestamp prefix
 * keeps B-tree inserts mostly sequential; the 74 random bits come from {@link SecureRandom} so ids that appear
 * in URLs cannot be guessed.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static UUID newId() {
        return newId(System.currentTimeMillis());
    }

    static UUID newId(long epochMillis) {
        var randomA = RANDOM.nextInt() & 0x0FFFL;
        var randomB = RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL;
        var mostSignificant = ((epochMillis & 0xFFFF_FFFF_FFFFL) << 16) | 0x7000L | randomA;
        var leastSignificant = 0x8000_0000_0000_0000L | randomB;
        return new UUID(mostSignificant, leastSignificant);
    }
}

package com.selfanalyst.events.raw;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;

/**
 * 生成 26 字符 Crockford Base32 ID：48 位毫秒时间戳加 80 位随机熵。
 * 同一进程内时钟不前进或回拨时递增熵，从而保持字典序单调。
 */
public final class RawEventIdGenerator {

    private static final char[] BASE32 =
            "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private final Clock clock;
    private final SecureRandom random;
    private long lastTimestamp = -1;
    private final byte[] lastEntropy = new byte[10];

    public RawEventIdGenerator() {
        this(Clock.systemUTC(), new SecureRandom());
    }

    RawEventIdGenerator(Clock clock, SecureRandom random) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    public synchronized String nextId() {
        long now = clock.millis();
        if (now > lastTimestamp) {
            lastTimestamp = now;
            random.nextBytes(lastEntropy);
        } else if (!incrementEntropy()) {
            lastTimestamp++;
            random.nextBytes(lastEntropy);
        }

        byte[] value = new byte[16];
        long timestamp = lastTimestamp;
        for (int i = 5; i >= 0; i--) {
            value[i] = (byte) timestamp;
            timestamp >>>= 8;
        }
        System.arraycopy(lastEntropy, 0, value, 6, lastEntropy.length);
        return encodeBase32(value);
    }

    private boolean incrementEntropy() {
        for (int i = lastEntropy.length - 1; i >= 0; i--) {
            lastEntropy[i]++;
            if (lastEntropy[i] != 0) return true;
        }
        return false;
    }

    private static String encodeBase32(byte[] value) {
        char[] encoded = new char[26];
        int bitBuffer = 0;
        int bitsInBuffer = 2; // ULID has two leading zero padding bits.
        int output = 0;
        for (byte current : value) {
            bitBuffer = (bitBuffer << 8) | (current & 0xff);
            bitsInBuffer += 8;
            while (bitsInBuffer >= 5) {
                bitsInBuffer -= 5;
                encoded[output++] = BASE32[(bitBuffer >>> bitsInBuffer) & 0x1f];
            }
        }
        if (bitsInBuffer > 0) {
            encoded[output++] = BASE32[(bitBuffer << (5 - bitsInBuffer)) & 0x1f];
        }
        if (output != encoded.length) {
            throw new IllegalStateException("原始事件 ID 编码长度错误");
        }
        return new String(encoded);
    }
}

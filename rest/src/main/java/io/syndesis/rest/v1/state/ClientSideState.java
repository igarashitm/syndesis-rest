/**
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.syndesis.rest.v1.state;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Base64.Decoder;
import java.util.Base64.Encoder;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.NewCookie;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;

/**
 * Persists given state on the client with these properties:
 * <ul>
 * <li>State remains opaque (encrypted) so client cannot determine what is
 * stored
 * <li>State tampering is detected by using MAC
 * <li>State timeout is enforced (default 15min)
 * </ul>
 * <p>
 * Given a {@link KeySource} construct {@link ClientSideState} as:
 * {@code new ClientSideState(keySource)}, and then persist state into HTTP
 * Cookie with {@link #persist(String, String, Object)} method, and restore the
 * state with {@link #restoreFrom(Cookie, Class)} method.
 * <p>
 * The implementation follows the
 * <a href="https://tools.ietf.org/html/rfc6896">RFC6896</a> Secure Cookie
 * Sessions for HTTP.
 */
public final class ClientSideState {
    // 15 min
    public static final long DEFAULT_TIMEOUT = 15 * 60;

    private static final Decoder DECODER = Base64.getUrlDecoder();

    private static final Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private static final int IV_LEN = 16;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Edition edition;

    private final BiFunction<Class<?>, byte[], Object> deserialization;

    private final Supplier<byte[]> ivSource;

    private final Function<Object, byte[]> serialization;

    private final long timeout;

    private final Supplier<Long> timeSource;

    protected static final class RandomIvSource implements Supplier<byte[]> {
        private static final SecureRandom RANDOM = new SecureRandom();

        @Override
        public byte[] get() {
            final byte[] iv = new byte[IV_LEN];
            RANDOM.nextBytes(iv);

            return iv;
        }
    }

    public ClientSideState(final Edition edition) {
        this(edition, ClientSideState::currentTimestmpUtc, new RandomIvSource(), ClientSideState::serialize,
            ClientSideState::deserialize, DEFAULT_TIMEOUT);
    }

    public ClientSideState(final Edition edition, final long timeout) {
        this(edition, ClientSideState::currentTimestmpUtc, new RandomIvSource(), ClientSideState::serialize,
            ClientSideState::deserialize, timeout);
    }

    protected ClientSideState(final Edition edition, final Supplier<Long> timeSource, long timeout) {
        this(edition, timeSource, new RandomIvSource(), ClientSideState::serialize, ClientSideState::deserialize,
            timeout);
    }

    protected ClientSideState(final Edition edition, final Supplier<Long> timeSource, final Supplier<byte[]> ivSource,
        final Function<Object, byte[]> serialization, final BiFunction<Class<?>, byte[], Object> deserialization,
        final long timeout) {
        this.edition = edition;
        this.timeSource = timeSource;
        this.ivSource = ivSource;
        this.serialization = serialization;
        this.deserialization = deserialization;
        this.timeout = timeout;
    }

    public NewCookie persist(final String key, final String path, final Object value) {
        return new NewCookie(key, protect(value), path, null, null, NewCookie.DEFAULT_MAX_AGE, true, true);
    }

    public <T> T restoreFrom(final Cookie cookie, final Class<T> type) {
        final String value = cookie.getValue();

        final int lastSeparatorIdx = value.lastIndexOf('|');

        KeySource keySource = edition.keySource();
        final byte[] calculated = mac(edition.authenticationAlgorithm, value.substring(0, lastSeparatorIdx),
            keySource.authenticationKey());

        final String[] parts = value.split("\\|", 5);

        final byte[] encrypted = DECODER.decode(parts[0]);
        final byte[] atime = DECODER.decode(parts[1]);
        final byte[] tid = DECODER.decode(parts[2]);
        final byte[] iv = DECODER.decode(parts[3]);
        final byte[] mac = DECODER.decode(parts[4]);

        final long atimeLong = atime(atime);

        if (atimeLong + timeout < timeSource.get()) {
            throw new IllegalArgumentException("Given value has timed out at: " + Instant.ofEpochSecond(atimeLong));
        }

        if (!MessageDigest.isEqual(tid, edition.tid)) {
            throw new IllegalArgumentException(String.format("Given TID `%s`, mismatches current TID `%s`",
                new BigInteger(tid).toString(16), new BigInteger(edition.tid).toString(16)));
        }

        if (!MessageDigest.isEqual(mac, calculated)) {
            throw new IllegalArgumentException("Cookie value fails authenticity check");
        }

        final byte[] clear = decrypt(edition.encryptionAlgorithm, iv, encrypted, keySource.encryptionKey());

        @SuppressWarnings("unchecked")
        final T ret = (T) deserialization.apply(type, clear);

        return ret;
    }

    protected byte[] atime() {
        final long nowInSec = timeSource.get();
        final String nowAsStr = Long.toString(nowInSec);

        return nowAsStr.getBytes(StandardCharsets.US_ASCII);
    }

    protected byte[] iv() {
        return ivSource.get();
    }

    protected String protect(final Object value) {
        final byte[] clear = serialization.apply(value);

        final byte[] iv = iv();

        KeySource keySource = edition.keySource();
        final SecretKey encryptionKey = keySource.encryptionKey();
        final byte[] cipher = encrypt(edition.encryptionAlgorithm, iv, clear, encryptionKey);

        final byte[] atime = atime();

        final StringBuilder base = new StringBuilder();
        base.append(ENCODER.encodeToString(cipher));
        base.append('|');

        base.append(ENCODER.encodeToString(atime));
        base.append('|');

        base.append(ENCODER.encodeToString(edition.tid));
        base.append('|');

        base.append(ENCODER.encodeToString(iv));

        final byte[] mac = mac(edition.authenticationAlgorithm, base, keySource.authenticationKey());

        base.append('|');
        base.append(ENCODER.encodeToString(mac));

        return base.toString();
    }

    protected static long atime(final byte[] atime) {
        final String timeAsStr = new String(atime, StandardCharsets.US_ASCII);

        return Long.parseLong(timeAsStr);
    }

    protected static long currentTimestmpUtc() {
        return Instant.now().toEpochMilli() / 1000;
    }

    protected static byte[] decrypt(final String encryptionAlgorithm, final byte[] iv, final byte[] encrypted,
        final SecretKey encryptionKey) {
        try {
            final Cipher cipher = Cipher.getInstance(encryptionAlgorithm);

            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new IvParameterSpec(iv));

            return cipher.doFinal(encrypted);
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("Unable to encrypt the given value", e);
        }
    }

    protected static Object deserialize(final Class<?> type, final byte[] pickle) {
        final ObjectReader reader = MAPPER.readerFor(type);

        try {
            return reader.readValue(pickle);
        } catch (final IOException e) {
            throw new IllegalArgumentException("Unable to serialize given pickle to value", e);
        }
    }

    protected static byte[] encrypt(final String encryptionAlgorithm, final byte[] iv, final byte[] clear,
        final SecretKey encryptionKey) {
        try {
            final Cipher cipher = Cipher.getInstance(encryptionAlgorithm);

            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new IvParameterSpec(iv));

            return cipher.doFinal(clear);
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("Unable to encrypt the given value", e);
        }
    }

    protected static byte[] mac(final String authenticationAlgorithm, final CharSequence base,
        final SecretKey authenticationKey) {
        try {
            final String baseString = base.toString();

            final Mac mac = Mac.getInstance(authenticationAlgorithm);
            mac.init(authenticationKey);

            // base contains only BASE64 characters and '|', so we use ASCII
            final byte[] raw = baseString.getBytes(StandardCharsets.US_ASCII);

            return mac.doFinal(raw);
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("Unable to compute MAC of the given value", e);
        }
    }

    protected static byte[] serialize(final Object value) {
        final ObjectWriter writer = MAPPER.writerFor(value.getClass());

        try {
            return writer.writeValueAsBytes(value);
        } catch (final JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize given value: " + value, e);
        }
    }

}

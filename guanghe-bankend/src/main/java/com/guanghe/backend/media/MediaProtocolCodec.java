package com.guanghe.backend.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class MediaProtocolCodec {
    private static final int MAX_ENVELOPE_BYTES = 2 * 1024 * 1024;
    private final ObjectMapper json;
    private final SecureRandom random;

    MediaProtocolCodec(ObjectMapper json) { this(json, new SecureRandom()); }
    MediaProtocolCodec(ObjectMapper json, SecureRandom random) { this.json = json; this.random = random; }

    Map<String, String> encrypt(Map<String, ?> payload, String key) {
        byte[] secret = validateKey(key);
        try {
            byte[] iv = new byte[16];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secret, "AES"), new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(serialize(payload));
            Map<String, String> inner = new LinkedHashMap<>();
            inner.put("iv", Base64.getEncoder().encodeToString(iv));
            inner.put("value", Base64.getEncoder().encodeToString(encrypted));
            String response = Base64.getEncoder().encodeToString(json.writeValueAsBytes(inner));
            return Map.of("response", response, "sign", digest(response), "app_key", key);
        } catch (Exception exception) {
            throw new MediaProviderException("MEDIA_PROTOCOL_INVALID", false, exception);
        }
    }

    JsonNode decrypt(String response, String sign, String key) {
        byte[] secret = validateKey(key);
        if (response == null || response.getBytes(StandardCharsets.UTF_8).length > MAX_ENVELOPE_BYTES
                || !MessageDigest.isEqual(digest(response).getBytes(StandardCharsets.US_ASCII),
                String.valueOf(sign).toLowerCase().getBytes(StandardCharsets.US_ASCII))) {
            throw new MediaProviderException("MEDIA_CALLBACK_SIGNATURE_INVALID", false);
        }
        try {
            JsonNode inner = json.readTree(Base64.getDecoder().decode(response));
            byte[] iv = Base64.getDecoder().decode(inner.path("iv").textValue());
            byte[] value = Base64.getDecoder().decode(inner.path("value").textValue());
            if (iv.length != 16 || value.length == 0 || value.length > MAX_ENVELOPE_BYTES) {
                throw new IllegalArgumentException("invalid encrypted payload");
            }
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret, "AES"), new IvParameterSpec(iv));
            Object parsed = new Parser(cipher.doFinal(value)).parseAll();
            return json.valueToTree(parsed);
        } catch (MediaProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new MediaProviderException("MEDIA_CALLBACK_PAYLOAD_INVALID", false, exception);
        }
    }

    private byte[] validateKey(String key) {
        byte[] bytes = key == null ? new byte[0] : key.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length != 32 || !StandardCharsets.US_ASCII.newEncoder().canEncode(key)) {
            throw new MediaProviderException("MEDIA_PROVIDER_NOT_CONFIGURED", false);
        }
        return bytes;
    }

    private byte[] serialize(Object value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeValue(out, value);
        return out.toByteArray();
    }

    private void writeValue(ByteArrayOutputStream out, Object value) {
        if (value instanceof Map<?, ?> map) {
            write(out, "a:" + map.size() + ":{");
            map.forEach((key, item) -> { writeString(out, key); writeValue(out, item); });
            write(out, "}");
        } else if (value instanceof List<?> list) {
            write(out, "a:" + list.size() + ":{");
            for (int i = 0; i < list.size(); i++) { writeString(out, i); writeValue(out, list.get(i)); }
            write(out, "}");
        } else {
            writeString(out, value == null ? "" : value);
        }
    }

    private void writeString(ByteArrayOutputStream out, Object value) {
        byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        write(out, "s:" + bytes.length + ":\"");
        out.writeBytes(bytes);
        write(out, "\";");
    }

    private void write(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
    }

    private String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static final class Parser {
        private final byte[] data;
        private int position;
        private Parser(byte[] data) { this.data = data; }
        private Object parseAll() {
            Object value = parse();
            if (position != data.length) throw new IllegalArgumentException("trailing payload");
            return value;
        }
        private Object parse() {
            if (position >= data.length) throw new IllegalArgumentException("truncated payload");
            char marker = (char) data[position];
            return switch (marker) {
                case 'a' -> array();
                case 's' -> string();
                case 'i' -> Long.parseLong(scalar());
                case 'd' -> Double.parseDouble(scalar());
                case 'b' -> "1".equals(scalar());
                case 'N' -> { expect('N'); expect(';'); yield null; }
                default -> throw new IllegalArgumentException("unsupported payload type");
            };
        }
        private Object array() {
            position += 2;
            int count = Integer.parseInt(readUntil(':'));
            expect('{');
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            boolean list = true;
            for (int index = 0; index < count; index++) {
                Object key = parse();
                if (!String.valueOf(index).equals(String.valueOf(key))) list = false;
                values.put(String.valueOf(key), parse());
            }
            expect('}');
            if (!list) return values;
            return new ArrayList<>(values.values());
        }
        private String string() {
            position += 2;
            int length = Integer.parseInt(readUntil(':'));
            expect('"');
            if (length < 0 || position + length > data.length) throw new IllegalArgumentException("invalid length");
            String value = new String(data, position, length, StandardCharsets.UTF_8);
            position += length;
            expect('"'); expect(';');
            return value;
        }
        private String scalar() { position += 2; return readUntil(';'); }
        private String readUntil(char marker) {
            int start = position;
            while (position < data.length && data[position] != (byte) marker) position++;
            if (position == data.length) throw new IllegalArgumentException("delimiter missing");
            String value = new String(data, start, position - start, StandardCharsets.US_ASCII);
            position++;
            return value;
        }
        private void expect(char value) {
            if (position >= data.length || data[position] != (byte) value) throw new IllegalArgumentException("invalid payload");
            position++;
        }
    }
}

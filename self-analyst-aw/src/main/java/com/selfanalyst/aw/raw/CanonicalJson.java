package com.selfanalyst.aw.raw;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/** 原始事件 data 的确定性 JSON 与 SHA-256 表示。 */
public final class CanonicalJson {

    private static final ObjectWriter WRITER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .writer()
            .with(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

    private CanonicalJson() {}

    public record Value(String json, String sha256) {}

    public static Value encode(Map<String, ?> data) {
        if (data == null) throw new IllegalArgumentException("原始事件 data 不能为空");
        try {
            String json = WRITER.writeValueAsString(normalize(data));
            return new Value(json, sha256(json));
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalArgumentException("无法规范化原始事件 data", serializationFailure);
        }
    }

    private static Object normalize(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("原始事件 JSON 对象键必须是字符串");
                }
                sorted.put(key, normalize(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof Collection<?> collection) {
            ArrayList<Object> normalized = new ArrayList<>(collection.size());
            for (Object item : collection) normalized.add(normalize(item));
            return normalized;
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof BigInteger) {
            return new BigInteger(value.toString());
        }
        if (value instanceof BigDecimal decimal) {
            return normalizeDecimal(decimal);
        }
        if (value instanceof Float number) {
            if (!Float.isFinite(number)) {
                throw new IllegalArgumentException("原始事件数字必须是有限值");
            }
            return normalizeDecimal(new BigDecimal(number.toString()));
        }
        if (value instanceof Double number) {
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException("原始事件数字必须是有限值");
            }
            return normalizeDecimal(BigDecimal.valueOf(number));
        }
        if (value instanceof Number number) {
            return normalizeDecimal(new BigDecimal(number.toString()));
        }
        throw new IllegalArgumentException(
                "原始事件 data 包含不支持的 JSON 值类型: " + value.getClass().getName());
    }

    private static BigDecimal normalizeDecimal(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.scale() < 0 ? normalized.setScale(0) : normalized;
    }

    private static String sha256(String canonicalJson) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 不支持 SHA-256", impossible);
        }
    }
}

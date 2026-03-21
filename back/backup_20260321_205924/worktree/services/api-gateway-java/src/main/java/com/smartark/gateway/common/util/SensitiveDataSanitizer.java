package com.smartark.gateway.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

public final class SensitiveDataSanitizer {
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[-._A-Za-z0-9]+");
    private static final Pattern OPENAI_LIKE = Pattern.compile("\\bsk-[A-Za-z0-9]{10,}\\b");
    private static final Pattern ALIYUN_LIKE = Pattern.compile("\\bsk-[A-Za-z0-9]{10,}\\b");

    private SensitiveDataSanitizer() {
    }

    public static String sanitizeJsonString(ObjectMapper mapper, String jsonOrText) {
        if (jsonOrText == null) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(jsonOrText);
            JsonNode sanitized = sanitizeNode(node);
            return mapper.writeValueAsString(sanitized);
        } catch (Exception ignored) {
            return sanitizeText(jsonOrText);
        }
    }

    public static JsonNode sanitizeNode(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode obj = ((ObjectNode) node).deepCopy();
            Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey();
                JsonNode val = entry.getValue();
                if (isSensitiveKey(key)) {
                    obj.put(key, "***");
                } else {
                    obj.set(key, sanitizeNode(val));
                }
            }
            return obj;
        }
        if (node.isArray()) {
            ArrayNode arr = ((ArrayNode) node).deepCopy();
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, sanitizeNode(arr.get(i)));
            }
            return arr;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(sanitizeText(node.asText()));
        }
        return node;
    }

    public static String sanitizeText(String text) {
        if (text == null) {
            return null;
        }
        String out = text;
        out = BEARER.matcher(out).replaceAll("Bearer ***");
        out = OPENAI_LIKE.matcher(out).replaceAll("sk-***");
        out = ALIYUN_LIKE.matcher(out).replaceAll("sk-***");
        return out;
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase();
        return k.contains("password")
                || k.contains("token")
                || k.contains("secret")
                || k.equals("authorization")
                || k.equals("api_key")
                || k.equals("apikey")
                || k.equals("access_key")
                || k.equals("accesskey");
    }
}

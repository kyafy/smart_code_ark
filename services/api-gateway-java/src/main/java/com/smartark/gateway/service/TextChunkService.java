package com.smartark.gateway.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class TextChunkService {

    public record TextChunk(int index, String content, int tokenCount) {}

    public List<TextChunk> chunk(String text, int maxTokens, int overlapTokens) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        int totalTokens = estimateTokens(text);
        if (totalTokens <= maxTokens) {
            return List.of(new TextChunk(0, text.trim(), totalTokens));
        }

        List<TextChunk> chunks = new ArrayList<>();
        int charIndex = 0;
        int chunkIdx = 0;

        while (charIndex < text.length()) {
            int endChar = findChunkEnd(text, charIndex, maxTokens);
            String chunkContent = text.substring(charIndex, endChar).trim();
            if (!chunkContent.isEmpty()) {
                chunks.add(new TextChunk(chunkIdx++, chunkContent, estimateTokens(chunkContent)));
            }

            int overlapChars = tokensToChars(overlapTokens);
            int nextStart = endChar - overlapChars;
            if (nextStart <= charIndex) {
                nextStart = endChar;
            }
            charIndex = nextStart;
        }

        return chunks;
    }

    private int findChunkEnd(String text, int startChar, int maxTokens) {
        int targetChars = tokensToChars(maxTokens);
        int endChar = Math.min(startChar + targetChars, text.length());

        if (endChar >= text.length()) {
            return text.length();
        }

        // Try to break at sentence boundary
        int lastSentenceBreak = -1;
        for (int i = endChar; i > startChar + (targetChars / 2); i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '.' || c == '！' || c == '!' || c == '？' || c == '?' || c == '\n') {
                lastSentenceBreak = i + 1;
                break;
            }
        }

        if (lastSentenceBreak > startChar) {
            return lastSentenceBreak;
        }

        // Fall back to space/CJK boundary
        for (int i = endChar; i > startChar + (targetChars / 2); i--) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || c == '，' || c == ',') {
                return i + 1;
            }
        }

        return endChar;
    }

    int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (isCjk(c)) {
                count += 2; // CJK characters ≈ 2 tokens
            } else {
                count++;
            }
        }
        // Rough approximation: ~4 chars per English token
        return Math.max(1, count / 4);
    }

    private int tokensToChars(int tokens) {
        return tokens * 4;
    }

    private boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }
}

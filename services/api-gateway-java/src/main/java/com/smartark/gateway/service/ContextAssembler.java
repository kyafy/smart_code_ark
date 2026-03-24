package com.smartark.gateway.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ContextAssembler {
    private final int contextMaxChars;

    public ContextAssembler(@Value("${smartark.memory.context.max-chars:4000}") int contextMaxChars) {
        this.contextMaxChars = Math.max(1000, contextMaxChars);
    }

    public AssembledContext assemble(String stepCode,
                                     String prd,
                                     String baseInstructions,
                                     List<String> shortTermMemories,
                                     List<String> longTermMemories) {
        StringBuilder sb = new StringBuilder();
        List<String> sources = new ArrayList<>();

        appendSection(sb, "CURRENT_STEP", stepCode == null ? "" : stepCode);
        appendSection(sb, "BASE_INSTRUCTIONS", safe(baseInstructions));
        sources.add("base_instructions");

        if (prd != null && !prd.isBlank()) {
            appendSection(sb, "PRD_SUMMARY", prd);
            sources.add("prd");
        }

        int shortCount = 0;
        if (shortTermMemories != null && !shortTermMemories.isEmpty()) {
            String shortSection = String.join("\n", shortTermMemories);
            appendSection(sb, "SHORT_TERM_MEMORY", shortSection);
            shortCount = shortTermMemories.size();
            sources.add("short_term_memory");
        }

        int longCount = 0;
        if (longTermMemories != null && !longTermMemories.isEmpty()) {
            String longSection = String.join("\n", longTermMemories);
            appendSection(sb, "LONG_TERM_MEMORY", longSection);
            longCount = longTermMemories.size();
            sources.add("long_term_memory");
        }

        String pack = sb.toString().trim();
        boolean truncated = false;
        if (pack.length() > contextMaxChars) {
            pack = pack.substring(0, contextMaxChars);
            truncated = true;
        }
        return new AssembledContext(pack, sources, shortCount, longCount, truncated);
    }

    private void appendSection(StringBuilder sb, String title, String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        sb.append("[").append(title).append("]\n");
        sb.append(body.trim()).append("\n\n");
    }

    private String safe(String text) {
        if (text == null) {
            return "";
        }
        return text.trim();
    }

    public record AssembledContext(
            String contextPack,
            List<String> sources,
            int shortTermCount,
            int longTermCount,
            boolean truncated
    ) {
    }
}

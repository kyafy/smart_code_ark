package com.smartark.template.mobile.ai;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simple prompt template builder with {{variable}} substitution.
 *
 * Usage:
 *   String prompt = PromptBuilder.of("Analyze {{document}} for {{purpose}}")
 *       .set("document", resumeText)
 *       .set("purpose", "job matching")
 *       .build();
 */
public class PromptBuilder {

    private String template;
    private final Map<String, String> variables = new LinkedHashMap<>();

    public static PromptBuilder of(String template) {
        PromptBuilder builder = new PromptBuilder();
        builder.template = template;
        return builder;
    }

    public PromptBuilder set(String key, String value) {
        variables.put(key, value != null ? value : "");
        return this;
    }

    public String build() {
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }
}

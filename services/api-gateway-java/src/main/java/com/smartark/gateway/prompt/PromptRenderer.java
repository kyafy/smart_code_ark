package com.smartark.gateway.prompt;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PromptRenderer {
    public String render(String template, Map<String, String> variables) {
        if (template == null) {
            return "";
        }
        String rendered = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String key = "{{" + entry.getKey() + "}}";
            rendered = rendered.replace(key, entry.getValue() == null ? "" : entry.getValue());
        }
        return rendered;
    }
}

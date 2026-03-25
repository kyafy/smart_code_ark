package com.smartark.gateway.prompt;

import com.smartark.gateway.db.entity.PromptTemplateEntity;
import com.smartark.gateway.db.entity.PromptVersionEntity;
import com.smartark.gateway.db.repo.PromptTemplateRepository;
import com.smartark.gateway.db.repo.PromptVersionRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class PromptResolver {
    private final PromptTemplateRepository promptTemplateRepository;
    private final PromptVersionRepository promptVersionRepository;

    public PromptResolver(PromptTemplateRepository promptTemplateRepository, PromptVersionRepository promptVersionRepository) {
        this.promptTemplateRepository = promptTemplateRepository;
        this.promptVersionRepository = promptVersionRepository;
    }

    public Optional<ResolvedPrompt> resolve(String templateKey) {
        return promptTemplateRepository.findByTemplateKey(templateKey).flatMap(template -> {
            Integer versionNo = template.getDefaultVersionNo();
            return promptVersionRepository.findByTemplateIdAndVersionNo(template.getId(), versionNo)
                    .map(version -> new ResolvedPrompt(template, version));
        });
    }

    public record ResolvedPrompt(PromptTemplateEntity template, PromptVersionEntity version) {
    }
}

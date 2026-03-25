package com.smartark.gateway.prompt;

import com.smartark.gateway.db.entity.PromptTemplateEntity;
import com.smartark.gateway.db.entity.PromptVersionEntity;
import com.smartark.gateway.db.repo.PromptTemplateRepository;
import com.smartark.gateway.db.repo.PromptVersionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromptResolverTest {

    @Mock
    private PromptTemplateRepository promptTemplateRepository;
    @Mock
    private PromptVersionRepository promptVersionRepository;

    @InjectMocks
    private PromptResolver promptResolver;

    @Test
    void resolve_shouldReturnDefaultVersionPrompt() {
        PromptTemplateEntity template = new PromptTemplateEntity();
        template.setId(100L);
        template.setTemplateKey("project_structure_plan");
        template.setDefaultVersionNo(3);

        PromptVersionEntity version = new PromptVersionEntity();
        version.setTemplateId(100L);
        version.setVersionNo(3);

        when(promptTemplateRepository.findByTemplateKey("project_structure_plan"))
                .thenReturn(Optional.of(template));
        when(promptVersionRepository.findByTemplateIdAndVersionNo(100L, 3))
                .thenReturn(Optional.of(version));

        Optional<PromptResolver.ResolvedPrompt> resolved = promptResolver.resolve("project_structure_plan");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().template().getTemplateKey()).isEqualTo("project_structure_plan");
        assertThat(resolved.get().version().getVersionNo()).isEqualTo(3);
        verify(promptVersionRepository).findByTemplateIdAndVersionNo(100L, 3);
    }

    @Test
    void resolve_shouldReturnEmptyWhenTemplateNotFound() {
        when(promptTemplateRepository.findByTemplateKey("missing_template"))
                .thenReturn(Optional.empty());

        Optional<PromptResolver.ResolvedPrompt> resolved = promptResolver.resolve("missing_template");

        assertThat(resolved).isEmpty();
        verifyNoInteractions(promptVersionRepository);
    }

    @Test
    void resolve_shouldReturnEmptyWhenDefaultVersionMissing() {
        PromptTemplateEntity template = new PromptTemplateEntity();
        template.setId(101L);
        template.setTemplateKey("codegen_file");
        template.setDefaultVersionNo(9);

        when(promptTemplateRepository.findByTemplateKey("codegen_file"))
                .thenReturn(Optional.of(template));
        when(promptVersionRepository.findByTemplateIdAndVersionNo(101L, 9))
                .thenReturn(Optional.empty());

        Optional<PromptResolver.ResolvedPrompt> resolved = promptResolver.resolve("codegen_file");

        assertThat(resolved).isEmpty();
        verify(promptVersionRepository).findByTemplateIdAndVersionNo(101L, 9);
    }
}

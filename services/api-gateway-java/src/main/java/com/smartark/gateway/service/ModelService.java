package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.config.ModelProperties;
import com.smartark.gateway.db.entity.PromptHistoryEntity;
import com.smartark.gateway.db.repo.PromptHistoryRepository;
import com.smartark.gateway.prompt.PromptRenderer;
import com.smartark.gateway.prompt.PromptResolver;
import com.smartark.gateway.service.modelgateway.ModelGatewayEndpoint;
import com.smartark.gateway.service.modelgateway.ModelGatewayInvocation;
import com.smartark.gateway.service.modelgateway.ModelGatewayResult;
import com.smartark.gateway.service.modelgateway.OpenAiCompatibleModelGatewayService;
import com.smartark.gateway.service.modelgateway.ChatCompletionsCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ModelService {
    private static final Logger logger = LoggerFactory.getLogger(ModelService.class);

    public record ModelResult(
            String model,
            String reply,
            List<String> draftModules,
            int tokenInput,
            int tokenOutput
    ) {
    }

    public record ConnectivityTestResult(
            boolean ok,
            String modelName,
            String provider,
            long latencyMs,
            String outputPreview,
            String errorType,
            String errorMessage,
            Integer httpStatus
    ) {
    }

    private record EffectiveConnection(String baseUrl, String apiKey, String source) {
    }

    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final boolean mockEnabled;
    private final String chatModel;
    private final String codeModel;
    private final String paperModel;
    private final PromptResolver promptResolver;
    private final PromptRenderer promptRenderer;
    private final PromptHistoryRepository promptHistoryRepository;
    private final OutputSchemaValidator outputSchemaValidator;
    private final boolean schemaValidationEnabled;
    private final int correctiveRetryMax;
    private final ModelRouterService modelRouterService;
    private final OpenAiCompatibleModelGatewayService modelGatewayService;
    private final ModelProperties modelProperties;

    public ModelService(
            ObjectMapper objectMapper,
            PromptResolver promptResolver,
            PromptRenderer promptRenderer,
            PromptHistoryRepository promptHistoryRepository,
            OutputSchemaValidator outputSchemaValidator,
            ModelRouterService modelRouterService,
            OpenAiCompatibleModelGatewayService modelGatewayService,
            ModelProperties modelProperties
    ) {
        this.objectMapper = objectMapper;
        this.modelProperties = modelProperties;
        this.baseUrl = modelProperties.getBaseUrl() == null ? "" : modelProperties.getBaseUrl().trim();
        this.apiKey = modelProperties.getApiKey() == null ? "" : modelProperties.getApiKey().trim();
        this.mockEnabled = modelProperties.isMockEnabled();
        this.chatModel = modelProperties.getChatModel();
        this.codeModel = modelProperties.getCodeModel();
        this.paperModel = modelProperties.getPaperModel();
        this.promptResolver = promptResolver;
        this.promptRenderer = promptRenderer;
        this.promptHistoryRepository = promptHistoryRepository;
        this.outputSchemaValidator = outputSchemaValidator;
        this.schemaValidationEnabled = modelProperties.isSchemaValidationEnabled();
        this.correctiveRetryMax = Math.max(0, modelProperties.getCorrectiveRetryMax());
        this.modelRouterService = modelRouterService;
        this.modelGatewayService = modelGatewayService;
    }

    /**
     * Resolve chat model via router (falls back to config default).
     */
    private String resolveChatModel() {
        return modelRouterService.resolve("chat");
    }

    /**
     * Resolve code model via router (falls back to config default).
     */
    private String resolveCodeModel() {
        return modelRouterService.resolve("code");
    }

    private String resolveCodeModel(String preferredModel) {
        if (preferredModel != null && !preferredModel.isBlank()) {
            String normalized = preferredModel.trim();
            if (modelRouterService.resolveConnection(normalized).isPresent()) {
                return normalized;
            }
            if (normalized.equals(codeModel)) {
                return normalized;
            }
            logger.warn("Preferred code model {} has no registry connection config, fallback to router/env", normalized);
        }
        return resolveCodeModel();
    }

    /**
     * Resolve paper model via router (falls back to config default).
     */
    private String resolvePaperModel() {
        return modelRouterService.resolve("paper");
    }

    private String resolvePaperModel(String preferredModel) {
        if (preferredModel != null && !preferredModel.isBlank()) {
            String normalized = preferredModel.trim();
            if (modelRouterService.resolveConnection(normalized).isPresent()) {
                return normalized;
            }
            if (normalized.equals(paperModel)) {
                return normalized;
            }
            logger.warn("Preferred paper model {} has no registry connection config, fallback to router/env", normalized);
        }
        return resolvePaperModel();
    }

    /**
     * Resolve global engineering rules from prompt_templates.
     * Prepended to system prompts of code-generation LLM calls.
     */
    private String resolveGlobalRulesPrefix() {
        try {
            var resolved = promptResolver.resolve("global_engineering_rules");
            if (resolved.isEmpty()) {
                logger.warn("Global engineering rules not found in prompt templates");
                return "";
            }
            String prompt = resolved.get().version().getSystemPrompt();
            if (prompt == null || prompt.isBlank()) {
                logger.warn("Global engineering rules resolved but system prompt is empty, versionNo={}",
                        resolved.get().version().getVersionNo());
                return "";
            }
            logger.info("Global engineering rules enabled, versionNo={}, length={}",
                    resolved.get().version().getVersionNo(), prompt.length());
            return prompt + "\n\n";
        } catch (Exception e) {
            logger.warn("Failed to resolve global engineering rules, skipping", e);
            return "";
        }
    }

    public void streamChatReply(String sessionTitle, String projectType, String userMessage, List<Map<String, String>> history, Consumer<String> onContent, Runnable onDone, Consumer<Throwable> onError) {
        String resolvedChat = resolveChatModel();
        EffectiveConnection connection = resolveConnectionForModel(resolvedChat);
        if (connection == null) {
            if (!mockEnabled) {
                onError.accept(new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "模型配置缺失：请设置 MODEL_BASE_URL 与 MODEL_API_KEY"));
                return;
            }
            logger.info("Using mock model for stream reply (baseUrlPresent={}, apiKeyPresent={})", !baseUrl.isEmpty(), !apiKey.isEmpty());
            String fullReply = buildStubReply(guessModules(sessionTitle, userMessage, projectType), userMessage);
            new Thread(() -> {
                try {
                    for (char c : fullReply.toCharArray()) {
                        onContent.accept(String.valueOf(c));
                        Thread.sleep(20);
                    }
                    onDone.run();
                } catch (Exception e) {
                    logger.error("Error in mock stream", e);
                    onError.accept(e);
                }
            }).start();
            return;
        }

        try {
            logger.info("Starting stream chat with model: {}, connectionSource={}", resolvedChat, connection.source());
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content",
                    "You are an assistant helping users clarify software requirements. " +
                            "When requirements are clear, append a final ```json``` block with keys: " +
                            "pages, title, features, coreFlows, userRoles, constraints, description, technicalRequirements, externalApiRequirements."
            ));
            if (history != null) {
                messages.addAll(history);
            }
            messages.add(Map.of("role", "user", "content", userMessage));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", resolvedChat);
            payload.put("messages", messages);
            payload.put("stream", true);
            ModelGatewayResult result = modelGatewayService.invokeStreaming(
                    new ModelGatewayInvocation(
                            ModelGatewayEndpoint.CHAT_COMPLETIONS,
                            "dashscope",
                            resolvedChat,
                            connection.baseUrl(),
                            connection.apiKey(),
                            payload,
                            "chat_stream",
                            null
                    ),
                    onContent);
            modelRouterService.recordUsage(
                    resolvedChat,
                    result.tokenInput() == null ? 0 : Math.max(0, result.tokenInput()),
                    result.tokenOutput() == null ? 0 : Math.max(0, result.tokenOutput())
            );
            onDone.run();
        } catch (Exception e) {
            logger.error("Failed to initiate stream request", e);
            onError.accept(e);
        }
    }

    public ModelResult chatReply(String sessionTitle, String projectType, String userMessage, List<Map<String, String>> history) {
        String resolvedChat = resolveChatModel();
        EffectiveConnection connection = resolveConnectionForModel(resolvedChat);
        if (connection == null) {
            if (!mockEnabled) {
                throw new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "模型配置缺失：请设置 MODEL_BASE_URL 与 MODEL_API_KEY");
            }
            List<String> modules = guessModules(sessionTitle, userMessage, projectType);
            String reply = buildStubReply(modules, userMessage);
            int inTok = estimateTokens(objectMapper.valueToTree(Map.of(
                    "title", sessionTitle,
                    "projectType", projectType,
                    "message", userMessage,
                    "history", history
            )));
            int outTok = estimateTokens(reply);
            return new ModelResult(resolvedChat, reply, modules, inTok, outTok);
        }
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", "你是智能助手，请帮助用户梳理需求并输出模块清单。"));
            if (history != null) {
                messages.addAll(history);
            }
            messages.add(Map.of("role", "user", "content", userMessage));

            Map<String, Object> payload = Map.of(
                    "model", resolvedChat,
                    "messages", messages
            );
            String response = callModelApi(payload, connection.baseUrl(), connection.apiKey());

            JsonNode root = objectMapper.readTree(response);
            String reply = root.at("/choices/0/message/content").asText("");
            int promptTokens = root.at("/usage/prompt_tokens").asInt(estimateTokens(objectMapper.valueToTree(payload)));
            int completionTokens = root.at("/usage/completion_tokens").asInt(estimateTokens(reply));
            modelRouterService.recordUsage(resolvedChat, promptTokens, completionTokens);
            List<String> modules = guessModules(sessionTitle, reply, projectType);
            return new ModelResult(resolvedChat, reply, modules, promptTokens, completionTokens);
        } catch (Exception e) {
            throw new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "模型服务调用失败");
        }
    }

    public String generateRequirement(String sessionTitle, String projectType, List<Map<String, String>> history) {
        String resolvedChat = resolveChatModel();
        EffectiveConnection connection = resolveConnectionForModel(resolvedChat);
        if (connection == null) {
            if (!mockEnabled) {
                throw new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "模型配置缺失：请设置 MODEL_BASE_URL 与 MODEL_API_KEY");
            }
            return "Mock PRD for " + sessionTitle + " (" + projectType + ")\n\nGenerated from chat history.";
        }
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", "你是一个资深产品经理。请根据以下对话历史，整理出一份详细的需求文档（PRD），包含项目目标、核心功能模块、用户角色及关键业务流程。输出格式为 Markdown。"));
            if (history != null) {
                messages.addAll(history);
            }
            messages.add(Map.of("role", "user", "content", "请根据以上对话，生成最终的需求文档。"));

            Map<String, Object> payload = Map.of(
                    "model", resolvedChat,
                    "messages", messages
            );
            String response = callModelApi(payload, connection.baseUrl(), connection.apiKey());

            JsonNode root = objectMapper.readTree(response);
            return root.at("/choices/0/message/content").asText("");
        } catch (Exception e) {
            throw new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "生成需求文档失败");
        }
    }

    public List<String> guessModules(String title, String text, String projectType) {
        String s = (title == null ? "" : title) + " " + (text == null ? "" : text);
        if (s.contains("二手") || s.contains("交易")) {
            return List.of("用户", "商品", "订单", "支付", "后台管理");
        }
        if (s.contains("博客") || s.contains("文章")) {
            return List.of("用户", "文章", "评论", "标签", "后台管理");
        }
        if (s.contains("外卖") || s.contains("点餐")) {
            return List.of("用户", "商家", "菜品", "订单", "配送");
        }
        if ("miniprogram".equalsIgnoreCase(projectType) || s.contains("小程序")) {
            return List.of("用户", "核心业务", "小程序端", "管理后台", "数据统计");
        }
        if ("app".equalsIgnoreCase(projectType) || s.contains("APP")) {
            return List.of("用户", "核心业务", "移动端", "管理后台", "数据统计");
        }
        return List.of("用户", "核心业务", "管理后台");
    }

    public List<String> generateProjectStructure(String prd, String stackBackend, String stackFrontend, String stackDb, String instructions) {
        return generateProjectStructure(null, null, prd, stackBackend, stackFrontend, stackDb, instructions);
    }

    public List<String> generateProjectStructure(String taskId, String projectId, String prd, String stackBackend, String stackFrontend, String stackDb, String instructions) {
        if (baseUrl.isEmpty()) {
            return List.of("README.md", "backend/src/main/java/App.java", "frontend/package.json");
        }
        long start = System.currentTimeMillis();
        String templateKey = "project_structure_plan";
        int versionNo = 1;
        String modelName = resolveCodeModel();
        String requestJson = null;
        String requestHash = null;
        try {
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("prd", prd);
            vars.put("stackBackend", stackBackend);
            vars.put("stackFrontend", stackFrontend);
            vars.put("stackDb", stackDb);
            vars.put("instructions", instructions == null ? "" : instructions);

            String defaultSystemPrompt =
                    "你是一个资深架构师。请根据以下PRD和技术栈，规划项目的完整文件结构。\n" +
                    "技术栈：后端 {{stackBackend}}，前端 {{stackFrontend}}，数据库 {{stackDb}}。\n" +
                    "请输出一个JSON数组，包含所有需要生成的文件路径（相对路径）。\n" +
                    "例如：[\"README.md\", \"backend/pom.xml\", \"frontend/package.json\", ...]\n" +
                    "请确保结构合理，包含必要的配置文件、代码文件和部署文件（Dockerfile, docker-compose.yml）。\n" +
                    "只输出JSON数组，不要包含Markdown标记或其他文字。";
            String defaultUserPrompt = "PRD内容：\n{{prd}}\n\n额外指令：\n{{instructions}}";

            PromptResolver.ResolvedPrompt resolvedPrompt = promptResolver.resolve(templateKey).orElse(null);
            String systemPrompt =
                    "你是经验丰富的全栈工程师。请根据 PRD、技术栈和目标文件路径生成完整文件内容。\n" +
                    "文件路径：{{filePath}}\n" +
                    "技术栈：{{techStack}}\n" +
                    "要求：\n" +
                    "1. 输出完整可运行实现，不要只写骨架、TODO、占位符或伪代码。\n" +
                    "2. 把 PRD 中的业务对象、字段、流程和约束真正落实到代码里。\n" +
                    "3. 代码要优先保证可读性：命名清晰、层次分明、控制流程直观。\n" +
                    "4. 只在复杂逻辑、边界条件、关键业务规则前添加必要注释，避免逐行废话式注释。\n" +
                    "5. 如果是接口层，要体现参数校验、错误处理和返回结构；如果是数据层，要体现字段、约束和必要关系。\n" +
                    "6. 直接输出文件内容，不要输出 Markdown 代码块，除非目标文件本身就是 Markdown。\n" +
                    "项目文件结构（{{currentGroup}}组）：\n{{projectStructure}}\n" +
                    "如果目标文件是代码文件，请确保结果可以直接编译或运行。";
            String userPrompt =
                    "PRD 内容：\n{{prd}}\n\n" +
                    "额外指令：\n{{instructions}}\n\n" +
                    "输出要求：\n" +
                    "1. 至少体现 2 个明确的业务字段、业务规则或业务状态。\n" +
                    "2. 对不直观的实现补充简洁注释，帮助后续维护者快速理解意图。\n" +
                    "3. 对简单赋值、简单 getter/setter、显而易见的框架样板不要滥加注释。\n" +
                    "4. 如果是接口层，补齐参数校验、错误分支和合理返回。\n" +
                    "5. 如果是数据层，补齐实体字段、约束、关系或迁移语义。\n" +
                    "6. 不要输出空文件或只有脚手架意味的模板代码。";
            if (resolvedPrompt != null) {
                versionNo = resolvedPrompt.version().getVersionNo();
                if (resolvedPrompt.version().getModel() != null && !resolvedPrompt.version().getModel().isBlank()) {
                    modelName = resolveCodeModel(resolvedPrompt.version().getModel());
                }
                if (resolvedPrompt.version().getSystemPrompt() != null && !resolvedPrompt.version().getSystemPrompt().isBlank()) {
                    systemPrompt = resolvedPrompt.version().getSystemPrompt();
                }
                if (resolvedPrompt.version().getUserPrompt() != null && !resolvedPrompt.version().getUserPrompt().isBlank()) {
                    userPrompt = resolvedPrompt.version().getUserPrompt();
                }
            }

            String globalRules = resolveGlobalRulesPrefix();
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", globalRules + promptRenderer.render(systemPrompt, vars)));
            messages.add(Map.of("role", "user", "content", promptRenderer.render(userPrompt, vars)));

            String correctivePrompt = null;
            for (int attempt = 0; attempt <= correctiveRetryMax; attempt++) {
                List<Map<String, String>> attemptMessages = new ArrayList<>(messages);
                if (correctivePrompt != null) {
                    attemptMessages.add(Map.of("role", "user", "content", correctivePrompt));
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("model", modelName);
                payload.put("messages", attemptMessages);
                if (schemaValidationEnabled) {
                    Map<String, Object> responseFormat = outputSchemaValidator.buildResponseFormat(templateKey);
                    if (responseFormat != null) {
                        payload.put("response_format", responseFormat);
                    }
                }
                requestJson = objectMapper.writeValueAsString(payload);
                requestHash = hash(requestJson);
                String response = callModelApi(payload);
                JsonNode root = objectMapper.readTree(response);
                String content = cleanupJsonContent(root.at("/choices/0/message/content").asText(""));
                JsonNode parsedNode = objectMapper.readTree(content);
                OutputSchemaValidator.ValidationResult validationResult = schemaValidationEnabled
                        ? outputSchemaValidator.validate(templateKey, parsedNode)
                        : OutputSchemaValidator.ValidationResult.skippedResult();
                if (validationResult.passed()) {
                    List<String> result = objectMapper.convertValue(parsedNode, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                    savePromptHistory(taskId, projectId, templateKey, versionNo, modelName, requestHash, requestJson, response, estimateTokens(requestJson), estimateTokens(response), (int) (System.currentTimeMillis() - start), "success", null, null);
                    return result;
                }
                if (attempt >= correctiveRetryMax) {
                    savePromptHistory(taskId, projectId, templateKey, versionNo, modelName, requestHash, requestJson, response, estimateTokens(requestJson), estimateTokens(response), (int) (System.currentTimeMillis() - start), "failed", String.valueOf(ErrorCodes.MODEL_OUTPUT_SCHEMA_VIOLATION), String.join("; ", validationResult.errors()));
                    throw new BusinessException(ErrorCodes.MODEL_OUTPUT_SCHEMA_VIOLATION, "模型输出不符合结构约束");
                }
                correctivePrompt = outputSchemaValidator.buildCorrectivePrompt(content, validationResult.errors());
                logger.warn("Project structure schema validation failed, corrective retry: taskId={}, attempt={}/{}, errors={}",
                        taskId, attempt + 1, correctiveRetryMax + 1, validationResult.errors());
            }
            throw new BusinessException(ErrorCodes.MODEL_OUTPUT_SCHEMA_VIOLATION, "模型输出不符合结构约束");
        } catch (BusinessException e) {
            if (e.getCode() != ErrorCodes.MODEL_OUTPUT_SCHEMA_VIOLATION) {
                savePromptHistory(
                        taskId,
                        projectId,
                        templateKey,
                        versionNo,
                        modelName,
                        requestHash,
                        requestJson,
                        null,
                        estimateTokens(requestJson),
                        0,
                        (int) (System.currentTimeMillis() - start),
                        "failed",
                        String.valueOf(e.getCode()),
                        e.getMessage()
                );
            }
            logger.error(
                    "Project structure model call failed: taskId={}, projectId={}, model={}, requestHash={}, requestPreview={}",
                    taskId,
                    projectId,
                    modelName,
                    requestHash,
                    compactForLog(requestJson, 1200),
                    e
            );
            throw e;
        } catch (Exception e) {
            logger.error(
                    "Failed to generate project structure: taskId={}, projectId={}, model={}, requestHash={}, requestPreview={}",
                    taskId,
                    projectId,
                    modelName,
                    requestHash,
                    compactForLog(requestJson, 1200),
                    e
            );
            savePromptHistory(taskId, projectId, templateKey, versionNo, modelName, requestHash, requestJson, null, estimateTokens(requestJson), 0, (int) (System.currentTimeMillis() - start), "failed", String.valueOf(ErrorCodes.MODEL_SERVICE_ERROR), detailMessage(e));
            throw new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "生成项目结构失败");
        }
    }

    public String generateFileContent(String prd, String filePath, String techStack, String instructions) {
        return generateFileContent(null, null, prd, filePath, techStack, instructions, null);
    }

    public String generateFileContent(String taskId, String projectId, String prd, String filePath, String techStack, String instructions) {
        return generateFileContent(taskId, projectId, prd, filePath, techStack, instructions, null);
    }

    public String generateFileContent(String taskId,
                                      String projectId,
                                      String prd,
                                      String filePath,
                                      String techStack,
                                      String instructions,
                                      String projectStructure) {
        long start = System.currentTimeMillis();
        String templateKey = "file_content_generate";
        int versionNo = 1;
        String modelName = resolveCodeModel();
        if (resolveConnectionForModel(modelName) == null) {
            return "// Mock content for " + filePath;
        }
        String requestJson = null;
        String requestHash = null;
        try {
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("prd", prd);
            vars.put("filePath", filePath);
            vars.put("techStack", techStack);
            vars.put("instructions", instructions == null ? "" : instructions);
            vars.put("projectStructure", projectStructure == null ? "" : projectStructure);
            vars.put("currentGroup", detectGroupByPath(filePath));

            String defaultSystemPrompt =
                    "你是一个全栈工程师。请根据PRD和技术栈，生成指定文件的完整代码内容。\n" +
                    "文件路径：{{filePath}}\n" +
                    "技术栈：{{techStack}}\n" +
                    "必须将 PRD 中的业务对象、关键流程、约束规则落实到代码中，禁止仅输出空壳框架、占位 TODO 或示例模板。\n" +
                    "如果目标是 Controller/Service/Repository/Entity/Page/Store/SQL 等业务文件，必须包含具体业务字段、接口、校验或流程实现。\n" +
                    "请直接输出文件内容，不要包含Markdown标记（如 ```java ... ```），除非文件本身是Markdown格式。\n" +
                    "项目文件结构（{{currentGroup}}组）：\n{{projectStructure}}\n" +
                    "如果文件是代码，请确保可以直接运行或编译。";
            String defaultUserPrompt = "PRD内容：\n{{prd}}\n\n额外指令：\n{{instructions}}\n\n输出要求：\n1) 必须体现至少2个业务字段或业务规则；\n2) 如果是接口层需包含参数校验与错误处理；\n3) 如果是数据层需体现实体字段与约束；\n4) 不能只输出项目脚手架样例。";

            PromptResolver.ResolvedPrompt resolvedPrompt = promptResolver.resolve(templateKey).orElse(null);
            String systemPrompt =
                    "你是经验丰富的全栈工程师。请基于下面的模板示例代码生成目标文件，并保持项目内风格一致。\n" +
                    "你需要继承示例中的：目录组织、命名风格、依赖使用方式、异常处理模式、DTO/Schema 设计以及 API 调用习惯。\n" +
                    "同时请确保生成结果更易读：\n" +
                    "1. 结构清晰，先给出核心入口，再展开细节实现。\n" +
                    "2. 复杂分支、关键业务规则和非直观实现前补充简洁注释。\n" +
                    "3. 不要为了凑注释而解释显而易见的代码。\n\n" +
                    "=== 模板示例代码（主文件）===\n{{exampleCode}}\n\n" +
                    "=== 模板相关文件 ===\n{{relatedExamples}}\n\n" +
                    "文件路径：{{filePath}}\n" +
                    "技术栈：{{techStack}}\n" +
                    "项目文件结构（{{currentGroup}}组）：\n{{projectStructure}}\n\n" +
                    "直接输出文件内容，不要输出 Markdown 代码块，除非目标文件本身就是 Markdown。\n" +
                    "确保最终代码可以直接编译或运行，并与示例的 import、组织方式和工程风格保持一致。";
            String userPrompt =
                    "PRD 内容：\n{{prd}}\n\n" +
                    "额外指令：\n{{instructions}}\n\n" +
                    "输出要求：\n" +
                    "1. 至少体现 2 个明确的业务字段、业务规则或业务状态。\n" +
                    "2. 如果是接口层，补齐参数校验、错误处理和返回结构。\n" +
                    "3. 如果是数据层，补齐字段、约束、关系或迁移语义。\n" +
                    "4. 严格参考模板示例的代码风格和组织结构，但不要机械复制无关业务。\n" +
                    "5. 对关键规则和不直观实现补充必要注释，帮助维护者快速阅读。\n" +
                    "6. 不要输出空实现、脚手架占位代码或只有函数签名的文件。";
            if (resolvedPrompt != null) {
                versionNo = resolvedPrompt.version().getVersionNo();
                if (resolvedPrompt.version().getModel() != null && !resolvedPrompt.version().getModel().isBlank()) {
                    modelName = resolveCodeModel(resolvedPrompt.version().getModel());
                }
                if (resolvedPrompt.version().getSystemPrompt() != null && !resolvedPrompt.version().getSystemPrompt().isBlank()) {
                    systemPrompt = resolvedPrompt.version().getSystemPrompt();
                }
                if (resolvedPrompt.version().getUserPrompt() != null && !resolvedPrompt.version().getUserPrompt().isBlank()) {
                    userPrompt = resolvedPrompt.version().getUserPrompt();
                }
            }

            String globalRules = resolveGlobalRulesPrefix();
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", globalRules + promptRenderer.render(systemPrompt, vars)));
            messages.add(Map.of("role", "user", "content", promptRenderer.render(userPrompt, vars)));

            Map<String, Object> payload = Map.of(
                    "model", modelName,
                    "messages", messages
            );
            requestJson = objectMapper.writeValueAsString(payload);
            requestHash = hash(requestJson);
            String response = callModelApi(payload);
            JsonNode root = objectMapper.readTree(response);
            String content = root.at("/choices/0/message/content").asText("");

            // Clean up markdown code blocks if present (sometimes models add them despite instructions)
            if (content.startsWith("```") && content.endsWith("```")) {
                int firstLineBreak = content.indexOf("\n");
                if (firstLineBreak != -1) {
                    content = content.substring(firstLineBreak + 1, content.lastIndexOf("```"));
                }
            }
            savePromptHistory(taskId, projectId, templateKey, versionNo, modelName, requestHash, requestJson, response, estimateTokens(requestJson), estimateTokens(response), (int) (System.currentTimeMillis() - start), "success", null, null);
            return content;
        } catch (Exception e) {
            logger.error("Failed to generate file content for " + filePath, e);
            String detail = detailMessage(e);
            savePromptHistory(taskId, projectId, templateKey, versionNo, modelName, requestHash, requestJson, null, estimateTokens(requestJson), 0, (int) (System.currentTimeMillis() - start), "failed", String.valueOf(ErrorCodes.MODEL_SERVICE_ERROR), detail);
            return "// Failed to generate content: " + detail;
        }
    }

    /**
     * Generate file content with template example code injected into the prompt.
     * The LLM is instructed to follow the template's coding patterns.
     */
    public String generateFileContentWithTemplate(String taskId,
                                                   String projectId,
                                                   String prd,
                                                   String filePath,
                                                   String techStack,
                                                   String instructions,
                                                   String projectStructure,
                                                   String exampleCode,
                                                   String relatedExamples) {
        long start = System.currentTimeMillis();
        String templateKey = "file_content_generate_with_template";
        int versionNo = 1;
        String modelName = resolveCodeModel();
        if (resolveConnectionForModel(modelName) == null) {
            return "// Mock content for " + filePath;
        }
        String requestJson = null;
        String requestHash = null;
        try {
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("prd", prd);
            vars.put("filePath", filePath);
            vars.put("techStack", techStack);
            vars.put("instructions", instructions == null ? "" : instructions);
            vars.put("projectStructure", projectStructure == null ? "" : projectStructure);
            vars.put("currentGroup", detectGroupByPath(filePath));
            vars.put("exampleCode", exampleCode == null ? "" : exampleCode);
            vars.put("relatedExamples", relatedExamples == null ? "" : relatedExamples);

            String defaultSystemPrompt =
                    "你是一个全栈工程师。请基于以下项目模板的示例代码，生成指定文件的完整业务代码。\n" +
                    "你必须严格遵循示例代码中的：\n" +
                    "1. 包结构和 import 路径风格\n" +
                    "2. 注解使用方式（@RestController, @Service, @Entity 等）\n" +
                    "3. 异常处理模式（@ExceptionHandler 风格）\n" +
                    "4. 请求/响应 DTO 模式（record + validation 注解）\n" +
                    "5. Repository 接口继承方式\n" +
                    "6. 前端组件结构、API 调用模式、TypeScript 类型定义\n\n" +
                    "=== 模板示例代码（主文件）===\n{{exampleCode}}\n\n" +
                    "=== 模板相关文件 ===\n{{relatedExamples}}\n\n" +
                    "文件路径：{{filePath}}\n" +
                    "技术栈：{{techStack}}\n" +
                    "项目文件结构（{{currentGroup}}组）：\n{{projectStructure}}\n\n" +
                    "请直接输出文件内容，不要包含Markdown标记（如 ```java ... ```），除非文件本身是Markdown格式。\n" +
                    "确保代码可以直接编译运行，import 路径与示例代码保持一致的风格。";

            String defaultUserPrompt =
                    "PRD内容：\n{{prd}}\n\n" +
                    "额外指令：\n{{instructions}}\n\n" +
                    "输出要求：\n" +
                    "1) 必须体现至少2个业务字段或业务规则；\n" +
                    "2) 如果是接口层需包含参数校验与错误处理；\n" +
                    "3) 如果是数据层需体现实体字段与约束；\n" +
                    "4) 严格参考模板示例的代码风格和结构模式，保持一致性；\n" +
                    "5) 不能只输出项目脚手架样例。";

            PromptResolver.ResolvedPrompt resolvedPrompt = promptResolver.resolve(templateKey).orElse(null);
            String systemPrompt = defaultSystemPrompt;
            String userPrompt = defaultUserPrompt;
            if (resolvedPrompt != null) {
                versionNo = resolvedPrompt.version().getVersionNo();
                if (resolvedPrompt.version().getModel() != null && !resolvedPrompt.version().getModel().isBlank()) {
                    modelName = resolveCodeModel(resolvedPrompt.version().getModel());
                }
                if (resolvedPrompt.version().getSystemPrompt() != null && !resolvedPrompt.version().getSystemPrompt().isBlank()) {
                    systemPrompt = resolvedPrompt.version().getSystemPrompt();
                }
                if (resolvedPrompt.version().getUserPrompt() != null && !resolvedPrompt.version().getUserPrompt().isBlank()) {
                    userPrompt = resolvedPrompt.version().getUserPrompt();
                }
            }

            String globalRules = resolveGlobalRulesPrefix();
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", globalRules + promptRenderer.render(systemPrompt, vars)));
            messages.add(Map.of("role", "user", "content", promptRenderer.render(userPrompt, vars)));

            Map<String, Object> payload = Map.of(
                    "model", modelName,
                    "messages", messages
            );
            requestJson = objectMapper.writeValueAsString(payload);
            requestHash = hash(requestJson);
            String response = callModelApi(payload);
            JsonNode root = objectMapper.readTree(response);
            String content = root.at("/choices/0/message/content").asText("");

            if (content.startsWith("```") && content.endsWith("```")) {
                int firstLineBreak = content.indexOf("\n");
                if (firstLineBreak != -1) {
                    content = content.substring(firstLineBreak + 1, content.lastIndexOf("```"));
                }
            }
            savePromptHistory(taskId, projectId, templateKey, versionNo, modelName, requestHash, requestJson, response, estimateTokens(requestJson), estimateTokens(response), (int) (System.currentTimeMillis() - start), "success", null, null);
            return content;
        } catch (Exception e) {
            logger.error("Failed to generate file content (template-aware) for " + filePath, e);
            String detail = detailMessage(e);
            savePromptHistory(taskId, projectId, templateKey, versionNo, modelName, requestHash, requestJson, null, estimateTokens(requestJson), 0, (int) (System.currentTimeMillis() - start), "failed", String.valueOf(ErrorCodes.MODEL_SERVICE_ERROR), detail);
            return "// Failed to generate content: " + detail;
        }
    }

    /**
     * Fix a compilation error by sending the file content and error output to LLM.
     * Returns the corrected file content, or null if fix fails.
     */
    public String fixCompilationError(String taskId,
                                       String projectId,
                                       String filePath,
                                       String currentContent,
                                       String compilationError,
                                       String techStack) {
        long start = System.currentTimeMillis();
        String templateKey = "compilation_error_fix";
        int versionNo = 1;
        String modelName = resolveCodeModel();
        if (resolveConnectionForModel(modelName) == null) {
            return null;
        }
        String requestJson = null;
        String requestHash = null;
        try {
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("filePath", filePath);
            vars.put("currentContent", currentContent);
            vars.put("compilationError", compilationError);
            vars.put("techStack", techStack);

            String systemPrompt =
                    "你是一个全栈工程师。以下文件编译/构建失败，请修复并返回完整的修正代码。\n\n" +
                    "文件路径：{{filePath}}\n" +
                    "技术栈：{{techStack}}\n\n" +
                    "修复规则：\n" +
                    "1. 只修复编译错误，不要改变业务逻辑\n" +
                    "2. 确保 import 路径正确\n" +
                    "3. 确保类型匹配和方法签名正确\n" +
                    "4. 直接输出修正后的完整文件内容，不要包含 Markdown 标记";

            String userPrompt =
                    "编译错误信息：\n{{compilationError}}\n\n" +
                    "当前文件内容：\n{{currentContent}}\n\n" +
                    "请输出修正后的完整文件内容。";

            PromptResolver.ResolvedPrompt resolvedPrompt = promptResolver.resolve(templateKey).orElse(null);
            if (resolvedPrompt != null) {
                versionNo = resolvedPrompt.version().getVersionNo();
                if (resolvedPrompt.version().getModel() != null && !resolvedPrompt.version().getModel().isBlank()) {
                    modelName = resolveCodeModel(resolvedPrompt.version().getModel());
                }
                if (resolvedPrompt.version().getSystemPrompt() != null && !resolvedPrompt.version().getSystemPrompt().isBlank()) {
                    systemPrompt = resolvedPrompt.version().getSystemPrompt();
                }
                if (resolvedPrompt.version().getUserPrompt() != null && !resolvedPrompt.version().getUserPrompt().isBlank()) {
                    userPrompt = resolvedPrompt.version().getUserPrompt();
                }
            }

            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", promptRenderer.render(systemPrompt, vars)));
            messages.add(Map.of("role", "user", "content", promptRenderer.render(userPrompt, vars)));

            Map<String, Object> payload = Map.of("model", modelName, "messages", messages);
            requestJson = objectMapper.writeValueAsString(payload);
            requestHash = hash(requestJson);
            String response = callModelApi(payload);
            JsonNode root = objectMapper.readTree(response);
            String content = root.at("/choices/0/message/content").asText("");

            if (content.startsWith("```") && content.endsWith("```")) {
                int firstLineBreak = content.indexOf("\n");
                if (firstLineBreak != -1) {
                    content = content.substring(firstLineBreak + 1, content.lastIndexOf("```"));
                }
            }
            savePromptHistory(taskId, projectId, templateKey, versionNo, modelName, requestHash, requestJson, response, estimateTokens(requestJson), estimateTokens(response), (int) (System.currentTimeMillis() - start), "success", null, null);
            return content;
        } catch (Exception e) {
            logger.error("Failed to fix compilation error for " + filePath, e);
            String detail = detailMessage(e);
            savePromptHistory(taskId, projectId, templateKey, versionNo, modelName, requestHash, requestJson, null, estimateTokens(requestJson), 0, (int) (System.currentTimeMillis() - start), "failed", String.valueOf(ErrorCodes.MODEL_SERVICE_ERROR), detail);
            return null;
        }
    }

    private String detectGroupByPath(String filePath) {
        String p = filePath == null ? "" : filePath.toLowerCase();
        if (p.contains("backend/")) return "backend";
        if (p.contains("frontend/")) return "frontend";
        if (p.endsWith(".sql") || p.contains("/db/") || p.contains("database")) return "database";
        if (p.contains("docker") || p.contains(".yml") || p.startsWith("scripts/") || p.endsWith(".sh") || p.endsWith(".bat")) return "infra";
        if (p.startsWith("docs/") || p.endsWith("readme.md")) return "docs";
        return "backend";
    }

    public JsonNode clarifyPaperTopic(String taskId,
                                      String projectId,
                                      String topic,
                                      String discipline,
                                      String degreeLevel,
                                      String methodPreference) {
        String modelName = resolvePaperModel();
        if (resolveConnectionForModel(modelName) == null) {
            return objectMapper.valueToTree(Map.of(
                    "topicRefined", topic,
                    "researchQuestions", List.of("核心研究问题是什么", "可行的研究方法是什么", "如何验证研究结论")
            ));
        }
        long start = System.currentTimeMillis();
        String templateKey = "paper_topic_clarify";
        int versionNo = 1;
        try {
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("topic", topic);
            vars.put("discipline", discipline);
            vars.put("degreeLevel", degreeLevel);
            vars.put("methodPreference", methodPreference == null ? "" : methodPreference);
            String defaultSystemPrompt = "你是论文导师助手。请将输入主题细化为可执行毕业论文题目，并输出JSON：{topicRefined:string,researchQuestions:string[]}。";
            String defaultUserPrompt = "主题：{{topic}}\n学科：{{discipline}}\n学位层次：{{degreeLevel}}\n方法偏好：{{methodPreference}}";
            JsonNode result = runPromptForJson(taskId, projectId, templateKey, versionNo, modelName, vars, defaultSystemPrompt, defaultUserPrompt, start);
            if (!result.has("topicRefined")) {
                return objectMapper.valueToTree(Map.of(
                        "topicRefined", topic,
                        "researchQuestions", List.of("核心研究问题是什么", "可行的研究方法是什么", "如何验证研究结论")
                ));
            }
            return result;
        } catch (Exception e) {
            logger.error("Failed to clarify paper topic", e);
            return objectMapper.valueToTree(Map.of(
                    "topicRefined", topic,
                    "researchQuestions", List.of("核心研究问题是什么", "可行的研究方法是什么", "如何验证研究结论")
            ));
        }
    }

    public JsonNode generatePaperOutline(String taskId,
                                         String projectId,
                                         String topic,
                                         String topicRefined,
                                         String discipline,
                                         String degreeLevel,
                                         String methodPreference,
                                         String researchQuestionsJson,
                                         JsonNode sources,
                                         JsonNode ragEvidence) {
        String modelName = resolvePaperModel();
        if (resolveConnectionForModel(modelName) == null) {
            return objectMapper.valueToTree(Map.of(
                    "researchQuestions", List.of("研究问题1", "研究问题2"),
                    "chapters", List.of(
                            Map.of("title", "绪论", "sections", List.of(
                                    Map.of("title", "研究背景", "subsections", List.of(
                                            Map.of("title", "问题提出", "evidence", List.of())
                                    ), "evidenceMapping", List.of())
                            ))
                    ),
                    "references", List.of()
            ));
        }
        long start = System.currentTimeMillis();
        String templateKey = "paper_outline_generate";
        int versionNo = ragEvidence != null ? 2 : 1;
        try {
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("topic", topic);
            vars.put("topicRefined", topicRefined == null ? "" : topicRefined);
            vars.put("discipline", discipline);
            vars.put("degreeLevel", degreeLevel);
            vars.put("methodPreference", methodPreference == null ? "" : methodPreference);
            vars.put("researchQuestions", researchQuestionsJson == null ? "[]" : researchQuestionsJson);
            vars.put("sources", sources == null ? "[]" : objectMapper.writeValueAsString(sources));
            vars.put("ragEvidence", ragEvidence == null ? "[]" : objectMapper.writeValueAsString(ragEvidence));
            String defaultSystemPrompt = "你是毕业论文结构专家。请输出JSON对象，包含researchQuestions、chapters、references。chapters需为章-节-小节三级结构，每个小节提供3条evidence，evidence包含paperId/title/url。每个section需包含evidenceMapping数组，其中每个元素包含chunkUid、title和relevance（0-1之间的相关度评分）。";
            String defaultUserPrompt = "主题：{{topic}}\n细化题目：{{topicRefined}}\n学科：{{discipline}}\n学位层次：{{degreeLevel}}\n方法偏好：{{methodPreference}}\n研究问题：{{researchQuestions}}\n候选文献：{{sources}}\nRAG证据：{{ragEvidence}}";
            return runPromptForJson(taskId, projectId, templateKey, versionNo, modelName, vars, defaultSystemPrompt, defaultUserPrompt, start);
        } catch (Exception e) {
            logger.error("Failed to generate paper outline", e);
            return objectMapper.valueToTree(Map.of(
                    "researchQuestions", List.of(),
                    "chapters", List.of(),
                    "references", List.of()
            ));
        }
    }

    public JsonNode qualityCheckPaperOutline(String taskId,
                                             String projectId,
                                             String topic,
                                             String topicRefined,
                                             String citationStyle,
                                             JsonNode outlineJson,
                                             JsonNode ragEvidence,
                                             String chapterEvidenceMapJson) {
        String modelName = resolvePaperModel();
        if (resolveConnectionForModel(modelName) == null) {
            return normalizeQualityReport(objectMapper.valueToTree(Map.of(
                    "logicClosedLoop", true,
                    "methodConsistency", "ok",
                    "citationVerifiability", "ok",
                    "overallScore", 85,
                    "evidenceCoverage", 80,
                    "uncoveredSections", List.of(),
                    "issues", List.of()
            )));
        }
        long start = System.currentTimeMillis();
        String templateKey = "paper_outline_quality_check";
        int versionNo = ragEvidence != null ? 2 : 1;
        try {
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("topic", topic);
            vars.put("topicRefined", topicRefined == null ? "" : topicRefined);
            vars.put("citationStyle", citationStyle == null ? "GB/T 7714" : citationStyle);
            vars.put("outlineJson", outlineJson == null ? "{}" : objectMapper.writeValueAsString(outlineJson));
            vars.put("ragEvidence", ragEvidence == null ? "[]" : objectMapper.writeValueAsString(ragEvidence));
            vars.put("chapterEvidenceMap", chapterEvidenceMapJson == null ? "{}" : chapterEvidenceMapJson);
            String defaultSystemPrompt = "你是论文质量审查助手。请对大纲进行质检并输出JSON：logicClosedLoop,methodConsistency,citationVerifiability,overallScore,issues,evidenceCoverage(0-100),uncoveredSections(未被证据覆盖的章节标题数组)。";
            String defaultUserPrompt = "主题：{{topic}}\n细化题目：{{topicRefined}}\n引文样式：{{citationStyle}}\n大纲：{{outlineJson}}\nRAG证据：{{ragEvidence}}\n章节证据映射：{{chapterEvidenceMap}}";
            JsonNode raw = runPromptForJson(taskId, projectId, templateKey, versionNo, modelName, vars, defaultSystemPrompt, defaultUserPrompt, start);
            return normalizeQualityReport(raw);
        } catch (Exception e) {
            logger.error("Failed to quality check paper outline", e);
            return normalizeQualityReport(objectMapper.valueToTree(Map.of(
                    "logicClosedLoop", false,
                    "methodConsistency", "unknown",
                    "citationVerifiability", "unknown",
                    "overallScore", 60,
                    "evidenceCoverage", 0,
                    "uncoveredSections", List.of(),
                    "issues", List.of("质检失败")
            )));
        }
    }

    public JsonNode expandPaperOutline(String taskId,
                                       String projectId,
                                       String topic,
                                       String topicRefined,
                                       String discipline,
                                       String degreeLevel,
                                       String methodPreference,
                                       String researchQuestionsJson,
                                       JsonNode outlineJson,
                                       JsonNode sources) {
        return expandPaperOutline(taskId, projectId, topic, topicRefined, discipline, degreeLevel,
                methodPreference, researchQuestionsJson, outlineJson, sources, null);
    }

    public JsonNode expandPaperOutline(String taskId,
                                       String projectId,
                                       String topic,
                                       String topicRefined,
                                       String discipline,
                                       String degreeLevel,
                                       String methodPreference,
                                       String researchQuestionsJson,
                                       JsonNode outlineJson,
                                       JsonNode sources,
                                       JsonNode ragEvidence) {
        long start = System.currentTimeMillis();
        String templateKey = "paper_outline_expand";
        int versionNo = ragEvidence != null ? 3 : 1;
        String modelName = resolvePaperModel();
        if (resolveConnectionForModel(modelName) == null) {
            return fallbackExpandedManuscript(topic, topicRefined, researchQuestionsJson, outlineJson);
        }
        try {
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("topic", topic);
            vars.put("topicRefined", topicRefined == null ? "" : topicRefined);
            vars.put("discipline", discipline == null ? "" : discipline);
            vars.put("degreeLevel", degreeLevel == null ? "" : degreeLevel);
            vars.put("methodPreference", methodPreference == null ? "" : methodPreference);
            vars.put("researchQuestions", researchQuestionsJson == null ? "[]" : researchQuestionsJson);
            vars.put("outlineJson", outlineJson == null ? "{}" : objectMapper.writeValueAsString(outlineJson));
            vars.put("sources", sources == null ? "[]" : objectMapper.writeValueAsString(sources));
            vars.put("ragEvidence", ragEvidence == null ? "[]" : objectMapper.writeValueAsString(ragEvidence));
            String defaultSystemPrompt = "你是论文写作助手。必须仅输出合法 JSON，禁止输出任何占位文本（如 placeholder/TBD/待补充/暂无正文）。每个章节都要有 sections；每个 section 必须有可读的中文 content 与 coreArgument。每章的 sections 数量必须与输入大纲中该章的 sections 数量严格一致，逐一扩写每个 section，不得合并或省略。若证据不足，给出保守但完整的学术表述并标注不确定性。";
            String defaultUserPrompt = "主题：{{topic}}\n细化题目：{{topicRefined}}\n学科：{{discipline}}\n学位层次：{{degreeLevel}}\n方法偏好：{{methodPreference}}\n研究问题：{{researchQuestions}}\n大纲：{{outlineJson}}\n候选文献：{{sources}}\nRAG证据：{{ragEvidence}}\n输出要求：\n1）返回 JSON：{topic,topicRefined,researchQuestions,chapters:[{index,title,summary,objective,sections:[{title,content,coreArgument,method,dataPlan,expectedResult,citations[]}]}],citationMap[]}\n2）content/coreArgument 必须是完整中文论述，不得为空且不得出现 placeholder 文本。\n3）chapters 与 sections 数量必须与输入大纲严格一致，每个 section 逐一扩写。\n4）citations 只能引用 citationMap 中存在的整数索引。";
            JsonNode result = runPromptForJson(taskId, projectId, templateKey, versionNo, modelName, vars, defaultSystemPrompt, defaultUserPrompt, start);
            if (!result.has("chapters")) {
                return fallbackExpandedManuscript(topic, topicRefined, researchQuestionsJson, outlineJson);
            }
            return result;
        } catch (Exception e) {
            logger.error("Failed to expand paper outline", e);
            return fallbackExpandedManuscript(topic, topicRefined, researchQuestionsJson, outlineJson);
        }
    }

    public JsonNode expandPaperOutlineBatch(String taskId,
                                            String projectId,
                                            String topic,
                                            String topicRefined,
                                            String discipline,
                                            String degreeLevel,
                                            String methodPreference,
                                            String researchQuestionsJson,
                                            JsonNode batchOutlineJson,
                                            JsonNode batchEvidence,
                                            String batchRange,
                                            int totalChapters) {
        long start = System.currentTimeMillis();
        String templateKey = "paper_outline_expand_batch";
        int versionNo = 1;
        String modelName = resolvePaperModel();
        if (resolveConnectionForModel(modelName) == null) {
            return objectMapper.valueToTree(Map.of(
                    "chapters", batchOutlineJson == null ? List.of() : batchOutlineJson.path("chapters"),
                    "citationMap", List.of()
            ));
        }
        try {
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("topic", topic == null ? "" : topic);
            vars.put("topicRefined", topicRefined == null ? "" : topicRefined);
            vars.put("discipline", discipline == null ? "" : discipline);
            vars.put("degreeLevel", degreeLevel == null ? "" : degreeLevel);
            vars.put("methodPreference", methodPreference == null ? "" : methodPreference);
            vars.put("researchQuestions", researchQuestionsJson == null ? "[]" : researchQuestionsJson);
            vars.put("outlineJson", batchOutlineJson == null ? "{\"chapters\":[]}" : objectMapper.writeValueAsString(batchOutlineJson));
            vars.put("ragEvidence", batchEvidence == null ? "[]" : objectMapper.writeValueAsString(batchEvidence));
            vars.put("batchRange", batchRange == null ? "" : batchRange);
            vars.put("totalChapters", String.valueOf(Math.max(0, totalChapters)));
            String defaultSystemPrompt = "你是严谨的论文写作助手。你将只扩写论文的一部分章节。必须仅输出合法 JSON，不得输出 Markdown。禁止占位文本。每章的 sections 数量必须与输入大纲中该章的 sections 数量严格一致，逐一扩写每个 section，不得合并或省略。";
            String defaultUserPrompt = "全局主题：{{topic}}\n细化题目：{{topicRefined}}\n学科：{{discipline}}\n学位层次：{{degreeLevel}}\n方法偏好：{{methodPreference}}\n研究问题：{{researchQuestions}}\n批次范围：{{batchRange}}\n总章节数：{{totalChapters}}\n本批大纲：{{outlineJson}}\n本批证据：{{ragEvidence}}\n\n重要约束：输出的每章 sections 数量必须与输入大纲中对应章节的 sections 数量完全一致。\n仅输出本批 JSON：{chapters:[...],citationMap:[...]}";
            JsonNode result = runPromptForJson(taskId, projectId, templateKey, versionNo, modelName, vars, defaultSystemPrompt, defaultUserPrompt, start);
            if (!result.has("chapters")) {
                return objectMapper.valueToTree(Map.of("chapters", List.of(), "citationMap", List.of()));
            }
            return result;
        } catch (Exception e) {
            logger.error("Failed to expand paper outline batch", e);
            return objectMapper.valueToTree(Map.of("chapters", List.of(), "citationMap", List.of()));
        }
    }

    public JsonNode rewriteOutlineByQualityIssues(String taskId,
                                                  String projectId,
                                                  String topic,
                                                  String topicRefined,
                                                  String citationStyle,
                                                  JsonNode qualityReportJson,
                                                  JsonNode manuscriptJson) {
        long start = System.currentTimeMillis();
        String templateKey = "paper_outline_quality_rewrite";
        int versionNo = 1;
        String modelName = resolvePaperModel();
        if (resolveConnectionForModel(modelName) == null) {
            return fallbackRewriteResult(manuscriptJson, qualityReportJson);
        }
        try {
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("topic", topic == null ? "" : topic);
            vars.put("topicRefined", topicRefined == null ? "" : topicRefined);
            vars.put("citationStyle", citationStyle == null ? "GB/T 7714" : citationStyle);
            vars.put("qualityReportJson", qualityReportJson == null ? "{}" : objectMapper.writeValueAsString(qualityReportJson));
            vars.put("manuscriptJson", manuscriptJson == null ? "{}" : objectMapper.writeValueAsString(manuscriptJson));
            String defaultSystemPrompt = "你是论文改写助手。请根据质检问题修订文稿，输出 JSON：{manuscript:{...},appliedIssues:string[],summary:string}。仅输出 JSON。";
            String defaultUserPrompt = "主题：{{topic}}\n细化题目：{{topicRefined}}\n引用规范：{{citationStyle}}\n质检报告：{{qualityReportJson}}\n当前文稿：{{manuscriptJson}}";
            JsonNode result = runPromptForJson(taskId, projectId, templateKey, versionNo, modelName, vars, defaultSystemPrompt, defaultUserPrompt, start);
            if (!result.has("manuscript")) {
                return fallbackRewriteResult(manuscriptJson, qualityReportJson);
            }
            return result;
        } catch (Exception e) {
            logger.error("Failed to rewrite paper manuscript by quality issues", e);
            return fallbackRewriteResult(manuscriptJson, qualityReportJson);
        }
    }

    private JsonNode runPromptForJson(String taskId,
                                      String projectId,
                                      String templateKey,
                                      int defaultVersionNo,
                                      String defaultModelName,
                                      Map<String, String> vars,
                                      String defaultSystemPrompt,
                                      String defaultUserPrompt,
                                      long start) throws Exception {
        int versionNo = defaultVersionNo;
        String modelName = defaultModelName;
        PromptResolver.ResolvedPrompt resolvedPrompt = promptResolver.resolve(templateKey).orElse(null);
        String systemPrompt = defaultSystemPrompt;
        String userPrompt = defaultUserPrompt;
        if (resolvedPrompt != null) {
            versionNo = resolvedPrompt.version().getVersionNo();
            if (resolvedPrompt.version().getModel() != null && !resolvedPrompt.version().getModel().isBlank()) {
                modelName = resolvePaperModel(resolvedPrompt.version().getModel());
            }
            if (resolvedPrompt.version().getSystemPrompt() != null && !resolvedPrompt.version().getSystemPrompt().isBlank()) {
                systemPrompt = resolvedPrompt.version().getSystemPrompt();
            }
            if (resolvedPrompt.version().getUserPrompt() != null && !resolvedPrompt.version().getUserPrompt().isBlank()) {
                userPrompt = resolvedPrompt.version().getUserPrompt();
            }
        }

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", promptRenderer.render(systemPrompt, vars)));
        messages.add(Map.of("role", "user", "content", promptRenderer.render(userPrompt, vars)));

        String correctivePrompt = null;
        String requestJson = null;
        String requestHash = null;
        String response = null;
        for (int attempt = 0; attempt <= correctiveRetryMax; attempt++) {
            List<Map<String, String>> attemptMessages = new ArrayList<>(messages);
            if (correctivePrompt != null) {
                attemptMessages.add(Map.of("role", "user", "content", correctivePrompt));
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", modelName);
            payload.put("messages", attemptMessages);
            requestJson = objectMapper.writeValueAsString(payload);
            requestHash = hash(requestJson);
            try {
                response = callModelApi(payload);
                JsonNode root = objectMapper.readTree(response);
                String content = root.at("/choices/0/message/content").asText("");
                String cleaned = cleanupJsonContent(content);
                JsonNode parsed = objectMapper.readTree(cleaned);
                OutputSchemaValidator.ValidationResult validationResult = schemaValidationEnabled
                        ? outputSchemaValidator.validate(templateKey, parsed)
                        : OutputSchemaValidator.ValidationResult.skippedResult();
                if (validationResult.passed()) {
                    savePromptHistory(taskId, projectId, templateKey, versionNo, modelName, requestHash, requestJson, response, estimateTokens(requestJson), estimateTokens(response), (int) (System.currentTimeMillis() - start), "success", null, null);
                    return parsed;
                }
                if (attempt >= correctiveRetryMax) {
                    savePromptHistory(taskId, projectId, templateKey, versionNo, modelName, requestHash, requestJson, response, estimateTokens(requestJson), estimateTokens(response), (int) (System.currentTimeMillis() - start), "failed", String.valueOf(ErrorCodes.MODEL_OUTPUT_SCHEMA_VIOLATION), String.join("; ", validationResult.errors()));
                    throw new BusinessException(ErrorCodes.MODEL_OUTPUT_SCHEMA_VIOLATION, "模型输出不符合结构约束");
                }
                correctivePrompt = outputSchemaValidator.buildCorrectivePrompt(cleaned, validationResult.errors());
                logger.warn("Prompt JSON validation failed, corrective retry: taskId={}, templateKey={}, attempt={}/{}, errors={}",
                        taskId, templateKey, attempt + 1, correctiveRetryMax + 1, validationResult.errors());
            } catch (BusinessException e) {
                if (e.getCode() == ErrorCodes.MODEL_OUTPUT_SCHEMA_VIOLATION && attempt < correctiveRetryMax) {
                    continue;
                }
                savePromptHistory(taskId, projectId, templateKey, versionNo, modelName, requestHash, requestJson, response, estimateTokens(requestJson), estimateTokens(response), (int) (System.currentTimeMillis() - start), "failed", String.valueOf(e.getCode()), e.getMessage());
                throw e;
            } catch (Exception e) {
                String errorCode = String.valueOf(ErrorCodes.MODEL_SERVICE_ERROR);
                savePromptHistory(taskId, projectId, templateKey, versionNo, modelName, requestHash, requestJson, response, estimateTokens(requestJson), estimateTokens(response), (int) (System.currentTimeMillis() - start), "failed", errorCode, e.getMessage());
                throw e;
            }
        }
        throw new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "模型调用失败");
    }

    public ConnectivityTestResult testModelConnectivity(String modelName,
                                                        String provider,
                                                        String prompt,
                                                        Integer timeoutMs,
                                                        String overrideBaseUrl,
                                                        String overrideApiKey) {
        long start = System.currentTimeMillis();
        String safeModelName = modelName == null ? "" : modelName.trim();
        String safeProvider = provider == null ? "" : provider.trim();
        if (safeModelName.isBlank()) {
            return new ConnectivityTestResult(false, safeModelName, safeProvider, 0, null, "bad_request", "modelName 不能为空", null);
        }
        String effectiveBaseUrl = overrideBaseUrl != null && !overrideBaseUrl.isBlank() ? overrideBaseUrl.trim() : baseUrl;
        String effectiveApiKey = overrideApiKey != null && !overrideApiKey.isBlank() ? overrideApiKey.trim() : apiKey;
        if (effectiveBaseUrl.isBlank() || effectiveApiKey.isBlank()) {
            return new ConnectivityTestResult(false, safeModelName, safeProvider, 0, null, "auth", "模型配置缺失：请设置可用的 baseUrl 与 apiKey", null);
        }
        String testPrompt = (prompt == null || prompt.isBlank()) ? "请回复OK" : prompt;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", safeModelName);
        payload.put("messages", List.of(Map.of("role", "user", "content", testPrompt)));
        payload.put("stream", false);
        payload.put("max_tokens", 64);
        if (timeoutMs != null && timeoutMs > 0) {
            payload.put("timeout_ms", timeoutMs);
        }
        try {
            String response = callModelApi(payload, effectiveBaseUrl, effectiveApiKey);
            JsonNode root = objectMapper.readTree(response);
            String content = root.at("/choices/0/message/content").asText("");
            long latency = System.currentTimeMillis() - start;
            return new ConnectivityTestResult(true, safeModelName, safeProvider, latency, truncate(content, 200), null, null, null);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            String message = detailMessage(e);
            Integer httpStatus = extractHttpStatus(message);
            String errorType = classifyErrorType(message, httpStatus);
            return new ConnectivityTestResult(false, safeModelName, safeProvider, latency, null, errorType, message, httpStatus);
        }
    }

    private String cleanupJsonContent(String content) {
        if (content == null) {
            return "{}";
        }
        String cleaned = content.trim();
        if (cleaned.contains("```json")) {
            cleaned = cleaned.substring(cleaned.indexOf("```json") + 7);
            if (cleaned.contains("```")) {
                cleaned = cleaned.substring(0, cleaned.indexOf("```"));
            }
        } else if (cleaned.startsWith("```")) {
            int firstLineBreak = cleaned.indexOf("\n");
            if (firstLineBreak != -1) {
                cleaned = cleaned.substring(firstLineBreak + 1);
            }
            if (cleaned.contains("```")) {
                cleaned = cleaned.substring(0, cleaned.indexOf("```"));
            }
        }
        return cleaned.trim();
    }

    private String callModelApi(Map<String, Object> payload) {
        String modelName = payload == null ? null : String.valueOf(payload.get("model"));
        EffectiveConnection connection = resolveConnectionForModel(modelName);
        if (connection == null) {
            return callModelApi(payload, baseUrl, apiKey);
        }
        return callModelApi(payload, connection.baseUrl(), connection.apiKey());
    }

    private String callModelApi(Map<String, Object> payload, String effectiveBaseUrl, String effectiveApiKey) {
        Integer timeoutMs = null;
        Map<String, Object> sanitizedPayload = new LinkedHashMap<>(payload);
        try {
            Object timeoutValue = sanitizedPayload.remove("timeout_ms");
            if (timeoutValue instanceof Number n) {
                timeoutMs = n.intValue();
            }
            ChatCompletionsCommand command = ChatCompletionsCommand.fromLegacyPayload(sanitizedPayload);
            ModelGatewayResult result = modelGatewayService.invoke(new ModelGatewayInvocation(
                    ModelGatewayEndpoint.CHAT_COMPLETIONS,
                    "dashscope",
                    command.model(),
                    effectiveBaseUrl,
                    effectiveApiKey,
                    command.toPayload(),
                    "model_service_sync",
                    timeoutMs));
            return result.body();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "模型服务调用失败(" + e.getClass().getSimpleName() + "): " + detailMessage(e));
        }
    }


    private Integer extractHttpStatus(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile("HTTP\\s+(\\d{3})").matcher(message);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (Exception ignore) {
            return null;
        }
    }

    private String classifyErrorType(String message, Integer httpStatus) {
        String msg = message == null ? "" : message.toLowerCase();
        if (msg.contains("read timed out") || msg.contains("timeout") || msg.contains("timed out")) {
            return "timeout";
        }
        if (httpStatus != null) {
            if (httpStatus == 401 || httpStatus == 403) {
                return "auth";
            }
            if (httpStatus == 429) {
                if (msg.contains("quota") || msg.contains("余额") || msg.contains("配额")) {
                    return "quota";
                }
                return "rate_limit";
            }
            if (httpStatus == 400) {
                return "bad_request";
            }
        }
        if (msg.contains("model_not_found") || msg.contains("does not exist")) {
            return "bad_request";
        }
        if (msg.contains("unauthorized") || msg.contains("forbidden") || msg.contains("api key")) {
            return "auth";
        }
        if (msg.contains("quota") || msg.contains("余额") || msg.contains("配额")) {
            return "quota";
        }
        return "unknown";
    }

    private EffectiveConnection resolveConnectionForModel(String modelName) {
        if (modelName != null && !modelName.isBlank()) {
            var fromRegistry = modelRouterService.resolveConnection(modelName);
            if (fromRegistry.isPresent()) {
                var c = fromRegistry.get();
                return new EffectiveConnection(c.baseUrl(), c.apiKey(), "registry");
            }
        }
        if (!baseUrl.isBlank() && !apiKey.isBlank()) {
            return new EffectiveConnection(baseUrl, apiKey, "env");
        }
        return null;
    }

    private String detailMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        Throwable root = rootCause(throwable);
        String msg = root.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = throwable.getMessage();
        }
        if (msg == null || msg.isBlank()) {
            msg = root.getClass().getSimpleName();
        }
        return truncate(msg.replace("\n", " ").replace("\r", " "), 400);
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private JsonNode fallbackExpandedManuscript(String topic,
                                                String topicRefined,
                                                String researchQuestionsJson,
                                                JsonNode outlineJson) {
        try {
            JsonNode questions = researchQuestionsJson == null || researchQuestionsJson.isBlank()
                    ? objectMapper.createArrayNode()
                    : objectMapper.readTree(researchQuestionsJson);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("topic", topic == null ? "" : topic);
            result.put("topicRefined", topicRefined == null ? "" : topicRefined);
            result.put("researchQuestions", questions);
            result.put("chapters", outlineJson == null ? objectMapper.createArrayNode() : outlineJson.path("chapters"));
            return objectMapper.valueToTree(result);
        } catch (Exception e) {
            return objectMapper.valueToTree(Map.of(
                    "topic", topic == null ? "" : topic,
                    "topicRefined", topicRefined == null ? "" : topicRefined,
                    "researchQuestions", List.of(),
                    "chapters", List.of()
            ));
        }
    }

    private JsonNode fallbackRewriteResult(JsonNode manuscriptJson, JsonNode qualityReportJson) {
        JsonNode issues = qualityReportJson == null ? objectMapper.createArrayNode() : qualityReportJson.path("issues");
        return objectMapper.valueToTree(Map.of(
                "manuscript", manuscriptJson == null ? objectMapper.createObjectNode() : manuscriptJson,
                "appliedIssues", issues.isArray() ? issues : objectMapper.createArrayNode(),
                "summary", "质量回写降级：沿用原文稿"
        ));
    }

    private JsonNode normalizeQualityReport(JsonNode raw) {
        JsonNode node = raw == null ? objectMapper.createObjectNode() : raw;
        boolean logicClosedLoop = node.path("logicClosedLoop").asBoolean(true);
        String methodConsistency = node.path("methodConsistency").asText("ok");
        String citationVerifiability = node.path("citationVerifiability").asText("ok");
        int score = node.has("overallScore") ? node.path("overallScore").asInt(0) : node.path("score").asInt(0);
        if (score < 0) {
            score = 0;
        }
        if (score > 100) {
            score = 100;
        }
        ArrayNode issues = objectMapper.createArrayNode();
        JsonNode rawIssues = node.path("issues");
        if (rawIssues.isArray()) {
            for (JsonNode issueNode : rawIssues) {
                ObjectNode issue = objectMapper.createObjectNode();
                if (issueNode.isObject()) {
                    String field = issueNode.path("field").asText("outline");
                    String severity = issueNode.path("severity").asText("medium");
                    String suggestion = issueNode.path("suggestion").asText("");
                    String message = issueNode.path("message").asText("");
                    if (message.isBlank()) {
                        message = suggestion.isBlank() ? "发现可改进项" : suggestion;
                    }
                    if (suggestion.isBlank()) {
                        suggestion = message;
                    }
                    issue.put("field", field);
                    issue.put("severity", normalizeSeverity(severity));
                    issue.put("suggestion", suggestion);
                    issue.put("message", message);
                } else {
                    String message = issueNode.asText("").isBlank() ? "发现可改进项" : issueNode.asText("");
                    issue.put("field", "outline");
                    issue.put("severity", "medium");
                    issue.put("suggestion", message);
                    issue.put("message", message);
                }
                issues.add(issue);
            }
        }

        ObjectNode normalized = objectMapper.createObjectNode();
        normalized.put("logicClosedLoop", logicClosedLoop);
        normalized.put("methodConsistency", methodConsistency);
        normalized.put("citationVerifiability", citationVerifiability);
        normalized.put("overallScore", score);
        normalized.set("issues", issues);
        return normalized;
    }

    private String normalizeSeverity(String severity) {
        String s = severity == null ? "" : severity.trim().toLowerCase();
        if ("high".equals(s) || "medium".equals(s) || "low".equals(s)) {
            return s;
        }
        return "medium";
    }

    private void savePromptHistory(String taskId,
                                   String projectId,
                                   String templateKey,
                                   Integer versionNo,
                                   String modelName,
                                   String requestHash,
                                   String inputJson,
                                   String outputJson,
                                   int tokenInput,
                                   int tokenOutput,
                                   int latencyMs,
                                   String status,
                                   String errorCode,
                                   String errorMessage) {
        try {
            PromptHistoryEntity history = new PromptHistoryEntity();
            history.setTaskId(taskId);
            history.setProjectId(projectId);
            history.setTemplateKey(templateKey);
            history.setVersionNo(versionNo == null ? 1 : versionNo);
            String effectiveModel = modelName == null || modelName.isBlank() ? resolveCodeModel() : modelName;
            history.setModel(effectiveModel);
            history.setRequestHash(requestHash == null ? "na" : requestHash);
            history.setInputJson(inputJson);
            history.setOutputJson(outputJson);
            history.setTokenInput(Math.max(0, tokenInput));
            history.setTokenOutput(Math.max(0, tokenOutput));
            history.setLatencyMs(Math.max(0, latencyMs));
            history.setStatus(status == null ? "success" : status);
            history.setErrorCode(errorCode);
            history.setErrorMessage(errorMessage == null ? null : truncate(errorMessage, 255));
            history.setCreatedAt(LocalDateTime.now());
            promptHistoryRepository.save(history);

            // Record usage for model routing
            if ("success".equals(status)) {
                try {
                    modelRouterService.recordUsage(effectiveModel, Math.max(0, tokenInput), Math.max(0, tokenOutput));
                } catch (Exception usageEx) {
                    logger.warn("Failed to record model usage for {}", effectiveModel, usageEx);
                }
            }
        } catch (Exception ex) {
            logger.warn("Failed to save prompt history", ex);
        }
    }

    private String hash(String text) {
        if (text == null) {
            return "na";
        }
        return Integer.toHexString(text.hashCode());
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return null;
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen);
    }

    private String compactForLog(String text, int maxLen) {
        String truncated = truncate(text, maxLen);
        if (truncated == null) {
            return "null";
        }
        return truncated.replace("\n", "\\n").replace("\r", "\\r");
    }

    public Map<String, Object> extractRequirements(String reply) {
        try {
            int start = reply.lastIndexOf("```json");
            if (start != -1) {
                int end = reply.indexOf("```", start + 7);
                if (end != -1) {
                    String jsonStr = reply.substring(start + 7, end).trim();
                    return objectMapper.readValue(jsonStr, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>(){});
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to parse extractedRequirements", e);
        }
        return null;
    }

    private String buildStubReply(List<String> modules, String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("我已收到需求，我们先把范围收敛成可交付的 MVP。");
        sb.append("\n\n你当前描述：").append(userMessage == null ? "" : userMessage.trim());
        sb.append("\n\n建议模块：");
        for (String m : modules) {
            sb.append("\n- ").append(m);
        }
        sb.append("\n\n请补充：目标用户、核心流程、权限角色、关键数据字段。");
        return sb.toString();
    }

    public int estimateTokens(JsonNode json) {
        try {
            return estimateTokens(objectMapper.writeValueAsString(json));
        } catch (Exception e) {
            return 0;
        }
    }

    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, (text.length() + 3) / 4);
    }

    /**
     * Lightweight synchronous chat for non-pipeline use (e.g., topic suggestion).
     * Returns raw LLM text response (cleaned of markdown fences).
     */
    public String chat(String templateKey, Map<String, String> vars) {
        PromptResolver.ResolvedPrompt resolvedPrompt = promptResolver.resolve(templateKey)
                .orElseThrow(() -> new BusinessException(ErrorCodes.INTERNAL_ERROR, "Prompt template not found: " + templateKey));
        return chat(resolvedPrompt, vars);
    }

    public String chat(PromptResolver.ResolvedPrompt prompt, Map<String, String> vars) {
        String modelName = resolveCodeModel();
        if (prompt.version().getModel() != null && !prompt.version().getModel().isBlank()) {
            modelName = prompt.version().getModel();
        }

        String systemPrompt = resolveGlobalRulesPrefix() + promptRenderer.render(prompt.version().getSystemPrompt(), vars);
        String userPrompt = promptRenderer.render(prompt.version().getUserPrompt(), vars);

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", userPrompt));

        Map<String, Object> payload = Map.of("model", modelName, "messages", messages);
        String response = callModelApi(payload);
        try {
            JsonNode root = objectMapper.readTree(response);
            String content = root.at("/choices/0/message/content").asText("");
            return cleanupJsonContent(content);
        } catch (Exception e) {
            throw new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "Failed to parse model response: " + e.getMessage());
        }
    }

    public String chatForText(String templateKey, Map<String, String> vars) {
        return chat(templateKey, vars);
    }
}

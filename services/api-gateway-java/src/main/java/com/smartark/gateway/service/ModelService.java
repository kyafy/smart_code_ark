package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.PromptHistoryEntity;
import com.smartark.gateway.db.repo.PromptHistoryRepository;
import com.smartark.gateway.prompt.PromptRenderer;
import com.smartark.gateway.prompt.PromptResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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

    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;
    private final boolean mockEnabled;
    private final String chatModel;
    private final String codeModel;
    private final PromptResolver promptResolver;
    private final PromptRenderer promptRenderer;
    private final PromptHistoryRepository promptHistoryRepository;

    public ModelService(
            ObjectMapper objectMapper,
            PromptResolver promptResolver,
            PromptRenderer promptRenderer,
            PromptHistoryRepository promptHistoryRepository,
            @Value("${smartark.model.base-url:}") String baseUrl,
            @Value("${smartark.model.api-key:}") String apiKey,
            @Value("${smartark.model.mock-enabled:false}") boolean mockEnabled,
            @Value("${smartark.model.chat-model:Qwen3.5-Plus}") String chatModel,
            @Value("${smartark.model.code-model:qwen-plus}") String codeModel
    ) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.mockEnabled = mockEnabled;
        this.chatModel = chatModel;
        this.codeModel = codeModel;
        this.promptResolver = promptResolver;
        this.promptRenderer = promptRenderer;
        this.promptHistoryRepository = promptHistoryRepository;
        this.restClient = RestClient.builder().build();
    }

    /**
     * Resolve global engineering rules from prompt_templates.
     * Prepended to system prompts of code-generation LLM calls.
     */
    private String resolveGlobalRulesPrefix() {
        try {
            return promptResolver.resolve("global_engineering_rules")
                    .map(rp -> rp.version().getSystemPrompt())
                    .filter(s -> s != null && !s.isBlank())
                    .map(s -> s + "\n\n")
                    .orElse("");
        } catch (Exception e) {
            logger.warn("Failed to resolve global engineering rules, skipping", e);
            return "";
        }
    }

    public void streamChatReply(String sessionTitle, String projectType, String userMessage, List<Map<String, String>> history, Consumer<String> onContent, Runnable onDone, Consumer<Throwable> onError) {
        if (baseUrl.isEmpty() || apiKey.isEmpty()) {
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
            logger.info("Starting stream chat with model: {}", chatModel);
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", 
                "你是智能助手，请帮助用户梳理软件系统需求。在回复用户时，请先以友好的语气进行对话，帮助澄清和完善需求细节。\n" +
                "当需求逐渐清晰时，请在回复的**最末尾**，严格按照以下 JSON 格式总结当前提取到的所有需求，并使用 ```json 和 ``` 包裹：\n" +
                "{\n" +
                "  \"pages\": [\"PC端-登录页\", \"PC端-管理员首页\"],\n" +
                "  \"title\": \"项目名称\",\n" +
                "  \"features\": [\"功能点1\", \"功能点2\"],\n" +
                "  \"coreFlows\": [\"核心流程1\"],\n" +
                "  \"userRoles\": [\"管理员\", \"用户\"],\n" +
                "  \"constraints\": [\"约束1\"],\n" +
                "  \"description\": \"项目概述\",\n" +
                "  \"technicalRequirements\": [\"技术要求1\"],\n" +
                "  \"externalApiRequirements\": \"外部API要求\"\n" +
                "}\n" +
                "如果还在早期探讨阶段，可以不输出 JSON。"
            ));
            if (history != null) {
                messages.addAll(history);
            }
            messages.add(Map.of("role", "user", "content", userMessage));

            Map<String, Object> payload = Map.of(
                    "model", chatModel,
                    "messages", messages,
                    "stream", true
            );
            
            String url;
            if (baseUrl.endsWith("/v1/") || baseUrl.endsWith("/v1")) {
                url = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";
            } else {
                url = baseUrl.endsWith("/") ? baseUrl + "v1/chat/completions" : baseUrl + "/v1/chat/completions";
            }
            logger.debug("Requesting model API: {}", url);

            restClient.post()
                    .uri(url)
                    .headers(h -> {
                        if (!apiKey.isEmpty()) {
                            h.set("Authorization", "Bearer " + apiKey);
                        }
                    })
                    .body(payload)
                    .exchange((request, response) -> {
                        var status = response.getStatusCode();
                        logger.info("Model API response status: {}", status);
                        try (InputStream is = response.getBody()) {
                            if (!status.is2xxSuccessful()) {
                                String errBody;
                                try {
                                    errBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                                } catch (Exception ex) {
                                    errBody = null;
                                }
                                String msg = "模型服务调用失败: " + status;
                                if (errBody != null && !errBody.isBlank()) {
                                    msg = msg + " body=" + truncate(errBody.replace("\n", " ").replace("\r", " "), 500);
                                }
                                onError.accept(new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, msg));
                                return null;
                            }
                            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.startsWith("data: ")) {
                                    String data = line.substring(6).trim();
                                    if ("[DONE]".equals(data)) {
                                        break;
                                    }
                                    try {
                                        JsonNode root = objectMapper.readTree(data);
                                        String content = root.at("/choices/0/delta/content").asText("");
                                        if (!content.isEmpty()) {
                                            onContent.accept(content);
                                        }
                                    } catch (Exception e) {
                                        logger.warn("Failed to parse stream data: {}", data, e);
                                    }
                                }
                            }
                            onDone.run();
                        } catch (Exception e) {
                            logger.error("Error reading stream response", e);
                            onError.accept(e);
                        }
                        return null;
                    });
        } catch (Exception e) {
            logger.error("Failed to initiate stream request", e);
            if (e instanceof ResourceAccessException) {
                onError.accept(new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "模型服务调用超时"));
                return;
            }
            onError.accept(e);
        }
    }

    public ModelResult chatReply(String sessionTitle, String projectType, String userMessage, List<Map<String, String>> history) {
        if (baseUrl.isEmpty() || apiKey.isEmpty()) {
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
            return new ModelResult(chatModel, reply, modules, inTok, outTok);
        }
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", "你是智能助手，请帮助用户梳理需求并输出模块清单。"));
            if (history != null) {
                messages.addAll(history);
            }
            messages.add(Map.of("role", "user", "content", userMessage));

            Map<String, Object> payload = Map.of(
                    "model", chatModel,
                    "messages", messages
            );
            
            String url;
            if (baseUrl.endsWith("/v1/") || baseUrl.endsWith("/v1")) {
                url = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";
            } else {
                url = baseUrl.endsWith("/") ? baseUrl + "v1/chat/completions" : baseUrl + "/v1/chat/completions";
            }

            String response = restClient.post()
                    .uri(url)
                    .headers(h -> {
                        if (!apiKey.isEmpty()) {
                            h.set("Authorization", "Bearer " + apiKey);
                        }
                    })
                    .body(payload)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            String reply = root.at("/choices/0/message/content").asText("");
            int promptTokens = root.at("/usage/prompt_tokens").asInt(estimateTokens(objectMapper.valueToTree(payload)));
            int completionTokens = root.at("/usage/completion_tokens").asInt(estimateTokens(reply));
            List<String> modules = guessModules(sessionTitle, reply, projectType);
            return new ModelResult(chatModel, reply, modules, promptTokens, completionTokens);
        } catch (Exception e) {
            throw new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "模型服务调用失败");
        }
    }

    public String generateRequirement(String sessionTitle, String projectType, List<Map<String, String>> history) {
        if (baseUrl.isEmpty() || apiKey.isEmpty()) {
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
                    "model", chatModel,
                    "messages", messages
            );
            
            String url;
            if (baseUrl.endsWith("/v1/") || baseUrl.endsWith("/v1")) {
                url = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";
            } else {
                url = baseUrl.endsWith("/") ? baseUrl + "v1/chat/completions" : baseUrl + "/v1/chat/completions";
            }

            String response = restClient.post()
                    .uri(url)
                    .headers(h -> {
                        if (!apiKey.isEmpty()) {
                            h.set("Authorization", "Bearer " + apiKey);
                        }
                    })
                    .body(payload)
                    .retrieve()
                    .body(String.class);

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
        String modelName = codeModel;
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
            String systemPrompt = defaultSystemPrompt;
            String userPrompt = defaultUserPrompt;
            if (resolvedPrompt != null) {
                versionNo = resolvedPrompt.version().getVersionNo();
                if (resolvedPrompt.version().getModel() != null && !resolvedPrompt.version().getModel().isBlank()) {
                    modelName = resolvedPrompt.version().getModel();
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

            // Clean up markdown if present
            if (content.contains("```json")) {
                content = content.substring(content.indexOf("```json") + 7);
                if (content.contains("```")) {
                    content = content.substring(0, content.indexOf("```"));
                }
            } else if (content.contains("```")) {
                 content = content.substring(content.indexOf("```") + 3);
                 if (content.contains("```")) {
                     content = content.substring(0, content.indexOf("```"));
                 }
            }

            List<String> result = objectMapper.readValue(content.trim(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){});
            savePromptHistory(taskId, projectId, templateKey, versionNo, modelName, requestHash, requestJson, response, estimateTokens(requestJson), estimateTokens(response), (int) (System.currentTimeMillis() - start), "success", null, null);
            return result;
        } catch (Exception e) {
            logger.error("Failed to generate project structure", e);
            savePromptHistory(taskId, projectId, templateKey, versionNo, modelName, requestHash, requestJson, null, estimateTokens(requestJson), 0, (int) (System.currentTimeMillis() - start), "failed", String.valueOf(ErrorCodes.MODEL_SERVICE_ERROR), detailMessage(e));
            throw new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "生成项目结构失败");
        }
    }

    public String generateFileContent(String prd, String filePath, String techStack, String instructions) {
        return generateFileContent(null, null, prd, filePath, techStack, instructions);
    }

    public String generateFileContent(String taskId, String projectId, String prd, String filePath, String techStack, String instructions) {
        if (baseUrl.isEmpty()) {
            return "// Mock content for " + filePath;
        }
        long start = System.currentTimeMillis();
        String templateKey = "file_content_generate";
        int versionNo = 1;
        String modelName = codeModel;
        String requestJson = null;
        String requestHash = null;
        try {
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("prd", prd);
            vars.put("filePath", filePath);
            vars.put("techStack", techStack);
            vars.put("instructions", instructions == null ? "" : instructions);

            String defaultSystemPrompt =
                    "你是一个全栈工程师。请根据PRD和技术栈，生成指定文件的完整代码内容。\n" +
                    "文件路径：{{filePath}}\n" +
                    "技术栈：{{techStack}}\n" +
                    "必须将 PRD 中的业务对象、关键流程、约束规则落实到代码中，禁止仅输出空壳框架、占位 TODO 或示例模板。\n" +
                    "如果目标是 Controller/Service/Repository/Entity/Page/Store/SQL 等业务文件，必须包含具体业务字段、接口、校验或流程实现。\n" +
                    "请直接输出文件内容，不要包含Markdown标记（如 ```java ... ```），除非文件本身是Markdown格式。\n" +
                    "如果文件是代码，请确保可以直接运行或编译。";
            String defaultUserPrompt = "PRD内容：\n{{prd}}\n\n额外指令：\n{{instructions}}\n\n输出要求：\n1) 必须体现至少2个业务字段或业务规则；\n2) 如果是接口层需包含参数校验与错误处理；\n3) 如果是数据层需体现实体字段与约束；\n4) 不能只输出项目脚手架样例。";

            PromptResolver.ResolvedPrompt resolvedPrompt = promptResolver.resolve(templateKey).orElse(null);
            String systemPrompt = defaultSystemPrompt;
            String userPrompt = defaultUserPrompt;
            if (resolvedPrompt != null) {
                versionNo = resolvedPrompt.version().getVersionNo();
                if (resolvedPrompt.version().getModel() != null && !resolvedPrompt.version().getModel().isBlank()) {
                    modelName = resolvedPrompt.version().getModel();
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

    public JsonNode clarifyPaperTopic(String taskId,
                                      String projectId,
                                      String topic,
                                      String discipline,
                                      String degreeLevel,
                                      String methodPreference) {
        if (baseUrl.isEmpty()) {
            return objectMapper.valueToTree(Map.of(
                    "topicRefined", topic,
                    "researchQuestions", List.of("核心研究问题是什么", "可行的研究方法是什么", "如何验证研究结论")
            ));
        }
        long start = System.currentTimeMillis();
        String templateKey = "paper_topic_clarify";
        int versionNo = 1;
        String modelName = codeModel;
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
        if (baseUrl.isEmpty()) {
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
        String modelName = codeModel;
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
        if (baseUrl.isEmpty()) {
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
        String modelName = codeModel;
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
        if (baseUrl.isEmpty()) {
            return fallbackExpandedManuscript(topic, topicRefined, researchQuestionsJson, outlineJson);
        }
        long start = System.currentTimeMillis();
        String templateKey = "paper_outline_expand";
        int versionNo = ragEvidence != null ? 3 : 1;
        String modelName = codeModel;
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
            String defaultSystemPrompt = "你是论文写作助手。请基于输入的大纲与文献，输出 JSON：{topic:string,topicRefined:string,researchQuestions:string[],chapters:[{index:number,title:string,summary:string,sections:[{title:string,content:string,citations:string[]}]}]}。仅输出 JSON。";
            String defaultUserPrompt = "主题：{{topic}}\n细化题目：{{topicRefined}}\n学科：{{discipline}}\n学位层次：{{degreeLevel}}\n方法偏好：{{methodPreference}}\n研究问题：{{researchQuestions}}\n大纲：{{outlineJson}}\n候选文献：{{sources}}";
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

    public JsonNode rewriteOutlineByQualityIssues(String taskId,
                                                  String projectId,
                                                  String topic,
                                                  String topicRefined,
                                                  String citationStyle,
                                                  JsonNode qualityReportJson,
                                                  JsonNode manuscriptJson) {
        if (baseUrl.isEmpty()) {
            return fallbackRewriteResult(manuscriptJson, qualityReportJson);
        }
        long start = System.currentTimeMillis();
        String templateKey = "paper_outline_quality_rewrite";
        int versionNo = 1;
        String modelName = codeModel;
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
                modelName = resolvedPrompt.version().getModel();
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
        String requestJson = objectMapper.writeValueAsString(payload);
        String requestHash = hash(requestJson);
        try {
            String response = callModelApi(payload);
            JsonNode root = objectMapper.readTree(response);
            String content = root.at("/choices/0/message/content").asText("");
            String cleaned = cleanupJsonContent(content);
            JsonNode parsed = objectMapper.readTree(cleaned);
            savePromptHistory(taskId, projectId, templateKey, versionNo, modelName, requestHash, requestJson, response, estimateTokens(requestJson), estimateTokens(response), (int) (System.currentTimeMillis() - start), "success", null, null);
            return parsed;
        } catch (Exception e) {
            String errorCode = e instanceof BusinessException be ? String.valueOf(be.getCode()) : String.valueOf(ErrorCodes.MODEL_SERVICE_ERROR);
            savePromptHistory(taskId, projectId, templateKey, versionNo, modelName, requestHash, requestJson, null, estimateTokens(requestJson), 0, (int) (System.currentTimeMillis() - start), "failed", errorCode, e.getMessage());
            throw e;
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
        String url;
        if (baseUrl.endsWith("/v1/") || baseUrl.endsWith("/v1")) {
            url = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";
        } else {
            url = baseUrl.endsWith("/") ? baseUrl + "v1/chat/completions" : baseUrl + "/v1/chat/completions";
        }
        try {
            return restClient.post()
                    .uri(url)
                    .headers(h -> {
                        if (!apiKey.isEmpty()) {
                            h.set("Authorization", "Bearer " + apiKey);
                        }
                    })
                    .body(payload)
                    .retrieve()
                    .body(String.class);
        } catch (ResourceAccessException e) {
            throw new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "模型服务调用失败(ResourceAccessException): " + detailMessage(e));
        } catch (RestClientResponseException e) {
            String body = truncate(e.getResponseBodyAsString(), 300);
            throw new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "模型服务调用失败(HTTP " + e.getStatusCode().value() + "): " + body);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "模型服务调用失败(" + e.getClass().getSimpleName() + "): " + detailMessage(e));
        }
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
            history.setModel(modelName == null || modelName.isBlank() ? codeModel : modelName);
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
        String modelName = codeModel;
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

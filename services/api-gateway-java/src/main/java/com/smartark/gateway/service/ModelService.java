package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

    public ModelService(
            ObjectMapper objectMapper,
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
        this.restClient = RestClient.builder().build();
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
                        logger.info("Model API response status: {}", response.getStatusCode());
                        try (InputStream is = response.getBody();
                             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
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

    public List<String> generateProjectStructure(String prd, String stackBackend, String stackFrontend, String stackDb) {
        if (baseUrl.isEmpty()) {
            return List.of("README.md", "backend/src/main/java/App.java", "frontend/package.json");
        }
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", 
                "你是一个资深架构师。请根据以下PRD和技术栈，规划项目的完整文件结构。\n" +
                "技术栈：后端 " + stackBackend + "，前端 " + stackFrontend + "，数据库 " + stackDb + "。\n" +
                "请输出一个JSON数组，包含所有需要生成的文件路径（相对路径）。\n" +
                "例如：[\"README.md\", \"backend/pom.xml\", \"frontend/package.json\", ...]\n" +
                "请确保结构合理，包含必要的配置文件、代码文件和部署文件（Dockerfile, docker-compose.yml）。\n" +
                "只输出JSON数组，不要包含Markdown标记或其他文字。"
            ));
            messages.add(Map.of("role", "user", "content", "PRD内容：\n" + prd));

            Map<String, Object> payload = Map.of(
                    "model", codeModel,
                    "messages", messages
            );
            
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
            
            return objectMapper.readValue(content.trim(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){});
        } catch (Exception e) {
            logger.error("Failed to generate project structure", e);
            throw new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "生成项目结构失败");
        }
    }

    public String generateFileContent(String prd, String filePath, String techStack) {
        if (baseUrl.isEmpty()) {
            return "// Mock content for " + filePath;
        }
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", 
                "你是一个全栈工程师。请根据PRD和技术栈，生成指定文件的完整代码内容。\n" +
                "文件路径：" + filePath + "\n" +
                "技术栈：" + techStack + "\n" +
                "请直接输出文件内容，不要包含Markdown标记（如 ```java ... ```），除非文件本身是Markdown格式。\n" +
                "如果文件是代码，请确保可以直接运行或编译。"
            ));
            messages.add(Map.of("role", "user", "content", "PRD内容：\n" + prd));

            Map<String, Object> payload = Map.of(
                    "model", codeModel,
                    "messages", messages
            );
            
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
            
            return content;
        } catch (Exception e) {
            logger.error("Failed to generate file content for " + filePath, e);
            return "// Failed to generate content: " + e.getMessage();
        }
    }

    private String callModelApi(Map<String, Object> payload) {
        String url;
        if (baseUrl.endsWith("/v1/") || baseUrl.endsWith("/v1")) {
            url = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";
        } else {
            url = baseUrl.endsWith("/") ? baseUrl + "v1/chat/completions" : baseUrl + "/v1/chat/completions";
        }

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
}

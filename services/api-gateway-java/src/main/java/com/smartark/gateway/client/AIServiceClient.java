package com.smartark.gateway.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.dto.GenerateRequest;
import com.smartark.gateway.dto.GenerateResult;
import com.smartark.gateway.dto.RequirementRequest;
import com.smartark.gateway.dto.RequirementResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * AI 服务客户端
 * <p>
 * 封装与后端 AI 微服务（需求解析服务、代码生成服务）的 HTTP 通信。
 * 负责请求的发送、响应的解析以及异常的转换。
 * </p>
 */
@Component
public class AIServiceClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String requirementServiceUrl;
    private final String generationServiceUrl;

    /**
     * 构造函数
     *
     * @param restTemplate          Spring RestTemplate 实例
     * @param objectMapper          JSON 处理工具
     * @param requirementServiceUrl 需求服务的基础 URL
     * @param generationServiceUrl  生成服务的基础 URL
     */
    public AIServiceClient(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${services.requirement.url}") String requirementServiceUrl,
            @Value("${services.generation.url}") String generationServiceUrl
    ) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.requirementServiceUrl = requirementServiceUrl;
        this.generationServiceUrl = generationServiceUrl;
    }

    /**
     * 调用需求解析服务
     *
     * @param text 原始需求文本
     * @return 解析后的结构化需求
     * @throws BusinessException 如果调用失败或超时
     */
    public RequirementResult parseRequirement(String text) {
        return exchange(requirementServiceUrl + "/api/v1/requirements/parse", new RequirementRequest(text), RequirementResult.class);
    }

    /**
     * 调用代码生成服务
     *
     * @param analysis 结构化的需求分析结果
     * @return 生成的代码文件列表
     * @throws BusinessException 如果调用失败或超时
     */
    public GenerateResult generateProject(RequirementResult analysis) {
        return exchange(generationServiceUrl + "/api/v1/generations/scaffold", new GenerateRequest(analysis), GenerateResult.class);
    }

    /**
     * 执行 HTTP 请求并处理响应
     *
     * @param url        请求 URL
     * @param payload    请求体
     * @param targetType 期望的返回类型
     * @param <T>        返回类型泛型
     * @return 解析后的响应数据
     */
    private <T> T exchange(String url, Object payload, Class<T> targetType) {
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new org.springframework.http.HttpEntity<>(payload),
                    new ParameterizedTypeReference<>() {
                    }
            );
            return parseData(response.getBody(), targetType);
        } catch (ResourceAccessException ex) {
            throw new BusinessException(ErrorCodes.DOWNSTREAM_TIMEOUT, "下游服务超时: " + url);
        } catch (RestClientException ex) {
            throw new BusinessException(ErrorCodes.DOWNSTREAM_FAILED, "下游服务调用失败: " + ex.getMessage());
        }
    }

    /**
     * 解析下游服务的标准响应格式
     *
     * @param body       响应体 Map
     * @param targetType 目标类型
     * @param <T>        返回类型泛型
     * @return 转换后的数据对象
     */
    private <T> T parseData(Map<String, Object> body, Class<T> targetType) {
        if (body == null || !body.containsKey("data")) {
            throw new BusinessException(ErrorCodes.DOWNSTREAM_FORMAT, "下游服务返回格式异常");
        }
        Object code = body.get("code");
        if (!(code instanceof Number) || ((Number) code).intValue() != 0) {
            throw new BusinessException(ErrorCodes.DOWNSTREAM_FAILED, String.valueOf(body.getOrDefault("message", "下游服务调用失败")));
        }
        return objectMapper.convertValue(body.get("data"), targetType);
    }
}

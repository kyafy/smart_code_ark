package com.smartark.gateway.service.modelgateway;

public enum ModelGatewayEndpoint {
    CHAT_COMPLETIONS("chat.completions"),
    EMBEDDINGS("embeddings");

    private final String value;

    ModelGatewayEndpoint(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}

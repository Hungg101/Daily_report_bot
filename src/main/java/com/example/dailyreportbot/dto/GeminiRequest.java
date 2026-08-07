package com.example.dailyreportbot.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeminiRequest(
        List<Content> contents,
        Content systemInstruction,
        List<Tool> tools
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Content(String role, List<Part> parts) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Part(
            String text,
            FunctionCall functionCall,
            FunctionResponse functionResponse,
            String thoughtSignature
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FunctionCall(String name, Map<String, Object> args, String id) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FunctionResponse(String name, Map<String, Object> response, String id) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Tool(List<FunctionDeclaration> functionDeclarations) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FunctionDeclaration(String name, String description, Parameters parameters) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Parameters(String type, Map<String, Schema> properties, List<String> required) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Schema(String type, String description) {}
}

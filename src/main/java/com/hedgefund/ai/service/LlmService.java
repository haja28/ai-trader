package com.hedgefund.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LlmService {

    private static final Logger log = Logger.getLogger(LlmService.class.getName());

    private final ChatModel openAiChatModel;
    private final ChatModel ollamaChatModel;
    private final ObjectMapper objectMapper;

    @Value("${hedgefund.max-llm-retries:3}")
    private int maxRetries;

    @Value("${hedgefund.default-model-provider:OPENAI}")
    private String defaultProvider;

    @Value("${hedgefund.default-model-name:gpt-4.1}")
    private String defaultModelName;

    public LlmService(
            @Autowired(required = false) @Nullable OpenAiChatModel openAiChatModel,
            @Autowired(required = false) @Nullable OllamaChatModel ollamaChatModel,
            ObjectMapper objectMapper) {
        this.openAiChatModel = openAiChatModel;
        this.ollamaChatModel = ollamaChatModel;
        this.objectMapper = objectMapper;
    }

    /**
     * Call the LLM with a system + user prompt, expecting JSON back.
     *
     * @param systemPrompt  system instructions
     * @param userPrompt    user/human message
     * @param responseType  the Java class to deserialize the JSON response into
     * @param provider      "OPENAI" or "OLLAMA" (nullable, falls back to default)
     * @param modelName     specific model name (nullable, falls back to default)
     * @param <T>           response type
     * @return parsed response or null on failure
     */
    public <T> T call(String systemPrompt, String userPrompt, Class<T> responseType, String provider, String modelName) {
        String resolvedProvider = (provider != null && !provider.isBlank()) ? provider : defaultProvider;
        ChatModel model = resolveModel(resolvedProvider);
        ChatOptions options = buildOptions(resolvedProvider, modelName);
        ChatClient client = ChatClient.create(model);

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                ChatClient.ChatClientRequestSpec spec = client.prompt()
                        .system(systemPrompt)
                        .user(userPrompt);

                if (options != null) {
                    spec.options(options);
                }

                String rawResponse = spec.call().content();

                return parseResponse(rawResponse, responseType);
            } catch (Exception e) {
                log.log(Level.WARNING, "LLM call attempt {0}/{1} failed: {2}",
                        new Object[]{attempt + 1, maxRetries, e.getMessage()});
                if (attempt == maxRetries - 1) {
                    log.log(Level.SEVERE, "LLM call failed after " + maxRetries + " attempts", e);
                }
            }
        }
        return null;
    }

    /**
     * Overload that uses the default model name.
     */
    public <T> T call(String systemPrompt, String userPrompt, Class<T> responseType, String provider) {
        return call(systemPrompt, userPrompt, responseType, provider, null);
    }

    /**
     * Simpler overload that uses the default provider and model.
     */
    public <T> T call(String systemPrompt, String userPrompt, Class<T> responseType) {
        return call(systemPrompt, userPrompt, responseType, null, null);
    }

    /**
     * Call the LLM and return the raw text response (no JSON parsing).
     * Used for free-form text generation like reports.
     *
     * @param systemPrompt  system instructions
     * @param userPrompt    user/human message
     * @param provider      "OPENAI" or "OLLAMA" (nullable, falls back to default)
     * @param modelName     specific model name (nullable, falls back to default)
     * @return raw LLM text response or null on failure
     */
    public String callRaw(String systemPrompt, String userPrompt, String provider, String modelName) {
        String resolvedProvider = (provider != null && !provider.isBlank()) ? provider : defaultProvider;
        ChatModel model = resolveModel(resolvedProvider);
        ChatOptions options = buildOptions(resolvedProvider, modelName);
        ChatClient client = ChatClient.create(model);

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                ChatClient.ChatClientRequestSpec spec = client.prompt()
                        .system(systemPrompt)
                        .user(userPrompt);

                if (options != null) {
                    spec.options(options);
                }

                String rawResponse = spec.call().content();
                if (rawResponse != null && !rawResponse.isBlank()) {
                    return rawResponse;
                }
            } catch (Exception e) {
                log.log(Level.WARNING, "LLM raw call attempt {0}/{1} failed: {2}",
                        new Object[]{attempt + 1, maxRetries, e.getMessage()});
                if (attempt == maxRetries - 1) {
                    log.log(Level.SEVERE, "LLM raw call failed after " + maxRetries + " attempts", e);
                }
            }
        }
        return null;
    }

    private ChatModel resolveModel(String provider) {
        if ("OLLAMA".equalsIgnoreCase(provider)) {
            if (ollamaChatModel == null) {
                throw new IllegalStateException(
                        "Ollama chat model is not available. Make sure Ollama is running and spring-ai-ollama-spring-boot-starter is on the classpath.");
            }
            return ollamaChatModel;
        } else {
            if (openAiChatModel == null) {
                throw new IllegalStateException(
                        "OpenAI chat model is not available. Set the spring.ai.openai.api-key property or the OPENAI_API_KEY environment variable.");
            }
            return openAiChatModel;
        }
    }

    private ChatOptions buildOptions(String provider, String modelName) {
        String model = (modelName != null && !modelName.isBlank()) ? modelName : null;
        if (model == null) return null;

        if ("OLLAMA".equalsIgnoreCase(provider)) {
            return OllamaOptions.builder().model(model).build();
        } else {
            return OpenAiChatOptions.builder().model(model).build();
        }
    }

    /**
     * Parse the LLM text response as JSON. Handles markdown code fences.
     */
    private <T> T parseResponse(String raw, Class<T> type) throws JsonProcessingException {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Empty LLM response");
        }

        // Try extracting JSON from markdown fences first
        String json = extractJsonFromMarkdown(raw);
        if (json == null) {
            // Try the raw response directly
            json = raw.trim();
        }

        return objectMapper.readValue(json, type);
    }

    private static final Pattern JSON_FENCE = Pattern.compile("```(?:json)?\\s*(\\{.*?})\\s*```", Pattern.DOTALL);

    private String extractJsonFromMarkdown(String content) {
        Matcher m = JSON_FENCE.matcher(content);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }
}

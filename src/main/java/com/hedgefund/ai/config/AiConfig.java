package com.hedgefund.ai.config;

import org.springframework.context.annotation.Configuration;

/**
 * AI configuration placeholder.
 *
 * Spring AI auto-creates:
 *   - OpenAiChatModel  (from spring-ai-openai-spring-boot-starter)
 *   - OllamaChatModel  (from spring-ai-ollama-spring-boot-starter)
 *
 * LlmService injects these concrete types directly — no manual bean
 * re-export is needed (which avoids BeanDefinitionOverrideException).
 */
@Configuration
public class AiConfig {
    // Auto-configuration handles ChatModel bean creation
}

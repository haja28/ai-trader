package com.hedgefund.ai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.ai.openai.api-key=test-key",
        "spring.ai.ollama.base-url=http://localhost:11434",
        "financial.datasets.api-key=test-key"
})
class AiHedgeFundApplicationTests {

    @Test
    void contextLoads() {
        // Verifies the Spring context starts up
    }
}


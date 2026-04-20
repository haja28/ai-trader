package com.hedgefund.ai.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA/Database configuration — only activates when hedgefund.persistence.enabled=true.
 * Re-imports the DataSource + JPA auto-configs that are excluded by default in
 * AiHedgeFundApplication so the app can start without any DB dependency.
 */
@Configuration
@ConditionalOnProperty(name = "hedgefund.persistence.enabled", havingValue = "true")
@Import({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
@EnableJpaRepositories(basePackages = "com.hedgefund.ai.repository")
@EntityScan(basePackages = "com.hedgefund.ai.model.persistence")
public class PersistenceConfig {
}


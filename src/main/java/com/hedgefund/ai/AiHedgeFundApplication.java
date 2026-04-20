package com.hedgefund.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;

/**
 * Excludes JPA/DataSource auto-config by default.
 * When hedgefund.persistence.enabled=true, PersistenceConfig re-imports them.
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        JpaRepositoriesAutoConfiguration.class
})
public class AiHedgeFundApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiHedgeFundApplication.class, args);
    }
}


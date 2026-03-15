package com.hedgefund.ai.config;

import com.hedgefund.ai.service.api.EodhdClient;
import com.hedgefund.ai.service.api.FinancialDataClient;
import com.hedgefund.ai.service.api.FinancialDatasetsClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.logging.Logger;

/**
 * Selects the active {@link FinancialDataClient} implementation based on the
 * {@code financial.data.provider} property.
 * <p>
 * Supported values:
 * <ul>
 *   <li>{@code financial-datasets} (default) — uses <a href="https://financialdatasets.ai">financialdatasets.ai</a></li>
 *   <li>{@code eodhd}                         — uses <a href="https://eodhd.com">eodhd.com</a></li>
 * </ul>
 */
@Configuration
public class FinancialDataProviderConfig {

    private static final Logger log = Logger.getLogger(FinancialDataProviderConfig.class.getName());

    @Bean
    @Primary
    public FinancialDataClient financialDataClient(
            @Value("${financial.data.provider:financial-datasets}") String provider,
            FinancialDatasetsClient financialDatasetsClient,
            EodhdClient eodhdClient) {

        if ("eodhd".equalsIgnoreCase(provider.trim())) {
            log.info("Financial data provider: EODHD (eodhd.com)");
            return eodhdClient;
        }

        log.info("Financial data provider: Financial Datasets (financialdatasets.ai)");
        return financialDatasetsClient;
    }
}


package io.github.dream07code.testsupport;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** Test-only pool: deliberately has no ConfigurationProperties binding for ambient Hikari settings. */
@TestConfiguration(proxyBeanMethods = false)
public class OwnedDataSourceConfiguration {
    @Bean(destroyMethod = "close")
    public HikariDataSource dataSource(Environment environment) {
        var config = new HikariConfig();
        config.setJdbcUrl(environment.getRequiredProperty("spring.datasource.url"));
        config.setUsername(environment.getRequiredProperty("spring.datasource.username"));
        config.setPassword(environment.getRequiredProperty("spring.datasource.password"));
        config.setConnectionTimeout(3000);
        config.setValidationTimeout(1000);
        config.setInitializationFailTimeout(-1);
        config.setMinimumIdle(0);
        config.setMaximumPoolSize(5);
        return new HikariDataSource(config);
    }
}

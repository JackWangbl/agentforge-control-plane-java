package com.agentforge.controlplane.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * config.import 读完 .env 之后再改写 JDBC URL。EnvironmentPostProcessor 可能早于 .env，
 * 这里作为第二道保险。
 */
@Component
public class SqlAlchemyUrlFixer implements BeanFactoryPostProcessor, EnvironmentAware, Ordered {

    private ConfigurableEnvironment environment;

    @Override
    public void setEnvironment(Environment environment) {
        if (environment instanceof ConfigurableEnvironment configurable) {
            this.environment = configurable;
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        if (environment == null) {
            return;
        }
        String raw = environment.getProperty("DATABASE_URL");
        if (raw == null || raw.isBlank()) {
            raw = environment.getProperty("spring.datasource.url");
        }
        if (raw == null || raw.isBlank()) {
            return;
        }
        if (raw.startsWith("jdbc:")) {
            Map<String, Object> jdbcProps = new LinkedHashMap<>();
            jdbcProps.put("spring.datasource.url", raw);
            String user = environment.getProperty("DATABASE_USER");
            String pass = environment.getProperty("DATABASE_PASSWORD");
            if (user != null && !user.isBlank()) {
                jdbcProps.put("spring.datasource.username", user);
            }
            if (pass != null) {
                jdbcProps.put("spring.datasource.password", pass);
            }
            environment.getPropertySources().addFirst(new MapPropertySource("sqlalchemy-url-fixer", jdbcProps));
            return;
        }
        var parsed = SqlAlchemyUrlEnvironmentPostProcessor.parse(raw);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("spring.datasource.url", parsed.jdbc());
        if (!parsed.username().isEmpty()) {
            props.put("spring.datasource.username", parsed.username());
        }
        props.put("spring.datasource.password", parsed.password());
        environment.getPropertySources().addFirst(new MapPropertySource("sqlalchemy-url-fixer", props));
    }
}

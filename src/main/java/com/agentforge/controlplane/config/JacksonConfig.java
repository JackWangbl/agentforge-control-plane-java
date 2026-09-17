package com.agentforge.controlplane.config;

import com.agentforge.controlplane.util.Jsons;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Instant;

/** Instant 序列化成和 Python isoformat 一样的无时区 UTC 字符串。 */
@Configuration
public class JacksonConfig {

    @Bean
    public SimpleModule instantModule() {
        SimpleModule module = new SimpleModule("agentforge-instant");
        module.addSerializer(Instant.class, new JsonSerializer<>() {
            @Override
            public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeString(Jsons.iso(value));
            }
        });
        return module;
    }
}

package com.guanghe.backend.common.config;

import io.lettuce.core.protocol.ProtocolVersion;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RedisConnectionConfiguration {

    @Bean
    LettuceClientOptionsBuilderCustomizer redisProtocolVersionCustomizer() {
        // Redis 6.0 evaluates HELLO AUTH against the disabled default ACL user.
        // RESP2 authenticates with AUTH first and still supports every command used here.
        return clientOptions -> clientOptions.protocolVersion(ProtocolVersion.RESP2);
    }
}

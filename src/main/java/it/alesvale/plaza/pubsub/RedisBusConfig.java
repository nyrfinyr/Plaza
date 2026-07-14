package it.alesvale.plaza.pubsub;

import io.lettuce.core.api.StatefulConnection;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Owns both Redis connection factories explicitly instead of relying on Spring Boot's
 * auto-configured one: defining any bean assignable to {@code RedisConnectionFactory} makes Boot
 * back off its own auto-configuration entirely ({@code @ConditionalOnMissingBean}), so once we
 * need a second, dedicated connection factory for blocking commands, we own the first one too.
 * <p>
 * Two connection factories on purpose: publish/admin commands (non-blocking) must never queue up
 * behind a long-poll XREADGROUP BLOCK sitting on a shared multiplexed connection, so the blocking
 * path gets its own pooled connection factory sized independently.
 */
@Configuration
class RedisBusConfig {

    /**
     * Primary so unqualified consumers elsewhere in the context (actuator health indicators,
     * reactive templates, etc.) resolve to this one rather than facing ambiguity against the
     * dedicated blocking connection factory below.
     */
    @Bean
    @Primary
    LettuceConnectionFactory nonBlockingRedisConnectionFactory(DataRedisConnectionDetails connectionDetails) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(standaloneConfig(connectionDetails));
        factory.afterPropertiesSet();
        return factory;
    }

    @Bean
    LettuceConnectionFactory blockingRedisConnectionFactory(DataRedisConnectionDetails connectionDetails,
                                                              PubSubProperties pubSubProperties) {
        GenericObjectPoolConfig<StatefulConnection<?, ?>> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(pubSubProperties.blockingPoolSize());
        poolConfig.setMaxIdle(pubSubProperties.blockingPoolSize());
        poolConfig.setMinIdle(0);

        LettuceClientConfiguration clientConfiguration = LettucePoolingClientConfiguration.builder()
                .poolConfig(poolConfig)
                .commandTimeout(Duration.ofSeconds(pubSubProperties.maxTimeoutSeconds() + 5L))
                .build();

        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(standaloneConfig(connectionDetails), clientConfiguration);
        factory.afterPropertiesSet();
        return factory;
    }

    @Bean
    StringRedisTemplate nonBlockingRedisTemplate(
            @Qualifier("nonBlockingRedisConnectionFactory") LettuceConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    StringRedisTemplate blockingRedisTemplate(
            @Qualifier("blockingRedisConnectionFactory") LettuceConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
        return template;
    }

    private static RedisStandaloneConfiguration standaloneConfig(DataRedisConnectionDetails connectionDetails) {
        DataRedisConnectionDetails.Standalone standalone = connectionDetails.getStandalone();
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(standalone.getHost(), standalone.getPort());
        if (connectionDetails.getUsername() != null) {
            config.setUsername(connectionDetails.getUsername());
        }
        if (connectionDetails.getPassword() != null) {
            config.setPassword(connectionDetails.getPassword());
        }
        return config;
    }
}

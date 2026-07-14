package it.alesvale.plaza.pubsub;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "plaza.pubsub")
public record PubSubProperties(int blockingPoolSize, int maxTimeoutSeconds, long streamMaxlen) {
}

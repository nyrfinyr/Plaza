package it.alesvale.plaza.pubsub;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class RedisStreamBusIT {

    @Container
    @ServiceConnection
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Autowired
    private RedisStreamBus bus;

    @Test
    void publishedMessageIsReceivedByNamedSubscriber() {
        bus.publish("test-channel", "hello", "sender-a");

        ReceiveResult result = bus.receive("test-channel", "subscriber-a", 5, 10);

        assertThat(result.timedOut()).isFalse();
        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().get(0).payload()).isEqualTo("hello");
        assertThat(result.messages().get(0).sender()).isEqualTo("sender-a");
    }

    @Test
    void secondSubscriberIndependentlyReceivesTheSameMessage() {
        bus.publish("fanout-channel", "broadcast", "sender-a");

        ReceiveResult first = bus.receive("fanout-channel", "subscriber-x", 5, 10);
        ReceiveResult second = bus.receive("fanout-channel", "subscriber-y", 5, 10);

        assertThat(first.messages()).hasSize(1);
        assertThat(second.messages()).hasSize(1);
    }

    @Test
    void receiveTimesOutWhenNoMessageArrives() {
        ReceiveResult result = bus.receive("empty-channel", "subscriber-b", 1, 10);

        assertThat(result.timedOut()).isTrue();
        assertThat(result.messages()).isEmpty();
    }
}

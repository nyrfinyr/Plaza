package it.alesvale.plaza.pubsub;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
class RedisStreamBus {

    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_.-]{1,128}");
    private static final String CHANNELS_KEY = "plaza:pubsub:channels";
    private static final String STREAM_PREFIX = "plaza:pubsub:stream:";
    private static final String CONSUMER_NAME = "worker";

    private final StringRedisTemplate nonBlocking;
    private final StringRedisTemplate blocking;
    private final PubSubProperties properties;

    RedisStreamBus(@Qualifier("nonBlockingRedisTemplate") StringRedisTemplate nonBlocking,
                   @Qualifier("blockingRedisTemplate") StringRedisTemplate blocking,
                   PubSubProperties properties) {
        this.nonBlocking = nonBlocking;
        this.blocking = blocking;
        this.properties = properties;
    }

    Message publish(String channel, String payload, String sender) {
        validateName(channel, "channel");
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        String senderValue = (sender == null || sender.isBlank()) ? "unknown" : sender;
        long ts = System.currentTimeMillis();

        Map<String, String> fields = Map.of(
                "sender", senderValue,
                "payload", payload,
                "ts", String.valueOf(ts));

        String streamKey = streamKey(channel);
        RecordId id = nonBlocking.opsForStream().add(streamKey, fields);
        nonBlocking.opsForStream().trim(streamKey, properties.streamMaxlen());
        nonBlocking.opsForSet().add(CHANNELS_KEY, channel);

        return new Message(id.getValue(), senderValue, ts, payload);
    }

    ReceiveResult receive(String channel, String subscriberId, int timeoutSeconds, int maxMessages) {
        validateName(channel, "channel");
        validateName(subscriberId, "subscriberId");
        int boundedTimeout = clamp(timeoutSeconds, 1, properties.maxTimeoutSeconds());
        int boundedCount = clamp(maxMessages, 1, 100);

        String streamKey = streamKey(channel);
        String group = groupName(subscriberId);
        ensureGroup(streamKey, group);

        StreamReadOptions options = StreamReadOptions.empty()
                .count(boundedCount)
                .block(Duration.ofSeconds(boundedTimeout));

        StreamOperations<String, Object, Object> ops = blocking.opsForStream();
        List<MapRecord<String, Object, Object>> records = ops.read(
                Consumer.from(group, CONSUMER_NAME),
                options,
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()));

        if (records == null || records.isEmpty()) {
            return new ReceiveResult(List.of(), true);
        }

        List<Message> messages = new ArrayList<>(records.size());
        List<RecordId> ids = new ArrayList<>(records.size());
        for (MapRecord<String, Object, Object> record : records) {
            Map<Object, Object> value = record.getValue();
            messages.add(new Message(
                    record.getId().getValue(),
                    String.valueOf(value.get("sender")),
                    Long.parseLong(String.valueOf(value.get("ts"))),
                    String.valueOf(value.get("payload"))));
            ids.add(record.getId());
        }
        ops.acknowledge(streamKey, group, ids.toArray(RecordId[]::new));

        return new ReceiveResult(messages, false);
    }

    Set<String> listChannels() {
        Set<String> members = nonBlocking.opsForSet().members(CHANNELS_KEY);
        return members == null ? Set.of() : members;
    }

    private void ensureGroup(String streamKey, String group) {
        byte[] keyBytes = nonBlocking.getStringSerializer().serialize(streamKey);
        nonBlocking.execute((RedisCallback<Void>) connection -> {
            try {
                connection.streamCommands().xGroupCreate(keyBytes, group, ReadOffset.latest(), true);
            } catch (RuntimeException ex) {
                if (!isBusyGroup(ex)) {
                    throw ex;
                }
            }
            return null;
        });
    }

    private static boolean isBusyGroup(Throwable ex) {
        String message = ex.getMessage();
        return message != null && message.contains("BUSYGROUP");
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    private static void validateName(String value, String field) {
        if (value == null || !NAME_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    field + " must match " + NAME_PATTERN.pattern() + " but was: " + value);
        }
    }

    private static String streamKey(String channel) {
        return STREAM_PREFIX + channel;
    }

    private static String groupName(String subscriberId) {
        return "grp:" + subscriberId;
    }
}

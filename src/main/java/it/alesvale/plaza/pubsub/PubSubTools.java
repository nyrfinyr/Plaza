package it.alesvale.plaza.pubsub;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class PubSubTools {

    private final RedisStreamBus bus;

    PubSubTools(RedisStreamBus bus) {
        this.bus = bus;
    }

    @McpTool(name = "publish", description = "Publish a message on a named channel of the pub/sub bus")
    Message publish(
            @McpToolParam(description = "Channel name (letters, digits, '.', '_', '-')", required = true)
            String channel,
            @McpToolParam(description = "Message payload, any string (e.g. plain text or JSON)", required = true)
            String payload,
            @McpToolParam(description = "Optional sender identifier", required = false)
            String sender) {
        return bus.publish(channel, payload, sender);
    }

    @McpTool(name = "receive", description = """
            Long-poll for new messages on a channel as a named subscriber. Blocks server-side up to \
            timeoutSeconds waiting for at least one message, then returns (possibly empty if it timed out). \
            Call it again with the same subscriberId to keep listening and pick up only messages you \
            haven't seen yet.""")
    ReceiveResult receive(
            @McpToolParam(description = "Channel name to listen on", required = true)
            String channel,
            @McpToolParam(
                    description = "Stable subscriber id identifying you as a listener; reuse the same value "
                            + "across calls to resume where you left off instead of missing or repeating messages",
                    required = true)
            String subscriberId,
            @McpToolParam(description = "Max seconds to block waiting for a message (default 20, server-capped)", required = false)
            Integer timeoutSeconds,
            @McpToolParam(description = "Max number of messages to return in one call (default 10)", required = false)
            Integer maxMessages) {
        return bus.receive(
                channel,
                subscriberId,
                timeoutSeconds == null ? 20 : timeoutSeconds,
                maxMessages == null ? 10 : maxMessages);
    }

    @McpTool(name = "list_channels", description = "List channel names that have had at least one message published")
    List<String> listChannels() {
        return bus.listChannels().stream().sorted().toList();
    }
}

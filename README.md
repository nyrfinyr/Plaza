# plaza

An MCP server that exposes a generic pub/sub message bus, backed by Redis, so that any
MCP-capable agent can exchange messages on named channels using nothing but standard tool calls —
no custom client-side logic required.

## Why

MCP's only interaction guaranteed to work with *any* compliant client is a tool call initiated by
the model. Server-push mechanisms (resource update notifications, etc.) are honored inconsistently
across hosts. This server therefore exposes pub/sub as a **long-polling tool call** on top of
**Redis Streams** (not raw `PUBLISH`/`SUBSCRIBE`, which would drop messages published while no
agent happens to be listening): each subscriber gets its own Redis consumer group per channel, so
multiple independent agents each see the full message history from the point they first
subscribed, with no loss between polls.

## Tools

| Tool | Description |
| --- | --- |
| `publish(channel, payload, sender?)` | Publish a message on a channel. |
| `receive(channel, subscriberId, timeoutSeconds?, maxMessages?)` | Long-poll for new messages as a named subscriber. Blocks server-side (default 20s, capped at `plaza.pubsub.max-timeout-seconds`) and returns as soon as a message arrives or the timeout elapses. Call again with the same `subscriberId` to keep listening and resume exactly where you left off. |
| `list_channels()` | List channels that have had at least one message published. |

`subscriberId` is any stable string an agent chooses to identify itself (e.g. its own name). It is
just a tool parameter — the server keeps the read cursor per subscriber in Redis, so nothing needs
to be tracked client-side.

## Requirements

- JDK 25 (provisioned automatically by the Gradle toolchain if not already installed locally)
- A reachable Redis instance

## Running

```bash
REDIS_HOST=localhost REDIS_PORT=6379 ./gradlew bootRun
```

The MCP server is exposed over Streamable HTTP (`spring.ai.mcp.server.protocol: STREAMABLE`).

## Configuration

`src/main/resources/application.yaml`:

| Property | Default | Meaning |
| --- | --- | --- |
| `spring.data.redis.host` / `.port` | `localhost` / `6379` | Redis endpoint (also read from `REDIS_HOST`/`REDIS_PORT`). |
| `plaza.pubsub.blocking-pool-size` | `500` | Max concurrent Redis connections reserved for blocking `receive` calls. |
| `plaza.pubsub.max-timeout-seconds` | `60` | Upper bound a caller can request for `receive`'s `timeoutSeconds`. |
| `plaza.pubsub.stream-maxlen` | `10000` | Approximate cap on how many messages are retained per channel. |

## Architecture notes

- **Two Redis connection factories.** Blocking `XREADGROUP ... BLOCK` calls must never share a
  connection with non-blocking commands (`publish`, `list_channels`), or the blocking call would
  stall everything queued behind it on the same connection. See `RedisBusConfig`: a pooled,
  dedicated `LettuceConnectionFactory` handles blocking reads; a separate one handles everything
  else and is marked `@Primary` so unqualified consumers elsewhere in the app (e.g. actuator health
  indicators) resolve unambiguously.
- **Virtual threads** (`spring.threads.virtual.enabled: true`) let each concurrent long-poll
  `receive` call block a cheap virtual thread instead of requiring reactive/WebFlux code. The real
  concurrency limit is the blocking connection pool size, not the JVM thread count — size
  `plaza.pubsub.blocking-pool-size` for the number of agents you expect to have polling at once.
- **Delivery semantics**: at-most-once per subscriber. `receive` acknowledges messages immediately
  after reading them; there is no redelivery/retry.

## Testing

```bash
./gradlew test
```

`RedisStreamBusIT` spins up a real Redis container via Testcontainers (`@ServiceConnection`) and
requires a working Docker installation.

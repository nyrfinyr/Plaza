# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

`plaza` is an MCP server exposing a generic pub/sub message bus backed by Redis Streams (package
`it.alesvale.plaza.pubsub`). See `README.md` for the rationale (why Streams instead of raw
Pub/Sub, why long-polling tool calls instead of push) and the tool contract (`publish`, `receive`,
`list_channels`).

## Stack

- Java 25 (via Gradle toolchain), Spring Boot 4.1.0, Spring AI 2.0.0 (MCP server, webmvc/Streamable
  HTTP transport), Gradle Kotlin DSL
- Dependencies: `spring-boot-starter-actuator`, `spring-boot-starter-data-redis`,
  `spring-ai-starter-mcp-server-webmvc`, `commons-pool2`, Lombok, DevTools
- Base package: `it.alesvale.plaza`; pub/sub bus code under `it.alesvale.plaza.pubsub`

## Commands

Use the Gradle wrapper (`./gradlew`), not a system-installed Gradle.

```bash
./gradlew build              # Compile, run tests, assemble
./gradlew bootRun             # Run the application locally (needs a reachable Redis)
./gradlew test                # Run all tests
./gradlew test --tests "it.alesvale.plaza.PlazaApplicationTests"   # Run a single test class
./gradlew test --tests "*.RedisStreamBusIT"                        # Integration test (needs Docker)
```

`spring-boot-devtools` is on the classpath, so `bootRun` supports live restart on classpath
changes.

### Environment gotchas (WSL2 + Windows-mounted project dir)

- If a build fails with `java.io.IOException: Input/output error` while Gradle is locking a cache
  file under `.gradle/`, it's a flakiness in the 9p/drvfs mount (project lives under `/mnt/c/...`),
  not a real error — `./gradlew --stop` and retry, or pass
  `--project-cache-dir=<a path on the native Linux filesystem>` to sidestep it.
- JDK 25 isn't necessarily installed locally; `settings.gradle.kts` applies the
  `org.gradle.toolchains.foojay-resolver-convention` plugin so Gradle can auto-provision it. If
  toolchain resolution ever fails with "Toolchain download repositories have not been configured",
  that plugin is what's missing.

## Architecture notes

- **Two Redis connection factories, both owned explicitly** (`RedisBusConfig`): one for blocking
  `XREADGROUP ... BLOCK` calls (pooled, sized via `plaza.pubsub.blocking-pool-size`), one for
  everything else (`@Primary`, so unqualified injections elsewhere — e.g. actuator health
  indicators — resolve unambiguously). Do not add a bare `RedisConnectionFactory`/
  `LettuceConnectionFactory` `@Bean` anywhere else: defining *any* bean assignable to
  `RedisConnectionFactory` makes Spring Boot's own auto-configured one back off entirely
  (`@ConditionalOnMissingBean`), which is what forced this file to own both instead of layering one
  extra factory on top of Boot's default.
- **Virtual threads** are enabled (`spring.threads.virtual.enabled: true`), so tool handlers can
  block imperatively (e.g. `receive`'s long-poll) without reactive/WebFlux code. The real
  concurrency ceiling is the blocking connection pool size, not JVM thread count.
- **MCP tools** are plain `@Component` classes with `@McpTool`/`@McpToolParam` methods
  (`org.springframework.ai.mcp.annotation`), auto-registered by component scanning — no manual
  `ToolCallbackProvider` bean needed. See `PubSubTools`.
- `RedisStreamBus` is the only class talking to Redis directly; `PubSubTools` is a thin adapter
  from MCP tool signatures to it.
- Test dependencies (`spring-boot-starter-actuator-test`, `spring-boot-starter-data-redis-test`)
  only pull in Spring's test-slice autoconfiguration, *not* Testcontainers — integration tests
  that need a real Redis (`RedisStreamBusIT`) additionally declare
  `spring-boot-testcontainers` + `org.testcontainers:testcontainers-junit-jupiter` (note: the
  Testcontainers 2.x JUnit 5 module artifact is `testcontainers-junit-jupiter`, not
  `junit-jupiter` — the latter resolves to nothing under Boot's BOM). Requires Docker to run.

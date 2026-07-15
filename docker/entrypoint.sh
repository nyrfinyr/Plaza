#!/bin/sh
# Supervises redis-server and the Plaza jar as the container's only two processes:
# starts Redis, waits for it to accept connections, then runs the app in the
# foreground, and on SIGTERM/SIGINT shuts both down in order (app first, then a
# saving Redis shutdown) so an orderly `docker stop` doesn't drop in-flight data.

REDIS_PID=""
JAVA_PID=""

shutdown() {
    trap '' TERM INT
    if [ -n "$JAVA_PID" ] && kill -0 "$JAVA_PID" 2>/dev/null; then
        kill -TERM "$JAVA_PID" 2>/dev/null
        wait "$JAVA_PID" 2>/dev/null
    fi
    if [ -n "$REDIS_PID" ] && kill -0 "$REDIS_PID" 2>/dev/null; then
        redis-cli -h 127.0.0.1 -p 6379 SHUTDOWN SAVE >/dev/null 2>&1
        wait "$REDIS_PID" 2>/dev/null
    fi
}

on_term() {
    shutdown
    exit 0
}
trap on_term TERM INT

redis-server /etc/redis/redis.conf &
REDIS_PID=$!

i=0
until redis-cli -h 127.0.0.1 -p 6379 PING >/dev/null 2>&1; do
    if ! kill -0 "$REDIS_PID" 2>/dev/null; then
        echo "redis-server exited before becoming ready" >&2
        exit 1
    fi
    i=$((i + 1))
    if [ "$i" -ge 30 ]; then
        echo "redis-server did not become ready within 30s" >&2
        exit 1
    fi
    sleep 1
done

java -jar /app/app.jar &
JAVA_PID=$!
wait "$JAVA_PID"
JAVA_EXIT=$?

shutdown
exit "$JAVA_EXIT"

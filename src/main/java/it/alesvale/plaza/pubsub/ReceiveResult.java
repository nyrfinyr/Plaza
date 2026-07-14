package it.alesvale.plaza.pubsub;

import java.util.List;

public record ReceiveResult(List<Message> messages, boolean timedOut) {
}

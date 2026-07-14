package it.alesvale.plaza.pubsub;

public record Message(String id, String sender, long ts, String payload) {
}

package multiuserpaint.common;

public class Message {
    private final MessageType type;
    private final byte[] payload;

    private Message(MessageType type, byte[] payload) {
        this.type = type;
        this.payload = payload;
    }

    public static Message of(MessageType type, byte[] payload) {
        return new Message(type, payload != null ? payload : new byte[0]);
    }

    public static Message of(MessageType type) {
        return new Message(type, new byte[0]);
    }

    public MessageType getType() { return type; }
    public byte[] getPayload()  { return payload; }

    @Override
    public String toString() {
        return "Message{type=" + type + ", payloadLen=" + payload.length + "}";
    }
}

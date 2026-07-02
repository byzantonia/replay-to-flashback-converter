package dev.replayconverter;

public record McprPacket(int timestampMillis, byte[] payload) {
    public McprPacket {
        if (timestampMillis < 0) throw new IllegalArgumentException("Negative MCPR timestamp");
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}

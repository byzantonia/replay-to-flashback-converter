package dev.replayconverter;

public final class TickScheduler {
    private TickScheduler() {}

    public static int tickAt(int timestampMillis) {
        if (timestampMillis < 0) throw new IllegalArgumentException("Negative timestamp");
        return timestampMillis / 50;
    }
}

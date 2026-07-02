package dev.replayconverter;

import java.nio.ByteBuffer;
import java.util.List;

public final class CoreSelfTest {
    public static void main(String[] args) throws Exception {
        if (TickScheduler.tickAt(0) != 0 || TickScheduler.tickAt(49) != 0 || TickScheduler.tickAt(50) != 1) {
            throw new AssertionError("Tick conversion failed");
        }
        FlashbackActionWriter writer = new FlashbackActionWriter(List.of(
                FlashbackActionWriter.NEXT_TICK, FlashbackActionWriter.GAME_PACKET));
        writer.snapshotAction(FlashbackActionWriter.GAME_PACKET, new byte[]{1, 2});
        writer.action(FlashbackActionWriter.NEXT_TICK, new byte[0]);
        byte[] result = writer.finish();
        if (ByteBuffer.wrap(result).getInt() != FlashbackActionWriter.MAGIC) throw new AssertionError("Magic mismatch");
        System.out.println("Core self-test passed (" + result.length + " bytes)");
    }
}

package dev.replayconverter;

import java.io.ByteArrayOutputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class FlashbackActionWriter implements AutoCloseable {
    public static final int MAGIC = 0xD780E884;
    public static final String NEXT_TICK = "flashback:action/next_tick";
    public static final String GAME_PACKET = "flashback:action/game_packet";
    public static final String CONFIGURATION_PACKET = "flashback:action/configuration_packet";
    public static final String CREATE_LOCAL_PLAYER = "flashback:action/create_local_player";
    public static final String LEVEL_CHUNK_CACHED = "flashback:action/level_chunk_cached";
    public static final String MOVE_ENTITIES = "flashback:action/move_entities";
    public static final String SIMPLE_VOICE_CHAT_SOUND_OPTIONAL = "flashback:action/simple_voice_chat_sound_optional";
    private static final int TICKS_PER_CHUNK = 5 * 60 * 20;

    private final List<String> actionTable;
    private final Map<String, Integer> actionIds = new LinkedHashMap<>();
    private final ByteArrayOutputStream snapshotBytes = new ByteArrayOutputStream();
    private final SnapshotState replayState = new SnapshotState();
    private final List<TimelineChunk> completedChunks = new ArrayList<>();
    private Path timelineFile;
    private OutputStream timelineBytes;
    private byte[] currentChunkSnapshot;
    private int ticksInChunk;
    private boolean rotateBeforeNextAction;
    private boolean prepared;

    public FlashbackActionWriter(List<String> actionTable) {
        this.actionTable = List.copyOf(actionTable);
        for (int i = 0; i < actionTable.size(); i++) {
            if (actionIds.put(actionTable.get(i), i) != null) throw new IllegalArgumentException("Duplicate action: " + actionTable.get(i));
        }
        try {
            openTimelineFile();
        } catch (IOException error) {
            throw new UncheckedIOException("Unable to create temporary timeline file", error);
        }
    }

    public void snapshotAction(String name, byte[] payload) throws IOException {
        writeAction(snapshotBytes, name, payload);
    }

    public void action(String name, byte[] payload) throws IOException {
        if (prepared) throw new IllegalStateException("Replay actions have already been finalized");
        if (rotateBeforeNextAction) rotateTimelineFile();
        if (currentChunkSnapshot == null) currentChunkSnapshot = buildCombinedSnapshot();
        writeAction(timelineBytes, name, payload);
        if (NEXT_TICK.equals(name)) {
            ticksInChunk++;
            rotateBeforeNextAction = ticksInChunk >= TICKS_PER_CHUNK;
        } else if (!SIMPLE_VOICE_CHAT_SOUND_OPTIONAL.equals(name)) {
            replayState.accept(name, payload);
        }
    }

    private void writeAction(OutputStream target, String name, byte[] payload) throws IOException {
        Integer id = actionIds.get(name);
        if (id == null) throw new IllegalArgumentException("Action is not in table: " + name);
        writeVarInt(target, id);
        new DataOutputStream(target).writeInt(payload.length);
        target.write(payload);
    }

    public byte[] finish() throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        prepareChunks();
        if (completedChunks.size() != 1) throw new IOException("Replay contains multiple Flashback chunks");
        writeChunkTo(0, result);
        return result.toByteArray();
    }

    public void prepareChunks() throws IOException {
        if (prepared) return;
        timelineBytes.close();
        byte[] snapshot = currentChunkSnapshot != null ? currentChunkSnapshot : buildCombinedSnapshot();
        completedChunks.add(new TimelineChunk(timelineFile, ticksInChunk, snapshot));
        prepared = true;
    }

    public int chunkCount() {
        if (!prepared) throw new IllegalStateException("Replay chunks have not been finalized");
        return completedChunks.size();
    }

    public int chunkDuration(int index) {
        if (!prepared) throw new IllegalStateException("Replay chunks have not been finalized");
        return completedChunks.get(index).ticks();
    }

    public void writeChunkTo(int index, OutputStream output) throws IOException {
        if (!prepared) throw new IllegalStateException("Replay chunks have not been finalized");
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(MAGIC);
        writeVarInt(output, actionTable.size());
        for (String action : actionTable) writeString(output, action);
        byte[] snapshot = completedChunks.get(index).snapshot();
        data.writeInt(snapshot.length);
        output.write(snapshot);
        Files.copy(completedChunks.get(index).path(), output);
    }

    @Override
    public void close() throws IOException {
        if (!prepared) timelineBytes.close();
        IOException failure = null;
        for (TimelineChunk chunk : completedChunks) {
            try {
                Files.deleteIfExists(chunk.path());
            } catch (IOException error) {
                failure = error;
            }
        }
        if (!prepared) Files.deleteIfExists(timelineFile);
        if (failure != null) throw failure;
    }

    private void rotateTimelineFile() throws IOException {
        timelineBytes.close();
        byte[] snapshot = currentChunkSnapshot != null ? currentChunkSnapshot : buildCombinedSnapshot();
        completedChunks.add(new TimelineChunk(timelineFile, ticksInChunk, snapshot));
        ticksInChunk = 0;
        rotateBeforeNextAction = false;
        openTimelineFile();
        currentChunkSnapshot = buildCombinedSnapshot();
    }

    private byte[] buildCombinedSnapshot() {
        byte[] initial = snapshotBytes.toByteArray();
        byte[] replayStateBytes = replayState.toByteArray();
        byte[] combined = new byte[initial.length + replayStateBytes.length];
        System.arraycopy(initial, 0, combined, 0, initial.length);
        System.arraycopy(replayStateBytes, 0, combined, initial.length, replayStateBytes.length);
        return combined;
    }

    private void openTimelineFile() throws IOException {
        timelineFile = Files.createTempFile("flashback-converter-timeline-", ".bin");
        timelineFile.toFile().deleteOnExit();
        timelineBytes = new BufferedOutputStream(Files.newOutputStream(timelineFile), 1024 * 1024);
    }

    static void writeString(OutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.write(bytes);
    }

    static void writeVarInt(OutputStream output, int value) throws IOException {
        do {
            int part = value & 0x7f;
            value >>>= 7;
            if (value != 0) part |= 0x80;
            output.write(part);
        } while (value != 0);
    }

    private record TimelineChunk(Path path, int ticks, byte[] snapshot) {}

    private final class SnapshotState {
        private final ByteArrayOutputStream actions = new ByteArrayOutputStream();
        private final Map<String, Map<Integer, byte[]>> entityMovements = new LinkedHashMap<>();

        void accept(String name, byte[] payload) throws IOException {
            if (MOVE_ENTITIES.equals(name) && rememberMoveEntities(payload)) {
                return;
            }
            writeAction(actions, name, payload);
        }

        byte[] toByteArray() {
            try {
                ByteArrayOutputStream result = new ByteArrayOutputStream(actions.size() + entityMovements.size() * 64);
                actions.writeTo(result);
                for (Map.Entry<String, Map<Integer, byte[]>> dimension : entityMovements.entrySet()) {
                    ByteArrayOutputStream payload = new ByteArrayOutputStream(64 + dimension.getValue().size() * 40);
                    writeVarInt(payload, 1);
                    writeString(payload, dimension.getKey());
                    writeVarInt(payload, dimension.getValue().size());
                    for (Map.Entry<Integer, byte[]> entity : dimension.getValue().entrySet()) {
                        writeVarInt(payload, entity.getKey());
                        payload.write(entity.getValue());
                    }
                    writeAction(result, MOVE_ENTITIES, payload.toByteArray());
                }
                return result.toByteArray();
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        private boolean rememberMoveEntities(byte[] payload) {
            try {
                PacketCursor in = new PacketCursor(payload);
                int dimensions = in.varInt();
                for (int d = 0; d < dimensions; d++) {
                    String dimension = in.stringValue();
                    Map<Integer, byte[]> entities = entityMovements.computeIfAbsent(dimension, ignored -> new LinkedHashMap<>());
                    int entityCount = in.varInt();
                    for (int e = 0; e < entityCount; e++) {
                        int entityId = in.varInt();
                        int start = in.position();
                        in.skip(Double.BYTES * 3 + Float.BYTES * 3 + 1);
                        entities.put(entityId, Arrays.copyOfRange(payload, start, in.position()));
                    }
                }
                return in.position() == payload.length;
            } catch (RuntimeException ignored) {
                return false;
            }
        }
    }

    private static final class PacketCursor {
        private final byte[] bytes;
        private int position;

        PacketCursor(byte[] bytes) {
            this.bytes = bytes;
        }

        int position() {
            return position;
        }

        void skip(int count) {
            if (count < 0 || position + count > bytes.length) throw new IllegalArgumentException("Packet underflow");
            position += count;
        }

        int varInt() {
            int value = 0;
            int shift = 0;
            while (shift < 35) {
                if (position >= bytes.length) throw new IllegalArgumentException("Packet underflow");
                int b = bytes[position++] & 0xff;
                value |= (b & 0x7f) << shift;
                if ((b & 0x80) == 0) return value;
                shift += 7;
            }
            throw new IllegalArgumentException("VarInt too large");
        }

        String stringValue() {
            int length = varInt();
            if (length < 0 || position + length > bytes.length) throw new IllegalArgumentException("Packet underflow");
            String value = new String(bytes, position, length, StandardCharsets.UTF_8);
            position += length;
            return value;
        }
    }
}

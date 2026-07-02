package dev.replayconverter;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FlashbackActionWriter {
    public static final int MAGIC = 0xD780E884;
    public static final String NEXT_TICK = "flashback:action/next_tick";
    public static final String GAME_PACKET = "flashback:action/game_packet";
    public static final String CONFIGURATION_PACKET = "flashback:action/configuration_packet";
    public static final String CREATE_LOCAL_PLAYER = "flashback:action/create_local_player";
    public static final String LEVEL_CHUNK_CACHED = "flashback:action/level_chunk_cached";
    public static final String MOVE_ENTITIES = "flashback:action/move_entities";
    public static final String SIMPLE_VOICE_CHAT_SOUND_OPTIONAL = "flashback:action/simple_voice_chat_sound_optional";

    private final List<String> actionTable;
    private final Map<String, Integer> actionIds = new LinkedHashMap<>();
    private final ByteArrayOutputStream snapshotBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream timelineBytes = new ByteArrayOutputStream();

    public FlashbackActionWriter(List<String> actionTable) {
        this.actionTable = List.copyOf(actionTable);
        for (int i = 0; i < actionTable.size(); i++) {
            if (actionIds.put(actionTable.get(i), i) != null) throw new IllegalArgumentException("Duplicate action: " + actionTable.get(i));
        }
    }

    public void snapshotAction(String name, byte[] payload) throws IOException {
        writeAction(snapshotBytes, name, payload);
    }

    public void action(String name, byte[] payload) throws IOException {
        writeAction(timelineBytes, name, payload);
    }

    private void writeAction(ByteArrayOutputStream target, String name, byte[] payload) throws IOException {
        Integer id = actionIds.get(name);
        if (id == null) throw new IllegalArgumentException("Action is not in table: " + name);
        writeVarInt(target, id);
        new DataOutputStream(target).writeInt(payload.length);
        target.write(payload);
    }

    public byte[] finish() throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(result);
        data.writeInt(MAGIC);
        writeVarInt(result, actionTable.size());
        for (String action : actionTable) writeString(result, action);
        data.writeInt(snapshotBytes.size());
        snapshotBytes.writeTo(result);
        timelineBytes.writeTo(result);
        return result.toByteArray();
    }

    static void writeString(ByteArrayOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.write(bytes);
    }

    static void writeVarInt(ByteArrayOutputStream output, int value) {
        do {
            int part = value & 0x7f;
            value >>>= 7;
            if (value != 0) part |= 0x80;
            output.write(part);
        } while (value != 0);
    }
}

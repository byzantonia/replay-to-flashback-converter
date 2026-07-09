package dev.replayconverter;

import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_20_5;
import com.viaversion.viaversion.protocols.v1_20_3to1_20_5.packet.ClientboundPackets1_20_5;

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
        private final List<SnapshotActionSlot> compactActionSlots = new ArrayList<>();
        private final Map<String, SnapshotAction> compactActions = new LinkedHashMap<>();
        private final Map<String, Map<Integer, byte[]>> entityMovements = new LinkedHashMap<>();

        void accept(String name, byte[] payload) throws IOException {
            if (MOVE_ENTITIES.equals(name) && rememberMoveEntities(payload)) {
                return;
            }

            SnapshotActionKey key = keyForSnapshotAction(name, payload);
            for (Integer removedEntityId : key.removedEntityIds) {
                removeEntityState(removedEntityId);
            }
            if (key.drop) {
                return;
            }
            if (key.value == null) {
                return;
            }
            if (!compactActions.containsKey(key.value)) {
                compactActionSlots.add(new SnapshotActionSlot(key.value));
            }
            if (isSetEquipmentKey(key.value)) {
                SnapshotAction existing = compactActions.get(key.value);
                if (existing != null) {
                    payload = EquipmentPackets.merge(existing.payload, payload);
                }
            }
            compactActions.put(key.value, new SnapshotAction(name, payload));
        }

        byte[] toByteArray() {
            try {
                ByteArrayOutputStream result = new ByteArrayOutputStream(compactActions.size() * 64 + entityMovements.size() * 64);
                for (SnapshotActionSlot slot : compactActionSlots) {
                    SnapshotAction action = compactActions.get(slot.key);
                    if (action != null) {
                        writeAction(result, action.name, action.payload);
                    }
                }
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

        private SnapshotActionKey keyForSnapshotAction(String name, byte[] payload) {
            if (CONFIGURATION_PACKET.equals(name)) {
                return SnapshotActionKey.keep(name + ":" + compactActionSlots.size());
            }
            if (LEVEL_CHUNK_CACHED.equals(name)) {
                return SnapshotActionKey.keep(name + ":" + compactActionSlots.size());
            }
            if (!GAME_PACKET.equals(name)) {
                return SnapshotActionKey.DROP;
            }
            return keyForSnapshotGamePacket(payload);
        }

        private SnapshotActionKey keyForSnapshotGamePacket(byte[] packet) {
            try {
                PacketCursor in = new PacketCursor(packet);
                int type = in.varInt();
                if (type == ClientboundPackets1_20_5.REMOVE_ENTITIES.getId()) {
                    int count = in.varInt();
                    ArrayList<Integer> ids = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) ids.add(in.varInt());
                    return SnapshotActionKey.removeEntities(ids);
                }
                if (type == ClientboundPackets1_20_5.TAKE_ITEM_ENTITY.getId()) {
                    return SnapshotActionKey.removeEntity(in.varInt());
                }
                if (isTransientSnapshotPacket(type)) {
                    return SnapshotActionKey.DROP;
                }
                if (type == ClientboundPackets1_20_5.LEVEL_CHUNK_WITH_LIGHT.getId()
                        || type == ClientboundPackets1_20_5.LIGHT_UPDATE.getId()
                        || type == ClientboundPackets1_20_5.CHUNKS_BIOMES.getId()) {
                    return SnapshotActionKey.keep(GAME_PACKET + ":" + type + ":chunk:" + in.intValue() + ":" + in.intValue());
                }
                if (type == ClientboundPackets1_20_5.SET_ENTITY_DATA.getId()) {
                    return SnapshotActionKey.keep(GAME_PACKET + ":" + type + ":entity:" + in.varInt()
                            + ":packet:" + compactActionSlots.size());
                }
                if (type == ClientboundPackets1_20_5.ADD_ENTITY.getId()) {
                    int entityId = in.varInt();
                    in.skip(16); // UUID
                    int entityTypeId = in.varInt();
                    if (isFishingBobberEntityType(entityTypeId)) {
                        return SnapshotActionKey.removeEntity(entityId);
                    }
                    return SnapshotActionKey.keep(GAME_PACKET + ":" + type + ":entity:" + entityId);
                }
                if (type == ClientboundPackets1_20_5.SET_ENTITY_MOTION.getId()
                        || type == ClientboundPackets1_20_5.SET_EQUIPMENT.getId()
                        || type == ClientboundPackets1_20_5.SET_PASSENGERS.getId()
                        || type == ClientboundPackets1_20_5.TELEPORT_ENTITY.getId()
                        || type == ClientboundPackets1_20_5.UPDATE_ATTRIBUTES.getId()) {
                    return SnapshotActionKey.keep(GAME_PACKET + ":" + type + ":entity:" + in.varInt());
                }
                if (type == ClientboundPackets1_20_5.UPDATE_MOB_EFFECT.getId()
                        || type == ClientboundPackets1_20_5.REMOVE_MOB_EFFECT.getId()) {
                    int entityId = in.varInt();
                    int effectId = in.varInt();
                    return SnapshotActionKey.keep(GAME_PACKET + ":" + type + ":effect:" + entityId + ":" + effectId);
                }
                if (type == ClientboundPackets1_20_5.PLAYER_INFO_UPDATE.getId()) {
                    int actions = in.unsignedByte();
                    int entries = in.varInt();
                    if (entries == 1) {
                        long most = in.longValue();
                        long least = in.longValue();
                        return SnapshotActionKey.keep(GAME_PACKET + ":" + type + ":player_info:" + actions + ":" + most + ":" + least);
                    }
                    return SnapshotActionKey.keep(GAME_PACKET + ":" + type + ":multi:" + compactActionSlots.size());
                }
                if (type == ClientboundPackets1_20_5.BOSS_EVENT.getId()) {
                    return SnapshotActionKey.keep(GAME_PACKET + ":" + type + ":boss:" + in.longValue() + ":" + in.longValue());
                }
                if (type == ClientboundPackets1_20_5.BLOCK_ENTITY_DATA.getId()
                        || type == ClientboundPackets1_20_5.BLOCK_UPDATE.getId()) {
                    return SnapshotActionKey.keep(GAME_PACKET + ":" + type + ":block:" + in.longValue());
                }
                if (type == ClientboundPackets1_20_5.SECTION_BLOCKS_UPDATE.getId()) {
                    return SnapshotActionKey.keep(GAME_PACKET + ":" + type + ":section:" + in.longValue() + ":" + compactActionSlots.size());
                }
                if (type == ClientboundPackets1_20_5.MAP_ITEM_DATA.getId()) {
                    return SnapshotActionKey.keep(GAME_PACKET + ":" + type + ":map:" + in.varInt());
                }
                if (type == ClientboundPackets1_20_5.SET_PLAYER_TEAM.getId()
                        || type == ClientboundPackets1_20_5.SET_OBJECTIVE.getId()) {
                    return SnapshotActionKey.keep(GAME_PACKET + ":" + type + ":named:" + in.stringValue());
                }
                if (type == ClientboundPackets1_20_5.SET_DISPLAY_OBJECTIVE.getId()) {
                    return SnapshotActionKey.keep(GAME_PACKET + ":" + type + ":slot:" + in.varInt());
                }
                if (type == ClientboundPackets1_20_5.SET_SCORE.getId()) {
                    String owner = in.stringValue();
                    String objective = in.stringValue();
                    return SnapshotActionKey.keep(GAME_PACKET + ":" + type + ":score:" + owner + ":" + objective);
                }
                if (type == ClientboundPackets1_20_5.GAME_EVENT.getId()
                        || type == ClientboundPackets1_20_5.INITIALIZE_BORDER.getId()
                        || type == ClientboundPackets1_20_5.LOGIN.getId()
                        || type == ClientboundPackets1_20_5.RESOURCE_PACK_POP.getId()
                        || type == ClientboundPackets1_20_5.RESOURCE_PACK_PUSH.getId()
                        || type == ClientboundPackets1_20_5.RESPAWN.getId()
                        || type == ClientboundPackets1_20_5.SERVER_DATA.getId()
                        || type == ClientboundPackets1_20_5.SET_BORDER_CENTER.getId()
                        || type == ClientboundPackets1_20_5.SET_BORDER_LERP_SIZE.getId()
                        || type == ClientboundPackets1_20_5.SET_BORDER_SIZE.getId()
                        || type == ClientboundPackets1_20_5.SET_BORDER_WARNING_DELAY.getId()
                        || type == ClientboundPackets1_20_5.SET_BORDER_WARNING_DISTANCE.getId()
                        || type == ClientboundPackets1_20_5.SET_DEFAULT_SPAWN_POSITION.getId()
                        || type == ClientboundPackets1_20_5.SET_SIMULATION_DISTANCE.getId()
                        || type == ClientboundPackets1_20_5.SET_TIME.getId()
                        || type == ClientboundPackets1_20_5.TAB_LIST.getId()
                        || type == ClientboundPackets1_20_5.UPDATE_TAGS.getId()) {
                    return SnapshotActionKey.keep(GAME_PACKET + ":" + type);
                }
                return SnapshotActionKey.DROP;
            } catch (RuntimeException ignored) {
                return SnapshotActionKey.DROP;
            }
        }

        private boolean isTransientSnapshotPacket(int type) {
            return type == ClientboundPackets1_20_5.ANIMATE.getId()
                    || type == ClientboundPackets1_20_5.ADD_EXPERIENCE_ORB.getId()
                    || type == ClientboundPackets1_20_5.BLOCK_DESTRUCTION.getId()
                    || type == ClientboundPackets1_20_5.BLOCK_EVENT.getId()
                    || type == ClientboundPackets1_20_5.DAMAGE_EVENT.getId()
                    || type == ClientboundPackets1_20_5.DISGUISED_CHAT.getId()
                    || type == ClientboundPackets1_20_5.ENTITY_EVENT.getId()
                    || type == ClientboundPackets1_20_5.EXPLODE.getId()
                    || type == ClientboundPackets1_20_5.HURT_ANIMATION.getId()
                    || type == ClientboundPackets1_20_5.LEVEL_EVENT.getId()
                    || type == ClientboundPackets1_20_5.LEVEL_PARTICLES.getId()
                    || type == ClientboundPackets1_20_5.PLAYER_INFO_REMOVE.getId()
                    || type == ClientboundPackets1_20_5.RESET_SCORE.getId()
                    || type == ClientboundPackets1_20_5.SET_ACTION_BAR_TEXT.getId()
                    || type == ClientboundPackets1_20_5.SET_SUBTITLE_TEXT.getId()
                    || type == ClientboundPackets1_20_5.SET_TITLE_TEXT.getId()
                    || type == ClientboundPackets1_20_5.SET_TITLES_ANIMATION.getId()
                    || type == ClientboundPackets1_20_5.SOUND.getId()
                    || type == ClientboundPackets1_20_5.SOUND_ENTITY.getId()
                    || type == ClientboundPackets1_20_5.STOP_SOUND.getId()
                    || type == ClientboundPackets1_20_5.SYSTEM_CHAT.getId();
        }

        private boolean isSetEquipmentKey(String key) {
            return key.startsWith(GAME_PACKET + ":" + ClientboundPackets1_20_5.SET_EQUIPMENT.getId() + ":entity:");
        }

        private boolean isFishingBobberEntityType(int entityTypeId) {
            return EntityTypes1_20_5.getTypeFromId(entityTypeId) == EntityTypes1_20_5.FISHING_BOBBER;
        }

        private void removeEntityState(int entityId) {
            compactActions.remove(GAME_PACKET + ":" + ClientboundPackets1_20_5.ADD_ENTITY.getId() + ":entity:" + entityId);
            compactActions.keySet().removeIf(key -> key.startsWith(
                    GAME_PACKET + ":" + ClientboundPackets1_20_5.SET_ENTITY_DATA.getId() + ":entity:" + entityId + ":packet:"));
            compactActions.remove(GAME_PACKET + ":" + ClientboundPackets1_20_5.SET_ENTITY_MOTION.getId() + ":entity:" + entityId);
            compactActions.remove(GAME_PACKET + ":" + ClientboundPackets1_20_5.SET_EQUIPMENT.getId() + ":entity:" + entityId);
            compactActions.entrySet().removeIf(entry -> isSetPassengersActionForEntity(entry.getKey(), entry.getValue().payload, entityId));
            compactActions.remove(GAME_PACKET + ":" + ClientboundPackets1_20_5.TELEPORT_ENTITY.getId() + ":entity:" + entityId);
            compactActions.remove(GAME_PACKET + ":" + ClientboundPackets1_20_5.UPDATE_ATTRIBUTES.getId() + ":entity:" + entityId);
            for (Map<Integer, byte[]> entities : entityMovements.values()) {
                entities.remove(entityId);
            }
        }

        private boolean isSetPassengersActionForEntity(String key, byte[] packet, int entityId) {
            if (!key.startsWith(GAME_PACKET + ":" + ClientboundPackets1_20_5.SET_PASSENGERS.getId() + ":entity:")) return false;
            try {
                PacketCursor in = new PacketCursor(packet);
                if (in.varInt() != ClientboundPackets1_20_5.SET_PASSENGERS.getId()) return false;
                if (in.varInt() == entityId) return true;
                int passengers = in.varInt();
                for (int i = 0; i < passengers; i++) {
                    if (in.varInt() == entityId) return true;
                }
                return false;
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

        long longValue() {
            if (position + Long.BYTES > bytes.length) throw new IllegalArgumentException("Packet underflow");
            long value = 0;
            for (int i = 0; i < Long.BYTES; i++) {
                value = (value << 8) | (bytes[position++] & 0xffL);
            }
            return value;
        }

        int intValue() {
            if (position + Integer.BYTES > bytes.length) throw new IllegalArgumentException("Packet underflow");
            int value = 0;
            for (int i = 0; i < Integer.BYTES; i++) {
                value = (value << 8) | (bytes[position++] & 0xff);
            }
            return value;
        }

        int unsignedByte() {
            if (position >= bytes.length) throw new IllegalArgumentException("Packet underflow");
            return bytes[position++] & 0xff;
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

    private record SnapshotAction(String name, byte[] payload) {}
    private record SnapshotActionSlot(String key) {}
    private record SnapshotActionKey(boolean drop, String value, List<Integer> removedEntityIds) {
        static final SnapshotActionKey DROP = new SnapshotActionKey(true, null, List.of());
        static SnapshotActionKey keep(String value) {
            return new SnapshotActionKey(false, value, List.of());
        }
        static SnapshotActionKey removeEntity(int entityId) {
            return new SnapshotActionKey(true, null, List.of(entityId));
        }
        static SnapshotActionKey removeEntities(List<Integer> entityIds) {
            return new SnapshotActionKey(true, null, List.copyOf(entityIds));
        }
    }
}

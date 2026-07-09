package dev.replayconverter;

import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_20_5;
import com.viaversion.viaversion.protocols.v1_20_3to1_20_5.packet.ClientboundPackets1_20_5;
import com.viaversion.viaversion.protocols.v1_20_3to1_20_5.packet.ClientboundConfigurationPackets1_20_5;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.replayconverter.viaversion.ProtocolTranslator;
import dev.replayconverter.viaversion.TranslatedPacket;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.UUID;
import java.util.Set;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public final class ReplayConverter {
    // Minecraft 1.20/1.20.1 clientbound play packet id. Some ReplayMod
    // snapshots contain truncated chunk section arrays; those chunks are not
    // usable, but should not prevent the rest of the replay being converted.
    private static final int SOURCE_LEVEL_CHUNK_WITH_LIGHT_PACKET_ID = 0x24;
    private static final int SIMPLE_VOICE_WARMUP_TICKS = 40;
    private static final int FLASHBACK_CHUNK_CACHE_SIZE = 10000;
    private static final int REPLAY_CAMERA_ENTITY_ID = 2_000_000_000;
    private static final UUID REPLAY_CAMERA_PROFILE_UUID = new UUID(0L, 1L);
    private static final Set<Integer> FLASHBACK_IGNORED_GAME_PACKET_IDS = Set.of(
            // Network framing consumed by Minecraft's packet-bundling pipeline.
            ClientboundPackets1_20_5.BUNDLE_DELIMITER.getId(),
            ClientboundPackets1_20_5.ADD_EXPERIENCE_ORB.getId(),
            ClientboundPackets1_20_5.STORE_COOKIE.getId(),
            ClientboundPackets1_20_5.DISCONNECT.getId(),
            ClientboundPackets1_20_5.PING.getId(),
            ClientboundPackets1_20_5.KEEP_ALIVE.getId(),
            ClientboundPackets1_20_5.TRANSFER.getId(),
            ClientboundPackets1_20_5.AWARD_STATS.getId(),
            ClientboundPackets1_20_5.RECIPE.getId(),
            ClientboundPackets1_20_5.OPEN_SIGN_EDITOR.getId(),
            ClientboundPackets1_20_5.BLOCK_DESTRUCTION.getId(),
            ClientboundPackets1_20_5.ROTATE_HEAD.getId(),
            ClientboundPackets1_20_5.MOVE_ENTITY_POS.getId(),
            ClientboundPackets1_20_5.MOVE_ENTITY_ROT.getId(),
            ClientboundPackets1_20_5.MOVE_ENTITY_POS_ROT.getId(),
            ClientboundPackets1_20_5.PLAYER_POSITION.getId(),
            ClientboundPackets1_20_5.PLAYER_CHAT.getId(),
            ClientboundPackets1_20_5.DELETE_CHAT.getId(),
            ClientboundPackets1_20_5.CONTAINER_CLOSE.getId(),
            ClientboundPackets1_20_5.CONTAINER_SET_CONTENT.getId(),
            ClientboundPackets1_20_5.HORSE_SCREEN_OPEN.getId(),
            ClientboundPackets1_20_5.CONTAINER_SET_DATA.getId(),
            ClientboundPackets1_20_5.CONTAINER_SET_SLOT.getId(),
            ClientboundPackets1_20_5.FORGET_LEVEL_CHUNK.getId(),
            ClientboundPackets1_20_5.PLAYER_ABILITIES.getId(),
            ClientboundPackets1_20_5.SET_CARRIED_ITEM.getId(),
            ClientboundPackets1_20_5.SET_EXPERIENCE.getId(),
            ClientboundPackets1_20_5.SET_HEALTH.getId(),
            ClientboundPackets1_20_5.TICKING_STATE.getId(),
            ClientboundPackets1_20_5.TICKING_STEP.getId(),
            ClientboundPackets1_20_5.PLAYER_COMBAT_END.getId(),
            ClientboundPackets1_20_5.PLAYER_COMBAT_ENTER.getId(),
            ClientboundPackets1_20_5.PLAYER_COMBAT_KILL.getId(),
            ClientboundPackets1_20_5.SET_CAMERA.getId(),
            ClientboundPackets1_20_5.COOLDOWN.getId(),
            ClientboundPackets1_20_5.UPDATE_ADVANCEMENTS.getId(),
            ClientboundPackets1_20_5.SELECT_ADVANCEMENTS_TAB.getId(),
            ClientboundPackets1_20_5.PLACE_GHOST_RECIPE.getId(),
            ClientboundPackets1_20_5.COMMANDS.getId(),
            ClientboundPackets1_20_5.COMMAND_SUGGESTIONS.getId(),
            ClientboundPackets1_20_5.UPDATE_RECIPES.getId(),
            ClientboundPackets1_20_5.TAG_QUERY.getId(),
            ClientboundPackets1_20_5.OPEN_BOOK.getId(),
            ClientboundPackets1_20_5.OPEN_SCREEN.getId(),
            ClientboundPackets1_20_5.MERCHANT_OFFERS.getId(),
            ClientboundPackets1_20_5.SET_CHUNK_CACHE_RADIUS.getId(),
            ClientboundPackets1_20_5.SET_SIMULATION_DISTANCE.getId(),
            ClientboundPackets1_20_5.SET_CHUNK_CACHE_CENTER.getId(),
            ClientboundPackets1_20_5.BLOCK_CHANGED_ACK.getId(),
            ClientboundPackets1_20_5.CUSTOM_CHAT_COMPLETIONS.getId(),
            ClientboundPackets1_20_5.START_CONFIGURATION.getId(),
            ClientboundPackets1_20_5.CHUNK_BATCH_START.getId(),
            ClientboundPackets1_20_5.CHUNK_BATCH_FINISHED.getId(),
            ClientboundPackets1_20_5.DEBUG_SAMPLE.getId(),
            ClientboundPackets1_20_5.PONG_RESPONSE.getId());
    private static final List<String> ACTIONS = List.of(
            FlashbackActionWriter.SIMPLE_VOICE_CHAT_SOUND_OPTIONAL,
            FlashbackActionWriter.NEXT_TICK,
            FlashbackActionWriter.GAME_PACKET,
            FlashbackActionWriter.CONFIGURATION_PACKET,
            FlashbackActionWriter.CREATE_LOCAL_PLAYER,
            FlashbackActionWriter.LEVEL_CHUNK_CACHED,
            FlashbackActionWriter.MOVE_ENTITIES);

    public ConversionResult convert(Path input, Path output) throws Exception {
        return convert(input, output, status -> {});
    }

    public ConversionResult convert(Path input, Path output, Consumer<String> progress) throws Exception {
        progress.accept("Preparing conversion...");
        List<byte[]> snapshotConfiguration = new ArrayList<>();
        List<byte[]> snapshotGame = new ArrayList<>();
        FlashbackActionWriter writer = new FlashbackActionWriter(ACTIONS);
        ChunkCacheBuilder chunkCache = new ChunkCacheBuilder();
        MovementConverter movement = new MovementConverter();
        VoiceChatConverter voiceChat = new VoiceChatConverter();
        SkinProfileExtractor skinProfiles = new SkinProfileExtractor();
        LocalPlayerTracker localPlayer = new LocalPlayerTracker();
        int sourcePackets = 0, outputPackets = 0, lastTick = 0, lastTimestamp = 0;
        int snapshotSourcePackets = 0;
        PlayerSpawn playerSpawn = null;
        boolean snapshotComplete = false;
        boolean loginSeenInSnapshot = false;
        int loginSeenTick = -1;
        String bobbyWorldName;
        String worldName;
        byte[] replayIconPng = null;

        progress.accept("Opening ReplayMod archive...");
        try (McprReader reader = new McprReader(input)) {
            progress.accept("Initializing protocol translator...");
            try (ProtocolTranslator translator = new ProtocolTranslator(viaVersionDirectory(output), 763, 767)) {
            progress.accept("Reading replay metadata and thumbnail...");
            JsonObject replayMetadata = JsonParser.parseString(reader.metadataJson()).getAsJsonObject();
            // Replay Mod playback has no live ServerData, so Bobby stores and reads
            // replay-populated chunks under its "unknown" world namespace.
            bobbyWorldName = "unknown";
            worldName = optionalString(replayMetadata, "customServerName");
            if (worldName == null) worldName = optionalString(replayMetadata, "serverName");
            replayIconPng = toPng(reader.thumbnailBytes());
            progress.accept("Translating initial replay snapshot...");
            for (McprPacket packet : reader) {
                sourcePackets++;
                if (sourcePackets % 1000 == 0) progress.accept("Translating snapshot packets: " + String.format("%,d", sourcePackets));
                lastTimestamp = packet.timestampMillis();
                int tick = TickScheduler.tickAt(lastTimestamp);
                byte[] primary = translateClientboundOrSkipMalformedChunk(
                        translator, packet, sourcePackets, progress);
                translator.finishSyntheticConfigurationIfNeeded();
                List<TranslatedPacket> generated = translator.drainInjectedPackets();

                if (primary != null) {
                    localPlayer.accept(primary);
                    skinProfiles.accept(primary);
                }
                for (TranslatedPacket translated : generated) {
                    localPlayer.accept(translated.payload());
                    skinProfiles.accept(translated.payload());
                }

                if (!snapshotComplete) {
                    if (playerSpawn == null && primary != null) playerSpawn = playerSpawnFromPacket(primary);
                    if (playerSpawn == null) {
                        for (TranslatedPacket translated : generated) {
                            playerSpawn = playerSpawnFromPacket(translated.payload());
                            if (playerSpawn != null) break;
                        }
                    }
                    if (playerSpawn == null && localPlayer.localPlayerEntityId != null) {
                        if (primary != null) {
                            playerSpawn = playerSpawnFromEntityPacket(primary, localPlayer.localPlayerEntityId);
                        }
                        if (playerSpawn == null) {
                            for (TranslatedPacket translated : generated) {
                                playerSpawn = playerSpawnFromEntityPacket(translated.payload(), localPlayer.localPlayerEntityId);
                                if (playerSpawn != null) break;
                            }
                        }
                    }
                    if (primary != null) {
                        addSnapshot(translator.clientState(), primary, snapshotConfiguration, snapshotGame);
                        if (packetId(primary) == ClientboundPackets1_20_5.LOGIN.getId()) {
                            loginSeenInSnapshot = true;
                            if (loginSeenTick < 0) loginSeenTick = tick;
                        }
                    }
                    for (TranslatedPacket translated : generated) {
                        addSnapshot(translated.state(), translated.payload(), snapshotConfiguration, snapshotGame);
                        if (packetId(translated.payload()) == ClientboundPackets1_20_5.LOGIN.getId()) {
                            loginSeenInSnapshot = true;
                            if (loginSeenTick < 0) loginSeenTick = tick;
                        }
                    }
                    boolean timedOutWaitingForSpawn = loginSeenTick >= 0 && tick - loginSeenTick >= 200;
                    snapshotComplete = loginSeenInSnapshot && (playerSpawn != null || timedOutWaitingForSpawn);
                    if (snapshotComplete) progress.accept("Initial snapshot found; reading timeline...");
                }
                if (primary != null) outputPackets++;
                outputPackets += generated.size();
                if (snapshotComplete) {
                    snapshotSourcePackets = sourcePackets;
                    break;
                }
            }
            }
        }

        progress.accept("Organizing configuration packets...");
        for (byte[] packet : snapshotConfiguration) writer.snapshotAction(FlashbackActionWriter.CONFIGURATION_PACKET, packet);
        int loginIndex = -1;
        for (int i = 0; i < snapshotGame.size(); i++) {
            if (packetId(snapshotGame.get(i)) == ClientboundPackets1_20_5.LOGIN.getId()) {
                loginIndex = i;
                break;
            }
        }
        if (loginIndex < 0) throw new IOException("Translated replay snapshot contains no login packet");

        // ReplayMod may prepend play-state housekeeping packets to its synthetic
        // snapshot. They were captured before registries/login existed, so translating
        // or replaying them is invalid; native Flashback snapshots begin at Login.
        progress.accept("Selecting the replay camera profile...");
        SkinProfile selectedProfile = skinProfiles.select(localPlayer.localPlayerUuid);
        progress.accept("Checking for the player skin...");
        selectedProfile = resolveSkinProfile(selectedProfile);
        progress.accept("Building the initial Flashback snapshot...");
        writeGamePacket(writer, chunkCache, movement, voiceChat,
                withReplayCameraLoginId(snapshotGame.get(loginIndex)), true, 0);
        writer.snapshotAction(FlashbackActionWriter.CREATE_LOCAL_PLAYER,
            createLocalPlayerPayload(input, localPlayer.localPlayerUuid, playerSpawn, selectedProfile));
        if (localPlayer.localPlayerEntityId != null) {
            writer.snapshotAction(FlashbackActionWriter.GAME_PACKET,
                createPlayerModelPartsPacket(REPLAY_CAMERA_ENTITY_ID));
        }
        for (byte[] packet : compactSnapshotGamePackets(snapshotGame.subList(loginIndex + 1, snapshotGame.size()))) {
            writeGamePacket(writer, chunkCache, movement, voiceChat, packet, true, 0);
        }
        progress.accept("Reopening replay for bounded-memory conversion...");
        sourcePackets = 0;
        outputPackets = 0;
        try (McprReader reader = new McprReader(input);
             ProtocolTranslator translator = new ProtocolTranslator(viaVersionDirectory(output), 763, 767)) {
            for (McprPacket packet : reader) {
                sourcePackets++;
                lastTimestamp = packet.timestampMillis();
                int tick = TickScheduler.tickAt(lastTimestamp);
                byte[] primary = translateClientboundOrSkipMalformedChunk(
                        translator, packet, sourcePackets, progress);
                translator.finishSyntheticConfigurationIfNeeded();
                List<TranslatedPacket> generated = translator.drainInjectedPackets();
                if (primary != null) outputPackets++;
                outputPackets += generated.size();

                if (sourcePackets <= snapshotSourcePackets) continue;
                if (sourcePackets % 1000 == 0) {
                    progress.accept("Converting timeline source packet: " + String.format("%,d", sourcePackets));
                }
                while (lastTick < tick) {
                    writePendingMovement(writer, movement, false);
                    writer.action(FlashbackActionWriter.NEXT_TICK, new byte[0]);
                    lastTick++;
                }
                if (primary != null) writeGamePacket(writer, chunkCache, movement, voiceChat,
                        withReplayCameraLoginId(primary), false, tick);
                for (TranslatedPacket translated : generated) {
                    if (translated.state() == State.CONFIGURATION) {
                        if (!isFlashbackUnsupportedConfigurationPacket(translated.payload())) {
                            writer.action(FlashbackActionWriter.CONFIGURATION_PACKET, translated.payload());
                        }
                    } else {
                        writeGamePacket(writer, chunkCache, movement, voiceChat,
                                withReplayCameraLoginId(translated.payload()), false, tick);
                    }
                }
            }
        }
        progress.accept("Finalizing replay ticks...");
        int totalTicks = Math.max(1, (lastTimestamp + 49) / 50);
        while (lastTick < totalTicks) {
            writePendingMovement(writer, movement, false);
            writer.action(FlashbackActionWriter.NEXT_TICK, new byte[0]);
            lastTick++;
        }
        progress.accept("Encoding Flashback action data...");
        try {
            FlashbackArchiveWriter.write(output, baseName(input), totalTicks, writer, chunkCache.files(),
                    bobbyWorldName, worldName, replayIconPng, progress);
        } finally {
            writer.close();
        }
        progress.accept("Conversion complete.");
        return new ConversionResult(sourcePackets, outputPackets, totalTicks, snapshotConfiguration.size(), snapshotGame.size());
    }

    private static byte[] translateClientboundOrSkipMalformedChunk(
            ProtocolTranslator translator, McprPacket packet, int sourcePacketIndex,
            Consumer<String> progress) {
        try {
            return translator.translateClientbound(packet.payload());
        } catch (RuntimeException failure) {
            if (packetId(packet.payload()) != SOURCE_LEVEL_CHUNK_WITH_LIGHT_PACKET_ID) {
                throw failure;
            }

            String warning = "Skipping malformed chunk packet " + String.format("%,d", sourcePacketIndex)
                    + " at " + packet.timestampMillis() + " ms";
            System.err.println(warning + ": " + failure);
            progress.accept(warning);
            return null;
        }
    }

    private static byte[] toPng(byte[] sourceImage) {
        if (sourceImage == null || sourceImage.length == 0) {
            return null;
        }

        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(sourceImage));
            if (image == null) {
                return null;
            }

            ByteArrayOutputStream png = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", png)) {
                return null;
            }
            return png.toByteArray();
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String optionalString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return null;
        String string = value.getAsString().trim();
        return string.isEmpty() ? null : string;
    }

    private static Path viaVersionDirectory(Path output) {
        Path replayFolder = output.toAbsolutePath().getParent();
        if (replayFolder == null) return Path.of(".viaversion");
        Path flashbackFolder = replayFolder.getParent();
        if (flashbackFolder == null) return replayFolder.resolve("..\\.viaversion").normalize();
        return flashbackFolder.resolve(".viaversion");
    }

    private static void addSnapshot(State state, byte[] packet, List<byte[]> configuration, List<byte[]> game) {
        if (state == State.CONFIGURATION) {
            if (!isFlashbackUnsupportedConfigurationPacket(packet)) configuration.add(packet);
        } else {
            if (!isFlashbackUnsupportedGameControlPacket(packet)) game.add(packet);
        }
    }

    private static boolean isFlashbackUnsupportedConfigurationPacket(byte[] packet) {
        // Flashback flushes pending configuration when the first game packet is handled;
        // replaying the wire-level terminator calls its deliberately unsupported handler.
        return packetId(packet) == ClientboundConfigurationPackets1_20_5.FINISH_CONFIGURATION.getId();
    }

    private static boolean isFlashbackUnsupportedGameControlPacket(byte[] packet) {
        return FLASHBACK_IGNORED_GAME_PACKET_IDS.contains(packetId(packet));
    }

    private static void writeGamePacket(FlashbackActionWriter writer, ChunkCacheBuilder chunks, MovementConverter movement,
                                        VoiceChatConverter voiceChat,
                                        byte[] packet, boolean snapshot, int tick) throws IOException {
        packet = withReplayCameraLoginId(packet);
        MovementResult movementResult = movement.accept(packet);
        if (movementResult.handled) {
            if (snapshot) writePendingMovement(writer, movement, true);
            return;
        }
        VoiceAction voiceAction = voiceChat.accept(packet);
        if (voiceAction.handled) {
            if (voiceAction.payload == null) return;
            if (snapshot && FlashbackActionWriter.SIMPLE_VOICE_CHAT_SOUND_OPTIONAL.equals(voiceAction.action)) {
                // Flashback recordings keep voice events out of snapshot state.
                return;
            }
            if (!snapshot
                    && FlashbackActionWriter.SIMPLE_VOICE_CHAT_SOUND_OPTIONAL.equals(voiceAction.action)
                    && tick < SIMPLE_VOICE_WARMUP_TICKS) {
                // Delay early replay voice packets to avoid startup incompatibilities.
                return;
            }
            if (snapshot) writer.snapshotAction(voiceAction.action, voiceAction.payload);
            else writer.action(voiceAction.action, voiceAction.payload);
            return;
        }
        if (isFlashbackUnsupportedGameControlPacket(packet)) return;
        String action;
        byte[] payload;
        if (packetId(packet) == ClientboundPackets1_20_5.LEVEL_CHUNK_WITH_LIGHT.getId()) {
            action = FlashbackActionWriter.LEVEL_CHUNK_CACHED;
            payload = chunks.add(packet);
        } else {
            action = FlashbackActionWriter.GAME_PACKET;
            payload = packet;
        }
        if (snapshot) writer.snapshotAction(action, payload); else writer.action(action, payload);
    }

    private static void writePendingMovement(FlashbackActionWriter writer, MovementConverter movement,
                                             boolean snapshot) throws IOException {
        byte[] payload = movement.drainPayload();
        if (payload == null) return;
        if (snapshot) writer.snapshotAction(FlashbackActionWriter.MOVE_ENTITIES, payload);
        else writer.action(FlashbackActionWriter.MOVE_ENTITIES, payload);
    }

    private static List<byte[]> compactSnapshotGamePackets(List<byte[]> packets) {
        ArrayList<SnapshotPacketSlot> slots = new ArrayList<>(packets.size());
        Map<String, byte[]> keyedPackets = new LinkedHashMap<>();
        for (int i = 0; i < packets.size(); i++) {
            byte[] packet = packets.get(i);
            SnapshotPacketKey key = snapshotPacketKey(packet, i);
            for (Integer removedEntityId : key.removedEntityIds) {
                removeSnapshotEntityState(keyedPackets, removedEntityId);
            }
            if (key.drop) continue;
            if (key.value == null) {
                slots.add(new SnapshotPacketSlot(null, packet));
            } else {
                if (!keyedPackets.containsKey(key.value)) {
                    slots.add(new SnapshotPacketSlot(key.value, null));
                }
                if (isSetEquipmentKey(key.value)) {
                    keyedPackets.merge(key.value, packet, EquipmentPackets::merge);
                } else {
                    keyedPackets.put(key.value, packet);
                }
            }
        }

        ArrayList<byte[]> compacted = new ArrayList<>(slots.size());
        for (SnapshotPacketSlot slot : slots) {
            byte[] packet = slot.key == null ? slot.payload : keyedPackets.get(slot.key);
            if (packet != null) compacted.add(packet);
        }
        return compacted;
    }

    private static void removeSnapshotEntityState(Map<String, byte[]> keyedPackets, int entityId) {
        keyedPackets.remove(ClientboundPackets1_20_5.ADD_ENTITY.getId() + ":entity:" + entityId);
        keyedPackets.keySet().removeIf(key -> key.startsWith(
                ClientboundPackets1_20_5.SET_ENTITY_DATA.getId() + ":entity:" + entityId + ":packet:"));
        keyedPackets.remove(ClientboundPackets1_20_5.SET_ENTITY_MOTION.getId() + ":entity:" + entityId);
        keyedPackets.remove(ClientboundPackets1_20_5.SET_EQUIPMENT.getId() + ":entity:" + entityId);
        keyedPackets.entrySet().removeIf(entry -> isSetPassengersPacketForEntity(entry.getKey(), entry.getValue(), entityId));
        keyedPackets.remove(ClientboundPackets1_20_5.TELEPORT_ENTITY.getId() + ":entity:" + entityId);
        keyedPackets.remove(ClientboundPackets1_20_5.UPDATE_ATTRIBUTES.getId() + ":entity:" + entityId);
    }

    private static boolean isSetPassengersPacketForEntity(String key, byte[] packet, int entityId) {
        if (!key.startsWith(ClientboundPackets1_20_5.SET_PASSENGERS.getId() + ":entity:")) return false;
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

    private static boolean isSetEquipmentKey(String key) {
        return key.startsWith(ClientboundPackets1_20_5.SET_EQUIPMENT.getId() + ":entity:");
    }

    private static SnapshotPacketKey snapshotPacketKey(byte[] packet, int packetOrdinal) {
        try {
            PacketCursor in = new PacketCursor(packet);
            int type = in.varInt();
            if (type == ClientboundPackets1_20_5.REMOVE_ENTITIES.getId()) {
                int count = in.varInt();
                ArrayList<Integer> ids = new ArrayList<>(count);
                for (int i = 0; i < count; i++) ids.add(in.varInt());
                return SnapshotPacketKey.removeEntities(ids);
            }
            if (type == ClientboundPackets1_20_5.TAKE_ITEM_ENTITY.getId()) {
                return SnapshotPacketKey.removeEntity(in.varInt());
            }
            if (isTransientSnapshotPacket(type)) {
                return SnapshotPacketKey.DROP;
            }
            if (type == ClientboundPackets1_20_5.LEVEL_CHUNK_WITH_LIGHT.getId()
                    || type == ClientboundPackets1_20_5.LIGHT_UPDATE.getId()
                    || type == ClientboundPackets1_20_5.CHUNKS_BIOMES.getId()) {
                return SnapshotPacketKey.keep(type + ":chunk:" + in.intValue() + ":" + in.intValue());
            }
            if (type == ClientboundPackets1_20_5.SET_ENTITY_DATA.getId()) {
                return SnapshotPacketKey.keep(type + ":entity:" + in.varInt() + ":packet:" + packetOrdinal);
            }
            if (type == ClientboundPackets1_20_5.ADD_ENTITY.getId()) {
                int entityId = in.varInt();
                in.skip(16); // UUID
                int entityTypeId = in.varInt();
                if (isFishingBobberEntityType(entityTypeId)) {
                    return SnapshotPacketKey.removeEntity(entityId);
                }
                return SnapshotPacketKey.keep(type + ":entity:" + entityId);
            }
            if (type == ClientboundPackets1_20_5.SET_ENTITY_MOTION.getId()
                    || type == ClientboundPackets1_20_5.SET_EQUIPMENT.getId()
                    || type == ClientboundPackets1_20_5.SET_PASSENGERS.getId()
                    || type == ClientboundPackets1_20_5.TELEPORT_ENTITY.getId()
                    || type == ClientboundPackets1_20_5.UPDATE_ATTRIBUTES.getId()) {
                return SnapshotPacketKey.keep(type + ":entity:" + in.varInt());
            }
            if (type == ClientboundPackets1_20_5.UPDATE_MOB_EFFECT.getId()
                    || type == ClientboundPackets1_20_5.REMOVE_MOB_EFFECT.getId()) {
                int entityId = in.varInt();
                int effectId = in.varInt();
                return SnapshotPacketKey.keep(type + ":effect:" + entityId + ":" + effectId);
            }
            if (type == ClientboundPackets1_20_5.PLAYER_INFO_UPDATE.getId()) {
                int actions = in.unsignedByte();
                int entries = in.varInt();
                if (entries == 1) {
                    UUID uuid = new UUID(in.longValue(), in.longValue());
                    return SnapshotPacketKey.keep(type + ":player_info:" + actions + ":" + uuid);
                }
                return SnapshotPacketKey.KEEP;
            }
            if (type == ClientboundPackets1_20_5.BOSS_EVENT.getId()) {
                UUID uuid = new UUID(in.longValue(), in.longValue());
                return SnapshotPacketKey.keep(type + ":boss:" + uuid);
            }
            if (type == ClientboundPackets1_20_5.BLOCK_ENTITY_DATA.getId()
                    || type == ClientboundPackets1_20_5.BLOCK_UPDATE.getId()) {
                return SnapshotPacketKey.keep(type + ":block:" + in.longValue());
            }
            if (type == ClientboundPackets1_20_5.SECTION_BLOCKS_UPDATE.getId()) {
                return SnapshotPacketKey.keep(type + ":section:" + in.longValue() + ":" + packetOrdinal);
            }
            if (type == ClientboundPackets1_20_5.MAP_ITEM_DATA.getId()) {
                return SnapshotPacketKey.keep(type + ":map:" + in.varInt());
            }
            if (type == ClientboundPackets1_20_5.SET_PLAYER_TEAM.getId()
                    || type == ClientboundPackets1_20_5.SET_OBJECTIVE.getId()) {
                return SnapshotPacketKey.keep(type + ":named:" + in.stringValue());
            }
            if (type == ClientboundPackets1_20_5.SET_DISPLAY_OBJECTIVE.getId()) {
                return SnapshotPacketKey.keep(type + ":slot:" + in.varInt());
            }
            if (type == ClientboundPackets1_20_5.SET_SCORE.getId()) {
                String owner = in.stringValue();
                String objective = in.stringValue();
                return SnapshotPacketKey.keep(type + ":score:" + owner + ":" + objective);
            }
            return SnapshotPacketKey.KEEP;
        } catch (RuntimeException ignored) {
            return SnapshotPacketKey.KEEP;
        }
    }

    private static boolean isTransientSnapshotPacket(int type) {
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

    private static final class ChunkCacheBuilder {
        private final List<byte[]> files = new ArrayList<>();
        private ByteArrayOutputStream currentFile = new ByteArrayOutputStream();
        private int count;

        byte[] add(byte[] packet) throws IOException {
            if (count > 0 && count % FLASHBACK_CHUNK_CACHE_SIZE == 0) {
                files.add(currentFile.toByteArray());
                currentFile = new ByteArrayOutputStream();
            }

            DataOutputStream out = new DataOutputStream(currentFile);
            out.writeInt(packet.length);
            currentFile.write(packet);

            ByteArrayOutputStream index = new ByteArrayOutputStream(5);
            FlashbackActionWriter.writeVarInt(index, count++);
            return index.toByteArray();
        }

        List<byte[]> files() {
            List<byte[]> result = new ArrayList<>(files.size() + 1);
            result.addAll(files);
            byte[] trailing = currentFile.toByteArray();
            if (trailing.length > 0) {
                result.add(trailing);
            }
            return result;
        }
    }

    private static final class MovementConverter {
        private final Map<Integer, EntityPosition> entities = new HashMap<>();
        private final Map<Integer, EntityPosition> pendingTickMovements = new LinkedHashMap<>();

        MovementResult accept(byte[] packet) throws IOException {
            PacketCursor in = new PacketCursor(packet);
            int type = in.varInt();
            if (type == ClientboundPackets1_20_5.ADD_ENTITY.getId()) {
                int id = in.varInt();
                in.skip(16); // UUID
                in.varInt(); // entity type
                double x = in.doubleValue(), y = in.doubleValue(), z = in.doubleValue();
                float pitch = angle(in.byteValue()), yaw = angle(in.byteValue()), head = angle(in.byteValue());
                entities.put(id, new EntityPosition(x, y, z, yaw, pitch, head, false));
                return MovementResult.NOT_HANDLED;
            }
            if (type == ClientboundPackets1_20_5.REMOVE_ENTITIES.getId()) {
                int count = in.varInt();
                for (int i = 0; i < count; i++) forget(in.varInt());
                return MovementResult.NOT_HANDLED;
            }
            if (type == ClientboundPackets1_20_5.TAKE_ITEM_ENTITY.getId()) {
                forget(in.varInt());
                return MovementResult.NOT_HANDLED;
            }
            if (type == ClientboundPackets1_20_5.TELEPORT_ENTITY.getId()) {
                int id = in.varInt();
                EntityPosition old = entities.get(id);
                double x = in.doubleValue(), y = in.doubleValue(), z = in.doubleValue();
                float yaw = angle(in.byteValue()), pitch = angle(in.byteValue());
                boolean onGround = in.booleanValue();
                entities.put(id, new EntityPosition(x, y, z, yaw, pitch, old == null ? yaw : old.headYaw, onGround));
                return MovementResult.NOT_HANDLED;
            }
            boolean position = type == ClientboundPackets1_20_5.MOVE_ENTITY_POS.getId()
                    || type == ClientboundPackets1_20_5.MOVE_ENTITY_POS_ROT.getId();
            boolean rotation = type == ClientboundPackets1_20_5.MOVE_ENTITY_ROT.getId()
                    || type == ClientboundPackets1_20_5.MOVE_ENTITY_POS_ROT.getId();
            boolean headRotation = type == ClientboundPackets1_20_5.ROTATE_HEAD.getId();
            if (!position && !rotation && !headRotation) return MovementResult.NOT_HANDLED;

            int id = in.varInt();
            EntityPosition old = entities.get(id);
            if (old == null) return MovementResult.HANDLED_WITHOUT_OUTPUT;
            double x = old.x, y = old.y, z = old.z;
            float yaw = old.yaw, pitch = old.pitch, head = old.headYaw;
            boolean onGround = old.onGround;
            if (position) {
                x += in.shortValue() / 4096.0;
                y += in.shortValue() / 4096.0;
                z += in.shortValue() / 4096.0;
            }
            if (rotation) {
                yaw = angle(in.byteValue());
                pitch = angle(in.byteValue());
            }
            if (headRotation) {
                head = angle(in.byteValue());
            } else {
                onGround = in.booleanValue();
            }
            EntityPosition updated = new EntityPosition(x, y, z, yaw, pitch, head, onGround);
            entities.put(id, updated);
            pendingTickMovements.put(id, updated);
            return MovementResult.HANDLED_WITHOUT_OUTPUT;
        }

        private void forget(int id) {
            entities.remove(id);
            pendingTickMovements.remove(id);
        }

        byte[] drainPayload() throws IOException {
            if (pendingTickMovements.isEmpty()) return null;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * pendingTickMovements.size());
            DataOutputStream data = new DataOutputStream(bytes);
            FlashbackActionWriter.writeVarInt(bytes, 1); // one dimension
            FlashbackActionWriter.writeString(bytes, "minecraft:overworld");
            FlashbackActionWriter.writeVarInt(bytes, pendingTickMovements.size());
            for (Map.Entry<Integer, EntityPosition> entry : pendingTickMovements.entrySet()) {
                EntityPosition position = entry.getValue();
                FlashbackActionWriter.writeVarInt(bytes, entry.getKey());
                data.writeDouble(position.x);
                data.writeDouble(position.y);
                data.writeDouble(position.z);
                data.writeFloat(position.yaw);
                data.writeFloat(position.pitch);
                data.writeFloat(position.headYaw);
                data.writeBoolean(position.onGround);
            }
            pendingTickMovements.clear();
            return bytes.toByteArray();
        }

        private static float angle(byte value) { return value * 360.0f / 256.0f; }
    }

    private static final class VoiceChatConverter {
        private static final String CHANNEL_ENTITY = "replayvoicechat:entity_sound";
        private static final String CHANNEL_LOCATIONAL = "replayvoicechat:locational_sound";
        private static final String CHANNEL_STATIC = "replayvoicechat:static_sound";
        private static final String CHANNEL_SIMPLE_VOICE_PREFIX = "voicechat:";
        private static final String CHANNEL_REPLAY_VOICE_PREFIX = "replayvoicechat:";
        private static final String CHANNEL_REGISTER = "minecraft:register";
        private static final String CHANNEL_UNREGISTER = "minecraft:unregister";
        private static final byte TYPE_STATIC = 0;
        private static final byte TYPE_LOCATIONAL = 1;
        private static final byte TYPE_ENTITY = 2;
        private static final float DEFAULT_DISTANCE = 48.0f;

        VoiceAction accept(byte[] packet) throws IOException {
            PacketCursor in = new PacketCursor(packet);
            if (in.varInt() != ClientboundPackets1_20_5.CUSTOM_PAYLOAD.getId()) {
                return VoiceAction.NOT_HANDLED;
            }

            String channel = in.stringValue();
            if (channel.startsWith(CHANNEL_SIMPLE_VOICE_PREFIX)) {
                return VoiceAction.HANDLED_WITHOUT_OUTPUT;
            }
            if (CHANNEL_REGISTER.equals(channel) || CHANNEL_UNREGISTER.equals(channel)) {
                byte[] sanitized = sanitizeLegacyChannelListPacket(channel, in.remainingBytes());
                if (sanitized != null) {
                    return new VoiceAction(true, FlashbackActionWriter.GAME_PACKET, sanitized);
                }
            }
            return switch (channel) {
                case CHANNEL_ENTITY -> decodeEntity(in);
                case CHANNEL_LOCATIONAL -> decodeLocational(in);
                case CHANNEL_STATIC -> decodeStatic(in);
                default -> VoiceAction.NOT_HANDLED;
            };
        }

        private static byte[] sanitizeLegacyChannelListPacket(String channel, byte[] payload) throws IOException {
            String registered = new String(payload, StandardCharsets.UTF_8);
            if (registered.isEmpty()) return null;

            String[] channels = registered.split("\\u0000", -1);
            ArrayList<String> filtered = new ArrayList<>(channels.length);
            boolean changed = false;
            for (String entry : channels) {
                boolean remove = entry.startsWith(CHANNEL_SIMPLE_VOICE_PREFIX)
                        || entry.startsWith(CHANNEL_REPLAY_VOICE_PREFIX);
                if (remove) {
                    changed = true;
                } else if (!entry.isEmpty()) {
                    filtered.add(entry);
                }
            }
            if (!changed) return null;

            String rewritten = String.join("\u0000", filtered);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(payload.length + 16);
            FlashbackActionWriter.writeVarInt(bytes, ClientboundPackets1_20_5.CUSTOM_PAYLOAD.getId());
            FlashbackActionWriter.writeString(bytes, channel);
            bytes.write(rewritten.getBytes(StandardCharsets.UTF_8));
            return bytes.toByteArray();
        }

        private static VoiceAction decodeEntity(PacketCursor in) throws IOException {
            DecodedAudio audio = decodeCommon(in);
            boolean whispering = in.booleanValue();
            float distance = audio.version >= 1 ? in.floatValue() : DEFAULT_DISTANCE;
            byte[] payload = encodeVoicePayload(audio.uuid, audio.samples, TYPE_ENTITY, out -> {
                out.writeBoolean(whispering);
                out.writeFloat(distance);
            });
            return new VoiceAction(true, FlashbackActionWriter.SIMPLE_VOICE_CHAT_SOUND_OPTIONAL, payload);
        }

        private static VoiceAction decodeLocational(PacketCursor in) throws IOException {
            DecodedAudio audio = decodeCommon(in);
            double x = in.doubleValue();
            double y = in.doubleValue();
            double z = in.doubleValue();
            float distance = audio.version >= 1 ? in.floatValue() : DEFAULT_DISTANCE;
            byte[] payload = encodeVoicePayload(audio.uuid, audio.samples, TYPE_LOCATIONAL, out -> {
                out.writeDouble(x);
                out.writeDouble(y);
                out.writeDouble(z);
                out.writeFloat(distance);
            });
            return new VoiceAction(true, FlashbackActionWriter.SIMPLE_VOICE_CHAT_SOUND_OPTIONAL, payload);
        }

        private static VoiceAction decodeStatic(PacketCursor in) throws IOException {
            DecodedAudio audio = decodeCommon(in);
            byte[] payload = encodeVoicePayload(audio.uuid, audio.samples, TYPE_STATIC, out -> {
            });
            return new VoiceAction(true, FlashbackActionWriter.SIMPLE_VOICE_CHAT_SOUND_OPTIONAL, payload);
        }

        private static DecodedAudio decodeCommon(PacketCursor in) {
            short version = in.shortValue();
            UUID uuid = new UUID(in.longValue(), in.longValue());
            byte[] encoded = in.bytes(in.varInt());
            return new DecodedAudio(version, uuid, decodeReplayAudioSamples(encoded));
        }

        private static short[] decodeReplayAudioSamples(byte[] encoded) {
            if ((encoded.length & 1) != 0) throw new IllegalArgumentException("Invalid replayvoicechat sample byte length");
            short[] samples = new short[encoded.length / 2];
            for (int i = 0, j = 0; i < samples.length; i++, j += 2) {
                int lo = encoded[j] & 0xff;
                int hi = encoded[j + 1] & 0xff;
                samples[i] = (short) ((hi << 8) | lo);
            }
            return samples;
        }

        private static byte[] encodeVoicePayload(UUID uuid, short[] samples, byte type, ExtraEncoder extra) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 + samples.length * 2);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeLong(uuid.getMostSignificantBits());
            out.writeLong(uuid.getLeastSignificantBits());
            FlashbackActionWriter.writeVarInt(bytes, samples.length);
            for (short sample : samples) out.writeShort(sample);
            out.writeByte(type);
            extra.write(out);
            return bytes.toByteArray();
        }
    }

    private static final class PacketCursor {
        private final ByteBuffer data;
        PacketCursor(byte[] packet) { data = ByteBuffer.wrap(packet); }
        int varInt() {
            int value = 0, shift = 0;
            while (shift < 35) {
                byte b = data.get();
                value |= (b & 0x7f) << shift;
                if ((b & 0x80) == 0) return value;
                shift += 7;
            }
            throw new IllegalArgumentException("Invalid packet VarInt");
        }
        void skip(int bytes) { data.position(data.position() + bytes); }
        long longValue() { return data.getLong(); }
        int intValue() { return data.getInt(); }
        int unsignedByte() { return data.get() & 0xff; }
        byte byteValue() { return data.get(); }
        short shortValue() { return data.getShort(); }
        double doubleValue() { return data.getDouble(); }
        boolean booleanValue() { return data.get() != 0; }
        float floatValue() { return data.getFloat(); }
        byte[] remainingBytes() {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            return bytes;
        }
        byte[] bytes(int length) {
            if (length < 0 || data.remaining() < length) {
                throw new IllegalArgumentException("Invalid packet byte length");
            }
            byte[] bytes = new byte[length];
            data.get(bytes);
            return bytes;
        }
        String stringValue() {
            int length = varInt();
            if (length < 0 || data.remaining() < length) {
                throw new IllegalArgumentException("Invalid packet string length");
            }
            byte[] bytes = new byte[length];
            data.get(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private record EntityPosition(double x, double y, double z, float yaw, float pitch, float headYaw, boolean onGround) {}
    private record EntitySpawn(int entityId, UUID uuid) {}
    private record SnapshotPacketSlot(String key, byte[] payload) {}
    private record SnapshotPacketKey(boolean drop, String value, List<Integer> removedEntityIds) {
        static final SnapshotPacketKey KEEP = new SnapshotPacketKey(false, null, List.of());
        static final SnapshotPacketKey DROP = new SnapshotPacketKey(true, null, List.of());
        static SnapshotPacketKey keep(String value) {
            return new SnapshotPacketKey(false, value, List.of());
        }
        static SnapshotPacketKey removeEntity(int entityId) {
            return new SnapshotPacketKey(true, null, List.of(entityId));
        }
        static SnapshotPacketKey removeEntities(List<Integer> entityIds) {
            return new SnapshotPacketKey(true, null, List.copyOf(entityIds));
        }
    }
    private record SkinProfile(UUID uuid, String name, String textureValue, String textureSignature) {}
    private record PropertyEntry(String name, String value, String signature) {}
    private record DecodedAudio(short version, UUID uuid, short[] samples) {}
    @FunctionalInterface
    private interface ExtraEncoder {
        void write(DataOutputStream out) throws IOException;
    }
    private record VoiceAction(boolean handled, String action, byte[] payload) {
        static final VoiceAction NOT_HANDLED = new VoiceAction(false, null, null);
        static final VoiceAction HANDLED_WITHOUT_OUTPUT = new VoiceAction(true, null, null);
    }
    private record MovementResult(boolean handled) {
        static final MovementResult NOT_HANDLED = new MovementResult(false);
        static final MovementResult HANDLED_WITHOUT_OUTPUT = new MovementResult(true);
    }

    private static String baseName(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static int packetId(byte[] packet) {
        int value = 0, shift = 0;
        for (byte raw : packet) {
            value |= (raw & 0x7f) << shift;
            if ((raw & 0x80) == 0) return value;
            shift += 7;
            if (shift >= 35) break;
        }
        return -1;
    }

    private static byte[] createLocalPlayerPayload(Path input, UUID localPlayerUuid, PlayerSpawn spawn, SkinProfile profile) throws IOException {
        if (spawn == null) spawn = new PlayerSpawn(0, 64, 0, 0, 0);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        UUID entityUuid = REPLAY_CAMERA_PROFILE_UUID;
        data.writeLong(entityUuid.getMostSignificantBits());
        data.writeLong(entityUuid.getLeastSignificantBits());
        data.writeDouble(spawn.x); data.writeDouble(spawn.y); data.writeDouble(spawn.z);
        data.writeFloat(spawn.pitch); data.writeFloat(spawn.yaw); data.writeFloat(spawn.yaw);
        data.writeDouble(0); data.writeDouble(0); data.writeDouble(0);
        // ByteBufCodecs.GAME_PROFILE: UUID, name, property count/properties.
        UUID profileUuid = REPLAY_CAMERA_PROFILE_UUID;
        data.writeLong(profileUuid.getMostSignificantBits());
        data.writeLong(profileUuid.getLeastSignificantBits());
        FlashbackActionWriter.writeString(bytes, "Replay Camera");
        if (profile != null && profile.textureValue != null) {
            FlashbackActionWriter.writeVarInt(bytes, 1);
            FlashbackActionWriter.writeString(bytes, "textures");
            FlashbackActionWriter.writeString(bytes, profile.textureValue);
            data.writeBoolean(profile.textureSignature != null);
            if (profile.textureSignature != null) {
                FlashbackActionWriter.writeString(bytes, profile.textureSignature);
            }
        } else {
            FlashbackActionWriter.writeVarInt(bytes, 0);
        }
        FlashbackActionWriter.writeVarInt(bytes, 3); // spectator
        return bytes.toByteArray();
    }

    private static byte[] encodePlayerInfoAddPacket(SkinProfile profile) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
        DataOutputStream data = new DataOutputStream(bytes);
        FlashbackActionWriter.writeVarInt(bytes, ClientboundPackets1_20_5.PLAYER_INFO_UPDATE.getId());
        bytes.write(1); // actions bitset: ADD only
        FlashbackActionWriter.writeVarInt(bytes, 1); // one entry
        data.writeLong(profile.uuid.getMostSignificantBits());
        data.writeLong(profile.uuid.getLeastSignificantBits());
        FlashbackActionWriter.writeString(bytes, profile.name != null ? profile.name : profile.uuid.toString());
        FlashbackActionWriter.writeVarInt(bytes, 1);
        FlashbackActionWriter.writeString(bytes, "textures");
        FlashbackActionWriter.writeString(bytes, profile.textureValue);
        data.writeBoolean(profile.textureSignature != null);
        if (profile.textureSignature != null) {
            FlashbackActionWriter.writeString(bytes, profile.textureSignature);
        }
        return bytes.toByteArray();
    }

    private static byte[] createPlayerModelPartsPacket(int entityId) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(16);
        FlashbackActionWriter.writeVarInt(bytes, ClientboundPackets1_20_5.SET_ENTITY_DATA.getId());
        FlashbackActionWriter.writeVarInt(bytes, entityId);
        // SynchedEntityData entry: index 17 (player model parts), serializer 0 (Byte), value 0x7F (all parts enabled).
        bytes.write(17);
        FlashbackActionWriter.writeVarInt(bytes, 0);
        bytes.write(0x7F);
        bytes.write(0xFF); // terminator
        return bytes.toByteArray();
    }

    private static byte[] withReplayCameraLoginId(byte[] packet) {
        if (packetId(packet) != ClientboundPackets1_20_5.LOGIN.getId()) return packet;
        int offset = varIntSize(packet);
        if (offset < 0 || packet.length < offset + Integer.BYTES) return packet;
        byte[] rewritten = packet.clone();
        ByteBuffer.wrap(rewritten, offset, Integer.BYTES).putInt(REPLAY_CAMERA_ENTITY_ID);
        return rewritten;
    }

    private static final class LocalPlayerTracker {
        private Integer localPlayerEntityId;
        private UUID localPlayerUuid;

        void accept(byte[] packet) {
            int type = packetId(packet);
            if (type == ClientboundPackets1_20_5.LOGIN.getId()) {
                localPlayerEntityId = loginEntityIdFromPacket(packet);
                return;
            }
            if (type == ClientboundPackets1_20_5.ADD_ENTITY.getId() && localPlayerEntityId != null && localPlayerUuid == null) {
                EntitySpawn spawn = addEntityFromPacket(packet);
                if (spawn != null && spawn.entityId == localPlayerEntityId) {
                    localPlayerUuid = spawn.uuid;
                }
            }
        }
    }

    private static Integer loginEntityIdFromPacket(byte[] packet) {
        if (packetId(packet) != ClientboundPackets1_20_5.LOGIN.getId()) return null;
        int offset = varIntSize(packet);
        if (offset < 0 || packet.length < offset + 4) return null;
        return ByteBuffer.wrap(packet, offset, 4).getInt();
    }

    private static EntitySpawn addEntityFromPacket(byte[] packet) {
        PacketCursor in = new PacketCursor(packet);
        if (in.varInt() != ClientboundPackets1_20_5.ADD_ENTITY.getId()) return null;
        int entityId = in.varInt();
        UUID uuid = new UUID(in.longValue(), in.longValue());
        return new EntitySpawn(entityId, uuid);
    }

    private static final class SkinProfileExtractor {
        private static final int TARGET_PLAYER_INFO_UPDATE_PACKET_ID = ClientboundPackets1_20_5.PLAYER_INFO_UPDATE.getId();
        private static final int ACTION_ADD = 1;
        private static final int ACTION_CHAT_KEY = 1 << 1;
        private static final int ACTION_GAMEMODE = 1 << 2;
        private static final int ACTION_LISTED = 1 << 3;
        private static final int ACTION_LATENCY = 1 << 4;
        private static final int ACTION_DISPLAY_NAME = 1 << 5;

        private final Map<UUID, SkinProfile> byUuid = new HashMap<>();
        private UUID firstWithTexture;
        private UUID firstWithCape;

        void accept(byte[] packet) {
            if (packetId(packet) != TARGET_PLAYER_INFO_UPDATE_PACKET_ID) return;
            try {
                PacketCursor in = new PacketCursor(packet);
                in.varInt(); // packet id
                int actions = in.unsignedByte();
                int entries = in.varInt();
                for (int i = 0; i < entries; i++) {
                    UUID uuid = new UUID(in.longValue(), in.longValue());
                    String name = null;
                    String textureValue = null;
                    String textureSignature = null;

                    if ((actions & ACTION_ADD) != 0) {
                        name = in.stringValue();
                        int properties = in.varInt();
                        for (int p = 0; p < properties; p++) {
                            String propertyName = in.stringValue();
                            String propertyValue = in.stringValue();
                            String signature = in.booleanValue() ? in.stringValue() : null;
                            if ("textures".equals(propertyName)) {
                                textureValue = propertyValue;
                                textureSignature = signature;
                            }
                        }
                    }
                    if ((actions & ACTION_CHAT_KEY) != 0) {
                        if (in.booleanValue()) {
                            in.skip(16); // session UUID
                            in.skip(8); // expires
                            in.skip(in.varInt()); // public key
                            in.skip(in.varInt()); // signature
                        }
                    }
                    if ((actions & ACTION_GAMEMODE) != 0) in.varInt();
                    if ((actions & ACTION_LISTED) != 0) in.booleanValue();
                    if ((actions & ACTION_LATENCY) != 0) in.varInt();
                    if ((actions & ACTION_DISPLAY_NAME) != 0) {
                        if (in.booleanValue()) in.stringValue();
                    }

                    if ((actions & ACTION_ADD) != 0) {
                        SkinProfile existing = byUuid.get(uuid);
                        String mergedName = choose(name, existing == null ? null : existing.name, uuid.toString());
                        String mergedTextureValue = choose(textureValue, existing == null ? null : existing.textureValue, null);
                        String mergedTextureSignature = choose(textureSignature, existing == null ? null : existing.textureSignature, null);
                        SkinProfile profile = new SkinProfile(uuid, mergedName, mergedTextureValue, mergedTextureSignature);
                        byUuid.put(uuid, profile);
                        if (firstWithCape == null && profile.textureValue != null && hasCape(profile.textureValue)) firstWithCape = uuid;
                        if (firstWithTexture == null && profile.textureValue != null) firstWithTexture = uuid;
                    }
                }
            } catch (RuntimeException ignored) {
                // Ignore malformed packets and keep conversion going.
            }
        }

        SkinProfile select(UUID preferred) {
            if (preferred != null) {
                SkinProfile preferredProfile = byUuid.get(preferred);
                if (preferredProfile != null && preferredProfile.textureValue != null && hasCape(preferredProfile.textureValue)) return preferredProfile;
            }
            if (firstWithCape != null) return byUuid.get(firstWithCape);
            if (preferred != null) {
                SkinProfile preferredProfile = byUuid.get(preferred);
                if (preferredProfile != null && preferredProfile.textureValue != null) return preferredProfile;
                return new SkinProfile(preferred, preferred.toString(), null, null);
            }
            if (firstWithTexture != null) return byUuid.get(firstWithTexture);
            Optional<SkinProfile> any = byUuid.values().stream().findFirst();
            return any.orElse(null);
        }

        private static boolean hasCape(String textureValue) {
            try {
                String json = new String(java.util.Base64.getDecoder().decode(textureValue), StandardCharsets.UTF_8);
                return json.contains("\"CAPE\"");
            } catch (IllegalArgumentException error) {
                return false;
            }
        }

        private static String choose(String preferred, String fallback, String defaultValue) {
            if (preferred != null && !preferred.isEmpty()) return preferred;
            if (fallback != null && !fallback.isEmpty()) return fallback;
            return defaultValue;
        }
    }

    private static final class PlayerInfoTexturePatcher {
        private static final int ACTION_ADD = 1;
        private static final int ACTION_CHAT_KEY = 1 << 1;
        private static final int ACTION_GAMEMODE = 1 << 2;
        private static final int ACTION_LISTED = 1 << 3;
        private static final int ACTION_LATENCY = 1 << 4;
        private static final int ACTION_DISPLAY_NAME = 1 << 5;

        private final Map<UUID, SkinProfile> texturesByUuid = new HashMap<>();

        PlayerInfoTexturePatcher(SkinProfile selectedProfile) {
            if (selectedProfile != null && selectedProfile.textureValue != null) {
                texturesByUuid.put(selectedProfile.uuid, selectedProfile);
            }
        }

        byte[] patch(byte[] packet) {
            if (texturesByUuid.isEmpty() || packetId(packet) != ClientboundPackets1_20_5.PLAYER_INFO_UPDATE.getId()) {
                return packet;
            }
            try {
                PacketCursor in = new PacketCursor(packet);
                int packetType = in.varInt();
                int actions = in.unsignedByte();
                int entries = in.varInt();

                ByteArrayOutputStream out = new ByteArrayOutputStream(packet.length + 192);
                DataOutputStream data = new DataOutputStream(out);
                FlashbackActionWriter.writeVarInt(out, packetType);
                out.write(actions);
                FlashbackActionWriter.writeVarInt(out, entries);

                for (int i = 0; i < entries; i++) {
                    UUID uuid = new UUID(in.longValue(), in.longValue());
                    data.writeLong(uuid.getMostSignificantBits());
                    data.writeLong(uuid.getLeastSignificantBits());
                    SkinProfile override = texturesByUuid.get(uuid);

                    if ((actions & ACTION_ADD) != 0) {
                        String name = in.stringValue();
                        int propertyCount = in.varInt();
                        ArrayList<PropertyEntry> properties = new ArrayList<>(propertyCount + 1);
                        boolean hasTextures = false;
                        for (int p = 0; p < propertyCount; p++) {
                            String propertyName = in.stringValue();
                            String propertyValue = in.stringValue();
                            String signature = in.booleanValue() ? in.stringValue() : null;
                            if ("textures".equals(propertyName) && propertyValue != null && !propertyValue.isEmpty()) {
                                hasTextures = true;
                            }
                            properties.add(new PropertyEntry(propertyName, propertyValue, signature));
                        }
                        if (!hasTextures && override != null) {
                            properties.add(new PropertyEntry("textures", override.textureValue, override.textureSignature));
                        }

                        FlashbackActionWriter.writeString(out, name);
                        FlashbackActionWriter.writeVarInt(out, properties.size());
                        for (PropertyEntry property : properties) {
                            FlashbackActionWriter.writeString(out, property.name);
                            FlashbackActionWriter.writeString(out, property.value);
                            data.writeBoolean(property.signature != null);
                            if (property.signature != null) {
                                FlashbackActionWriter.writeString(out, property.signature);
                            }
                        }
                    }
                    if ((actions & ACTION_CHAT_KEY) != 0) {
                        boolean hasChatKey = in.booleanValue();
                        data.writeBoolean(hasChatKey);
                        if (hasChatKey) {
                            data.writeLong(in.longValue());
                            data.writeLong(in.longValue());
                            data.writeLong(in.longValue());
                            int keyLength = in.varInt();
                            FlashbackActionWriter.writeVarInt(out, keyLength);
                            out.write(in.bytes(keyLength));
                            int sigLength = in.varInt();
                            FlashbackActionWriter.writeVarInt(out, sigLength);
                            out.write(in.bytes(sigLength));
                        }
                    }
                    if ((actions & ACTION_GAMEMODE) != 0) {
                        FlashbackActionWriter.writeVarInt(out, in.varInt());
                    }
                    if ((actions & ACTION_LISTED) != 0) {
                        data.writeBoolean(in.booleanValue());
                    }
                    if ((actions & ACTION_LATENCY) != 0) {
                        FlashbackActionWriter.writeVarInt(out, in.varInt());
                    }
                    if ((actions & ACTION_DISPLAY_NAME) != 0) {
                        boolean hasDisplayName = in.booleanValue();
                        data.writeBoolean(hasDisplayName);
                        if (hasDisplayName) {
                            FlashbackActionWriter.writeString(out, in.stringValue());
                        }
                    }
                }
                return out.toByteArray();
            } catch (RuntimeException | IOException ignored) {
                return packet;
            }
        }
    }

    private static SkinProfile resolveSkinProfile(SkinProfile profile) {
        if (profile == null || profile.textureValue != null || profile.uuid == null) return profile;
        try {
            String undashedUuid = profile.uuid.toString().replace("-", "");
            HttpClient client = HttpClient.newBuilder().build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + undashedUuid + "?unsigned=false"))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return profile;

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray properties = root.has("properties") ? root.getAsJsonArray("properties") : null;
            if (properties == null) return profile;
            for (JsonElement element : properties) {
                JsonObject property = element.getAsJsonObject();
                String name = property.has("name") ? property.get("name").getAsString() : "";
                if (!"textures".equals(name)) continue;
                String value = property.has("value") ? property.get("value").getAsString() : null;
                if (value == null || value.isEmpty()) continue;
                String signature = property.has("signature") ? property.get("signature").getAsString() : null;
                return new SkinProfile(profile.uuid, profile.name, value, signature);
            }
            return profile;
        } catch (Exception ignored) {
            return profile;
        }
    }

    private static PlayerSpawn playerSpawnFromPacket(byte[] packet) {
        if (packetId(packet) != ClientboundPackets1_20_5.PLAYER_POSITION.getId()) return null;
        int offset = varIntSize(packet);
        if (offset < 0 || packet.length < offset + 32) return null;
        ByteBuffer data = ByteBuffer.wrap(packet, offset, packet.length - offset);
        return new PlayerSpawn(data.getDouble(), data.getDouble(), data.getDouble(), data.getFloat(), data.getFloat());
    }

    private static PlayerSpawn playerSpawnFromEntityPacket(byte[] packet, int localPlayerEntityId) {
        PacketCursor in = new PacketCursor(packet);
        int type = in.varInt();
        if (type == ClientboundPackets1_20_5.ADD_ENTITY.getId()) {
            int entityId = in.varInt();
            if (entityId != localPlayerEntityId) return null;
            in.skip(16); // UUID
            in.varInt(); // entity type
            double x = in.doubleValue();
            double y = in.doubleValue();
            double z = in.doubleValue();
            float pitch = angleFromByte(in.byteValue());
            float yaw = angleFromByte(in.byteValue());
            return new PlayerSpawn(x, y, z, yaw, pitch);
        }
        if (type == ClientboundPackets1_20_5.TELEPORT_ENTITY.getId()) {
            int entityId = in.varInt();
            if (entityId != localPlayerEntityId) return null;
            double x = in.doubleValue();
            double y = in.doubleValue();
            double z = in.doubleValue();
            float yaw = angleFromByte(in.byteValue());
            float pitch = angleFromByte(in.byteValue());
            return new PlayerSpawn(x, y, z, yaw, pitch);
        }
        return null;
    }

    private static float angleFromByte(byte value) {
        return value * 360.0f / 256.0f;
    }

    private static int varIntSize(byte[] packet) {
        for (int i = 0; i < Math.min(5, packet.length); i++) {
            if ((packet[i] & 0x80) == 0) return i + 1;
        }
        return -1;
    }

    private static boolean isFishingBobberEntityType(int entityTypeId) {
        return EntityTypes1_20_5.getTypeFromId(entityTypeId) == EntityTypes1_20_5.FISHING_BOBBER;
    }

    private record PlayerSpawn(double x, double y, double z, float yaw, float pitch) {}
    private record TimedPacket(int tick, State state, byte[] payload) {}

    public record ConversionResult(int sourcePackets, int outputPackets, int totalTicks,
                                   int snapshotConfigurationPackets, int snapshotGamePackets) {}
}

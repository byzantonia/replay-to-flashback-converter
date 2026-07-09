package dev.replayconverter;

import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.type.Type;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.protocols.v1_20_3to1_20_5.packet.ClientboundPackets1_20_5;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Merges Minecraft {@code SET_EQUIPMENT} packets slot-by-slot. The vanilla protocol
 * sends only changed slots, so a later partial update (for example, swapping the held
 * item) would otherwise overwrite the full-armor packet during snapshot compaction and
 * make players appear to lose their armor. Items are round-tripped through ViaVersion's
 * item type so no Minecraft registry access is required.
 */
final class EquipmentPackets {
    private static final int SET_EQUIPMENT_ID = ClientboundPackets1_20_5.SET_EQUIPMENT.getId();
    private static final int CONTINUE_MASK = 0x80;
    private static final int SLOT_MASK = 0x7f;
    private static final Type<Item> ITEM = VersionedTypes.V1_21.item();

    private EquipmentPackets() {}

    static byte[] merge(byte[] existingPacket, byte[] updatedPacket) {
        try {
            Decoded existing = decode(existingPacket);
            Decoded updated = decode(updatedPacket);
            if (existing == null || updated == null || existing.entityId != updated.entityId) {
                return updatedPacket;
            }
            LinkedHashMap<Integer, Item> slots = new LinkedHashMap<>(existing.slots);
            slots.putAll(updated.slots);
            return encode(updated.entityId, slots);
        } catch (RuntimeException ignored) {
            return updatedPacket;
        }
    }

    private static Decoded decode(byte[] packet) {
        ByteBuf buffer = Unpooled.wrappedBuffer(packet);
        try {
            if (readVarInt(buffer) != SET_EQUIPMENT_ID) return null;
            int entityId = readVarInt(buffer);
            LinkedHashMap<Integer, Item> slots = new LinkedHashMap<>();
            int flags;
            do {
                flags = buffer.readByte() & 0xff;
                Item item = ITEM.read(buffer);
                slots.put(flags & SLOT_MASK, item);
            } while ((flags & CONTINUE_MASK) != 0);
            return new Decoded(entityId, slots);
        } finally {
            buffer.release();
        }
    }

    private static byte[] encode(int entityId, LinkedHashMap<Integer, Item> slots) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            writeVarInt(buffer, SET_EQUIPMENT_ID);
            writeVarInt(buffer, entityId);
            int index = 0;
            int size = slots.size();
            for (Map.Entry<Integer, Item> entry : slots.entrySet()) {
                index++;
                int flags = (entry.getKey() & SLOT_MASK) | (index < size ? CONTINUE_MASK : 0);
                buffer.writeByte(flags);
                ITEM.write(buffer, entry.getValue());
            }
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private static int readVarInt(ByteBuf buffer) {
        int value = 0, shift = 0;
        while (shift < 35) {
            byte b = buffer.readByte();
            value |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
        }
        throw new IllegalArgumentException("Invalid packet VarInt");
    }

    private static void writeVarInt(ByteBuf buffer, int value) {
        do {
            int part = value & 0x7f;
            value >>>= 7;
            if (value != 0) part |= 0x80;
            buffer.writeByte(part);
        } while (value != 0);
    }

    private record Decoded(int entityId, LinkedHashMap<Integer, Item> slots) {}
}

package dev.replayconverter.viaversion;

import com.viaversion.viaversion.api.protocol.packet.State;

public record TranslatedPacket(State state, byte[] payload) {
    public TranslatedPacket {
        payload = payload.clone();
    }

    @Override
    public byte[] payload() { return payload.clone(); }
}

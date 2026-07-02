package dev.replayconverter.viaversion;

import com.viaversion.viaversion.ViaManagerImpl;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.platform.ViaPlatformLoader;
import com.viaversion.viaversion.api.protocol.ProtocolPathEntry;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.commands.ViaCommandHandler;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.platform.NoopInjector;
import com.viaversion.viaversion.protocol.ProtocolPipelineImpl;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.DecoderException;
import com.viaversion.viaversion.exception.CancelException;
import com.viaversion.viaversion.protocols.v1_20_3to1_20_5.packet.ServerboundConfigurationPackets1_20_5;
import com.viaversion.viaversion.protocols.v1_20_3to1_20_5.packet.ServerboundPackets1_20_5;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;

public final class ProtocolTranslator implements AutoCloseable {
    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final List<TranslatedPacket> injectedPackets = new ArrayList<>();
    private UserConnection connection;

    public ProtocolTranslator(Path dataDirectory, int sourceProtocol, int targetProtocol) throws IOException {
        Files.createDirectories(dataDirectory);
        if (!Via.isLoaded()) {
            ViaManagerImpl.initAndLoad(
                    new ConverterViaPlatform(dataDirectory.toFile()),
                    new NoopInjector(), new ViaCommandHandler(false), ViaPlatformLoader.NOOP);
        }
        ProtocolVersion source = requiredVersion(sourceProtocol);
        ProtocolVersion target = requiredVersion(targetProtocol);
        channel.pipeline().addLast("outbound-collector", new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                if (message instanceof ByteBuf packet) {
                    byte[] bytes = new byte[packet.readableBytes()];
                    packet.getBytes(packet.readerIndex(), bytes);
                    injectedPackets.add(new TranslatedPacket(connection == null ? State.PLAY : connection.getProtocolInfo().getClientState(), bytes));
                    packet.release();
                    promise.setSuccess();
                } else {
                    context.write(message, promise);
                }
            }
        });
        channel.pipeline().addLast("via-encoder", new ChannelOutboundHandlerAdapter());
        connection = new UserConnectionImpl(channel, false);
        connection.getProtocolInfo().setProtocolVersion(target);
        connection.getProtocolInfo().setServerProtocolVersion(source);
        connection.getProtocolInfo().setClientState(State.PLAY);
        connection.getProtocolInfo().setServerState(State.PLAY);
        ProtocolPipelineImpl pipeline = new ProtocolPipelineImpl(connection);
        List<ProtocolPathEntry> path = Via.getManager().getProtocolManager().getProtocolPath(target, source);
        if (path == null || path.isEmpty()) throw new IOException("ViaVersion has no path from " + source + " to " + target);
        for (ProtocolPathEntry entry : path) pipeline.add(entry.protocol());
    }

    private static ProtocolVersion requiredVersion(int id) throws IOException {
        ProtocolVersion version = ProtocolVersion.getProtocol(id);
        if (!version.isKnown()) throw new IOException("ViaVersion does not know protocol " + id);
        return version;
    }

    public byte[] translateClientbound(byte[] packet) {
        ByteBuf buffer = Unpooled.buffer(Math.max(256, packet.length));
        buffer.writeBytes(packet);
        try {
            try {
                connection.transformClientbound(buffer, DecoderException::new);
            } catch (DecoderException cancelled) {
                if (cancelled.getCause() instanceof CancelException) return null;
                throw cancelled;
            }
            byte[] translated = new byte[buffer.readableBytes()];
            buffer.readBytes(translated);
            return translated;
        } finally {
            buffer.release();
        }
    }

    public List<TranslatedPacket> drainInjectedPackets() {
        List<TranslatedPacket> result = List.copyOf(injectedPackets);
        injectedPackets.clear();
        return result;
    }

    public String states() {
        return "client=" + connection.getProtocolInfo().getClientState()
                + ", server=" + connection.getProtocolInfo().getServerState();
    }

    public State clientState() { return connection.getProtocolInfo().getClientState(); }

    public boolean finishSyntheticConfigurationIfNeeded() {
        if (connection.getProtocolInfo().getServerState() != State.CONFIGURATION
                || connection.getProtocolInfo().getClientState() != State.PLAY) return false;
        // ViaVersion normally runs on a Netty event loop. In this one-shot converter,
        // execute the configuration packets emitted by Join Game before acknowledging them.
        channel.runPendingTasks();
        transformSyntheticServerbound(ServerboundPackets1_20_5.CONFIGURATION_ACKNOWLEDGED.getId());
        channel.runPendingTasks();
        transformSyntheticServerbound(ServerboundConfigurationPackets1_20_5.FINISH_CONFIGURATION.getId());
        channel.runPendingTasks();
        return true;
    }

    private void transformSyntheticServerbound(int packetId) {
        ByteBuf acknowledgement = Unpooled.buffer(1);
        acknowledgement.writeByte(packetId);
        try {
            try {
                connection.transformServerbound(acknowledgement, DecoderException::new);
            } catch (DecoderException cancelled) {
                if (!(cancelled.getCause() instanceof CancelException)) throw cancelled;
            }
        } finally {
            acknowledgement.release();
        }
    }

    @Override
    public void close() {
        channel.finishAndReleaseAll();
        if (Via.isLoaded() && Via.getManager() instanceof ViaManagerImpl manager) manager.destroy();
    }
}

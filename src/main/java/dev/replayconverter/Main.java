package dev.replayconverter;

import java.nio.file.Path;
import dev.replayconverter.viaversion.ProtocolTranslator;

public final class Main {
    public static void main(String[] args) throws Exception {
        int exit;
        try {
            run(args);
            exit = 0;
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            exit = 1;
        }
        System.out.flush();
        System.err.flush();
        System.exit(exit);
    }

    private static void run(String[] args) throws Exception {
        if (args.length == 2) {
            ReplayConverter.ConversionResult result = new ReplayConverter().convert(Path.of(args[0]), Path.of(args[1]));
            System.out.printf("Converted %,d source packets into %,d protocol-767 packets and %,d ticks: %s%n",
                    result.sourcePackets(), result.outputPackets(), result.totalTicks(), Path.of(args[1]).toAbsolutePath());
            return;
        }
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: replay-converter-core <input.mcpr> [output.zip]");
        }
        int count = 0;
        int translated = 0;
        int injected = 0;
        int lastTimestamp = 0;
        System.out.println("Opening MCPR and initializing ViaVersion...");
        System.out.flush();
        try (McprReader reader = new McprReader(Path.of(args[0]));
             ProtocolTranslator translator = new ProtocolTranslator(Path.of("build", "viaversion"), 763, 767)) {
            System.out.println("ViaVersion initialized; translating packets...");
            System.out.flush();
            if (reader.protocolVersion() != 763) throw new IllegalArgumentException("Expected protocol 763, found " + reader.protocolVersion());
            for (McprPacket packet : reader) {
                count++;
                lastTimestamp = packet.timestampMillis();
                try {
                    byte[] result = translator.translateClientbound(packet.payload());
                    if (result != null) translated++;
                    translator.finishSyntheticConfigurationIfNeeded();
                    injected += translator.drainInjectedPackets().size();
                    if (count % 1000 == 0) {
                        System.out.printf("Processed %,d packets (%,d translated)...%n", count, translated);
                        System.out.flush();
                    }
                } catch (RuntimeException failure) {
                    throw new IllegalStateException("Translation failed at MCPR packet " + count + " (timestamp " + lastTimestamp + " ms)", failure);
                }
            }
            System.out.printf("Read %,d packets; translated %,d; injected %,d; %,d ms; %,d target ticks; %s%n",
                    count, translated, injected, lastTimestamp, TickScheduler.tickAt(lastTimestamp), translator.states());
        }
    }
}

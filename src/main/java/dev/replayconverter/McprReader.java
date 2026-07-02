package dev.replayconverter;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class McprReader implements AutoCloseable, Iterable<McprPacket> {
    private static final int MAX_PACKET_BYTES = 64 * 1024 * 1024;
    private final ZipFile archive;
    private final DataInputStream packets;
    private final String metadataJson;

    public McprReader(Path path) throws IOException {
        archive = new ZipFile(path.toFile());
        ZipEntry metadata = required("metaData.json");
        ZipEntry recording = required("recording.tmcpr");
        try (InputStream input = archive.getInputStream(metadata)) {
            metadataJson = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        packets = new DataInputStream(new BufferedInputStream(archive.getInputStream(recording)));
    }

    private ZipEntry required(String name) throws IOException {
        ZipEntry entry = archive.getEntry(name);
        if (entry == null) throw new IOException("MCPR entry is missing: " + name);
        return entry;
    }

    public String metadataJson() {
        return metadataJson;
    }

    public int protocolVersion() throws IOException {
        String key = "\"protocol\"";
        int keyAt = metadataJson.indexOf(key);
        if (keyAt < 0) throw new IOException("MCPR metadata has no protocol field");
        int colon = metadataJson.indexOf(':', keyAt + key.length());
        int start = colon + 1;
        while (start < metadataJson.length() && Character.isWhitespace(metadataJson.charAt(start))) start++;
        int end = start;
        while (end < metadataJson.length() && Character.isDigit(metadataJson.charAt(end))) end++;
        if (start == end) throw new IOException("Invalid MCPR protocol field");
        return Integer.parseInt(metadataJson.substring(start, end));
    }

    public byte[] thumbnailBytes() throws IOException {
        String[] candidates = {
            "icon.png",
            "thumb.png",
            "thumb.jpg",
            "thumb.jpeg",
            "thumbnail.png",
            "thumbnail.jpg",
            "thumbnail.jpeg"
        };

        for (String name : candidates) {
            ZipEntry entry = archive.getEntry(name);
            if (entry == null) {
                continue;
            }
            try (InputStream input = archive.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }

        return null;
    }

    @Override
    public Iterator<McprPacket> iterator() {
        return new Iterator<>() {
            private McprPacket next;
            private boolean finished;

            @Override
            public boolean hasNext() {
                if (next == null && !finished) next = readNext();
                return next != null;
            }

            @Override
            public McprPacket next() {
                if (!hasNext()) throw new NoSuchElementException();
                McprPacket value = next;
                next = null;
                return value;
            }

            private McprPacket readNext() {
                try {
                    int timestamp;
                    try { timestamp = packets.readInt(); }
                    catch (EOFException end) { finished = true; return null; }
                    int length = packets.readInt();
                    if (length < 0 || length > MAX_PACKET_BYTES) throw new IOException("Invalid MCPR packet length: " + length);
                    byte[] payload = packets.readNBytes(length);
                    if (payload.length != length) throw new EOFException("Truncated MCPR packet payload");
                    return new McprPacket(timestamp, payload);
                } catch (IOException error) {
                    throw new McprReadException(error);
                }
            }
        };
    }

    @Override
    public void close() throws IOException {
        packets.close();
        archive.close();
    }

    public static final class McprReadException extends RuntimeException {
        McprReadException(IOException cause) { super(cause); }
    }
}

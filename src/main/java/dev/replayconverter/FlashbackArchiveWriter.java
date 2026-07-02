package dev.replayconverter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class FlashbackArchiveWriter {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private FlashbackArchiveWriter() {}

    public static void write(Path output, String name, int totalTicks, byte[] chunk, List<byte[]> chunkCacheFiles,
                             String bobbyWorldName, String worldName, byte[] iconPng) throws IOException {
        Map<String, Object> chunkMeta = new LinkedHashMap<>();
        chunkMeta.put("duration", totalTicks);
        chunkMeta.put("forcePlaySnapshot", false);
        Map<String, Object> chunks = new LinkedHashMap<>();
        chunks.put("c0.flashback", chunkMeta);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("uuid", UUID.randomUUID().toString());
        metadata.put("name", name);
        metadata.put("version_string", "1.21.1");
        metadata.put("data_version", 3955);
        metadata.put("protocol_version", 767);
        metadata.put("total_ticks", totalTicks);
        if (bobbyWorldName != null) metadata.put("bobby_world_name", bobbyWorldName);
        if (worldName != null) metadata.put("world_name", worldName);
        metadata.put("customNamespacesForRegistries", Map.of());
        metadata.put("chunks", chunks);

        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (OutputStream file = Files.newOutputStream(output); ZipOutputStream zip = new ZipOutputStream(file)) {
            put(zip, "metadata.json", GSON.toJson(metadata).getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < chunkCacheFiles.size(); i++) {
                byte[] cacheFile = chunkCacheFiles.get(i);
                if (cacheFile != null && cacheFile.length > 0) {
                    put(zip, "level_chunk_caches/" + i, cacheFile);
                }
            }
            if (iconPng != null && iconPng.length > 0) {
                put(zip, "icon.png", iconPng);
            }
            put(zip, "c0.flashback", chunk);
            put(zip, "converter.replayconverter.marker", new byte[0]);
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }
}

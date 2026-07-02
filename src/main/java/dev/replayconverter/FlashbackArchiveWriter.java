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
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class FlashbackArchiveWriter {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private FlashbackArchiveWriter() {}

    public static void write(Path output, String name, int totalTicks, FlashbackActionWriter actions, List<byte[]> chunkCacheFiles,
                             String bobbyWorldName, String worldName, byte[] iconPng,
                             Consumer<String> progress) throws IOException {
        progress.accept("Creating the Flashback archive...");
        actions.prepareChunks();
        Map<String, Object> chunks = new LinkedHashMap<>();
        for (int i = 0; i < actions.chunkCount(); i++) {
            Map<String, Object> chunkMeta = new LinkedHashMap<>();
            chunkMeta.put("duration", actions.chunkDuration(i));
            chunkMeta.put("forcePlaySnapshot", false);
            chunks.put("c" + i + ".flashback", chunkMeta);
        }
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
            progress.accept("Writing Flashback metadata...");
            put(zip, "metadata.json", GSON.toJson(metadata).getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < chunkCacheFiles.size(); i++) {
                byte[] cacheFile = chunkCacheFiles.get(i);
                if (cacheFile != null && cacheFile.length > 0) {
                    progress.accept(String.format("Writing chunk cache: %d / %d", i + 1, chunkCacheFiles.size()));
                    put(zip, "level_chunk_caches/" + i, cacheFile);
                }
            }
            if (iconPng != null && iconPng.length > 0) {
                progress.accept("Writing the replay thumbnail...");
                put(zip, "icon.png", iconPng);
            }
            for (int i = 0; i < actions.chunkCount(); i++) {
                progress.accept(String.format("Writing replay chunk: %d / %d", i + 1, actions.chunkCount()));
                zip.putNextEntry(new ZipEntry("c" + i + ".flashback"));
                actions.writeChunkTo(i, zip);
                zip.closeEntry();
            }
            progress.accept("Finalizing the Flashback archive...");
            put(zip, "converter.replayconverter.marker", new byte[0]);
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }
}

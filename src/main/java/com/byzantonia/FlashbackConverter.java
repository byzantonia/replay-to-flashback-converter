package com.byzantonia;

import dev.replayconverter.ReplayConverter;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.api.ModInitializer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public class FlashbackConverter implements ModInitializer {
	public static final String MOD_ID = "flashback-converter";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Flashback Converter initialized");
	}

	public static void convertAsync(Path input, Path output, Consumer<String> onProgress,
			Runnable onSuccess, Consumer<Throwable> onFailure) {
		Thread worker = new Thread(() -> {
			try {
				new ReplayConverter().convert(input, output, onProgress);
				onSuccess.run();
			} catch (Throwable error) {
				LOGGER.error("Conversion failed", error);
				onFailure.accept(error);
			}
		}, "flashback-converter-worker");
		worker.setDaemon(true);
		worker.start();
	}

	public static Path getReplayFolder() {
		Path replayFolder = FabricLoader.getInstance().getGameDir().resolve("flashback").resolve("replays");
		try {
			Files.createDirectories(replayFolder);
		} catch (Exception error) {
			throw new RuntimeException("Unable to create Flashback replay folder: " + replayFolder, error);
		}
		return replayFolder;
	}

	public static Path defaultOutputPath(Path input) {
		String name = input.getFileName().toString();
		int dot = name.lastIndexOf('.');
		String base = dot > 0 ? name.substring(0, dot) : name;
		return getReplayFolder().resolve(base + "-flashback.zip");
	}

	public static void openReplay(Path replayPath) throws Exception {
		Class<?> flashbackClass = Class.forName("com.moulberry.flashback.Flashback");
		Method openReplayWorld = flashbackClass.getMethod("openReplayWorld", Path.class);
		openReplayWorld.invoke(null, replayPath);
	}

	public static Screen recreateSelectReplayScreen(Screen currentScreen) {
		try {
			Class<?> screenClass = currentScreen.getClass();
			Field lastScreenField = screenClass.getDeclaredField("lastScreen");
			lastScreenField.setAccessible(true);
			Screen lastScreen = (Screen) lastScreenField.get(currentScreen);

			Field pathField = screenClass.getDeclaredField("path");
			pathField.setAccessible(true);
			Path path = (Path) pathField.get(currentScreen);

			Constructor<?> constructor = screenClass.getConstructor(Screen.class, Path.class);
			return (Screen) constructor.newInstance(lastScreen, path);
		} catch (Exception error) {
			LOGGER.warn("Failed to recreate SelectReplayScreen, falling back to existing screen", error);
			return currentScreen;
		}
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}

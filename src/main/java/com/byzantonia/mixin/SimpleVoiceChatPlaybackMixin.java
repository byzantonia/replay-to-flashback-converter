package com.byzantonia.mixin;

import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.file.FileSystem;
import java.nio.file.Files;

@Pseudo
@Mixin(targets = "com.moulberry.flashback.compat.simple_voice_chat.SimpleVoiceChatPlayback")
public abstract class SimpleVoiceChatPlaybackMixin {

    private static final String CONVERTER_MARKER = "/converter.replayconverter.marker";

    @Redirect(
        method = "play",
        at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Throwable;)V"),
        remap = false
    )
    private static void flashbackConverter$redirectVoicePlaybackError(Logger logger, String message, Throwable throwable) {
        if (flashbackConverter$shouldSuppressVoicePlaybackError(message, throwable)) {
            return;
        }
        logger.error(message, throwable);
    }

    @Redirect(
        method = "play",
        at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;error(Ljava/lang/String;)V"),
        remap = false
    )
    private static void flashbackConverter$redirectVoicePlaybackSummary(Logger logger, String message) {
        if (message != null
            && message.contains("voice chat playback errors")
            && flashbackConverter$isConvertedReplayLoaded()) {
            return;
        }
        logger.error(message);
    }

    @Redirect(
        method = "play",
        at = @At(value = "INVOKE", target = "Lcom/moulberry/flashback/editor/ui/ReplayUI;setInfoOverlay(Ljava/lang/String;)V"),
        remap = false
    )
    private static void flashbackConverter$redirectVoicePlaybackOverlay(String message) {
        if (flashbackConverter$isConvertedReplayLoaded()
            && message != null
            && message.contains("Simple Voice Chat audio")) {
            return;
        }

        try {
            Class<?> replayUiClass = Class.forName("com.moulberry.flashback.editor.ui.ReplayUI");
            replayUiClass.getMethod("setInfoOverlay", String.class).invoke(null, message);
        } catch (Throwable ignored) {
            // If reflection fails, avoid crashing the render thread over a status overlay.
        }
    }

    private static boolean flashbackConverter$shouldSuppressVoicePlaybackError(String message, Throwable throwable) {
        if (!(throwable instanceof NoSuchMethodError noSuchMethodError)) {
            return false;
        }

        String detail = noSuchMethodError.getMessage();
        if (detail == null || !detail.contains("TalkCache.updateLevel")) {
            return false;
        }

        if (message == null || !message.contains("voice chat sound")) {
            return false;
        }

        return flashbackConverter$isConvertedReplayLoaded();
    }

    private static boolean flashbackConverter$isConvertedReplayLoaded() {
        try {
            Class<?> flashbackClass = Class.forName("com.moulberry.flashback.Flashback");
            Object replayServer = flashbackClass.getMethod("getReplayServer").invoke(null);
            if (replayServer == null) {
                return false;
            }

            var field = replayServer.getClass().getDeclaredField("playbackFileSystem");
            field.setAccessible(true);
            Object value = field.get(replayServer);
            if (!(value instanceof FileSystem fileSystem)) {
                return false;
            }

            return Files.exists(fileSystem.getPath(CONVERTER_MARKER));
        } catch (Throwable ignored) {
            return false;
        }
    }
}

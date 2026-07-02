package com.byzantonia;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

public class ConversionCompleteScreen extends Screen {

    private final Screen returnScreen;
    private final Path replayPath;

    public ConversionCompleteScreen(Screen returnScreen, Path replayPath) {
        super(Component.literal("Conversion complete"));
        this.returnScreen = returnScreen;
        this.replayPath = replayPath;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.setScreen(FlashbackConverter.recreateSelectReplayScreen(this.returnScreen));
        }).bounds(this.width / 2 - 151, this.height - 28, 150, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Open Flashback"), button -> {
            try {
                FlashbackConverter.openReplay(this.replayPath);
            } catch (Exception error) {
                FlashbackConverter.LOGGER.error("Failed to open converted replay", error);
                Minecraft.getInstance().setScreen(new ConversionErrorScreen(this.returnScreen,
                    Component.literal("Failed to open replay"),
                    Component.literal(error.getClass().getSimpleName() + ": " + error.getMessage())));
            }
        }).bounds(this.width / 2 + 1, this.height - 28, 150, 20).build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 28, 0xFFFFFFFF);
        guiGraphics.drawCenteredString(this.font, this.replayPath.getFileName().toString(), this.width / 2, this.height / 2 - 10, 0xFFB8B8B8);
        guiGraphics.drawCenteredString(this.font, "Saved to flashback/replays in this instance.", this.width / 2, this.height / 2 + 6, 0xFFB8B8B8);
    }
}
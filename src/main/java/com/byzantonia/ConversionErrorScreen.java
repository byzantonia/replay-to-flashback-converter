package com.byzantonia;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class ConversionErrorScreen extends Screen {

    private final Screen returnScreen;
    private final Component message;

    public ConversionErrorScreen(Screen returnScreen, Component title, Component message) {
        super(title);
        this.returnScreen = returnScreen;
        this.message = message;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(Component.literal("Done"), button ->
            Minecraft.getInstance().setScreen(FlashbackConverter.recreateSelectReplayScreen(this.returnScreen))
        ).bounds(this.width / 2 - 104, this.height - 28, 100, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Copy error"), button -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(
                this.title.getString() + ": " + this.message.getString()
            );
            button.setMessage(Component.literal("Copied!"));
        }).bounds(this.width / 2 + 4, this.height - 28, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 20, 0xFFFF8080);
        guiGraphics.drawCenteredString(this.font, this.message, this.width / 2, this.height / 2 + 2, 0xFFB8B8B8);
    }
}

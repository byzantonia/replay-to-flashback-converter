package com.byzantonia;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class ConversionProgressScreen extends Screen {

    private final String replayName;
    private volatile String status = "Preparing conversion...";

    public ConversionProgressScreen(String replayName) {
        super(Component.literal("Converting replay"));
        this.replayName = replayName;
    }

    @Override
    protected void init() {
    }

    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 34, 0xFFFFFFFF);
        guiGraphics.drawCenteredString(this.font, this.replayName, this.width / 2, this.height / 2 - 18, 0xFFB8B8B8);
        guiGraphics.drawCenteredString(this.font, this.status, this.width / 2, this.height / 2 - 2, 0xFFB8B8B8);

        int barWidth = 240;
        int barHeight = 12;
        int left = (this.width - barWidth) / 2;
        int top = this.height / 2 + 16;
        guiGraphics.fill(left, top, left + barWidth, top + barHeight, 0xFF303030);

        long time = Util.getMillis() / 8L;
        int segmentWidth = 64;
        int range = barWidth + segmentWidth;
        int offset = (int) (time % range) - segmentWidth;
        int segmentLeft = Math.max(left, left + offset);
        int segmentRight = Math.min(left + barWidth, left + offset + segmentWidth);
        if (segmentRight > segmentLeft) {
            guiGraphics.fill(segmentLeft, top + 1, segmentRight, top + barHeight - 1, 0xFFF0F0F0);
        }

        guiGraphics.drawCenteredString(this.font, "Please wait", this.width / 2, top + 20, 0xFF9A9A9A);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void onClose() {
    }
}

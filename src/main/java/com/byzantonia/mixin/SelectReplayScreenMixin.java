package com.byzantonia.mixin;

import com.byzantonia.ConversionCompleteScreen;
import com.byzantonia.ConversionErrorScreen;
import com.byzantonia.ConversionProgressScreen;
import com.byzantonia.FlashbackConverter;
import dev.replayconverter.McprReader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

@Pseudo
@Mixin(targets = "com.moulberry.flashback.screen.select_replay.SelectReplayScreen")
public abstract class SelectReplayScreenMixin extends Screen {

    protected SelectReplayScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"), require = 0, remap = false)
    private void flashbackConverter$addConvertButtonNamed(CallbackInfo ci) {
        flashbackConverter$addConvertButton();
    }

    @Inject(method = "method_25426", at = @At("TAIL"), require = 0, remap = false)
    private void flashbackConverter$addConvertButtonIntermediary(CallbackInfo ci) {
        flashbackConverter$addConvertButton();
    }

    private void flashbackConverter$addConvertButton() {
        Screen self = (Screen) (Object) this;
        Button convertButton = Button.builder(Component.literal("Convert from replay mcpr"), button -> {
            String selected;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(1);
                filters.put(stack.UTF8("*.mcpr"));
                filters.flip();
                selected = TinyFileDialogs.tinyfd_openFileDialog(
                    "Select Replay (.mcpr)",
                    "",
                    filters,
                    "ReplayMod replay (*.mcpr)",
                    false
                );
            }

            if (selected == null || selected.isBlank()) {
                return;
            }

            Path input = Path.of(selected);
            Path output = FlashbackConverter.defaultOutputPath(input);
            Minecraft minecraft = Minecraft.getInstance();

            try (McprReader reader = new McprReader(input)) {
                int protocol = reader.protocolVersion();
                if (protocol == 763) {
                    flashbackConverter$showConfirmation(self, input, output);
                } else {
                    minecraft.setScreen(new ConfirmScreen(
                        proceed -> {
                            if (proceed) {
                                flashbackConverter$startConversion(self, input, output);
                            } else {
                                minecraft.setScreen(self);
                            }
                        },
                        Component.literal("Unsupported replay version"),
                        Component.literal("This replay uses unsupported protocol " + protocol
                            + " (expected 763). Conversion may fail or produce an invalid replay. Continue anyway?"),
                        Component.literal("Continue"),
                        Component.literal("Cancel")
                    ));
                }
            } catch (Exception error) {
                minecraft.setScreen(new ConversionErrorScreen(
                    self,
                    Component.literal("Could not read replay"),
                    Component.literal(error.getClass().getSimpleName() + ": " + error.getMessage())
                ));
            }
        }).bounds(this.width / 2 + 1, this.height - 76, 150, 20).build();

        this.addRenderableWidget(convertButton);
    }

    private void flashbackConverter$showConfirmation(Screen returnScreen, Path input, Path output) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new ConfirmScreen(
            proceed -> {
                if (proceed) {
                    flashbackConverter$startConversion(returnScreen, input, output);
                } else {
                    minecraft.setScreen(returnScreen);
                }
            },
            Component.literal("Convert replay?"),
            Component.literal(input.getFileName() + " -> " + output.getFileName()),
            Component.literal("Convert"),
            Component.literal("Cancel")
        ));
    }

    private void flashbackConverter$startConversion(Screen returnScreen, Path input, Path output) {
        Minecraft minecraft = Minecraft.getInstance();
        ConversionProgressScreen progressScreen = new ConversionProgressScreen(input.getFileName().toString());
        minecraft.setScreen(progressScreen);
        FlashbackConverter.convertAsync(
            input,
            output,
            progressScreen::setStatus,
            () -> minecraft.execute(() -> minecraft.setScreen(new ConversionCompleteScreen(returnScreen, output))),
            error -> minecraft.execute(() -> minecraft.setScreen(new ConversionErrorScreen(
                returnScreen,
                Component.literal("Conversion failed"),
                Component.literal(error.getClass().getSimpleName() + ": " + error.getMessage())
            )))
        );
    }
}

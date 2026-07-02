package dev.replayconverter.viaversion;

import com.viaversion.viaversion.platform.UserConnectionViaVersionPlatform;

import java.io.File;
import java.util.logging.Logger;

final class ConverterViaPlatform extends UserConnectionViaVersionPlatform {
    ConverterViaPlatform(File dataFolder) {
        super(dataFolder);
    }

    @Override
    public Logger createLogger(String name) {
        return Logger.getLogger(name);
    }

    @Override
    public boolean isProxy() {
        return true;
    }

    @Override
    public String getPlatformName() {
        return "ReplayConverter";
    }

    @Override
    public String getPlatformVersion() {
        return "0.1.0";
    }
}

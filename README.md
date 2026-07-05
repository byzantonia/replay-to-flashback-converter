# Flashback Converter (Fabric 1.21.1)

Convert 1.20.1 ReplayMod `.mcpr` recordings into 1.21.1 Flashback `.zip` archives from inside Minecraft.

## Notes

This is only currently supporting the conversion of 1.20.1 replays into 1.21.1 flashbacks. Other conversions are not currently supported and may fail or crash.

There will likely be bugs and issues, if you encounter any, please make a bug report.

### Bobby Data

To import your Bobby mod data:

1. Open the root folder of the Minecraft instance where you recorded the original replay.
2. Locate the `.bobby` folder.
3. Copy the `.bobby` folder into the root folder of your Minecraft 1.21.1 instance.
4. Open the converted Flashback replay and run `/bobby upgrade` in chat.

## Requirements

- Minecraft `1.21.1`
- Fabric Loader `0.18.4+`
- Fabric API `0.116.13+1.21.1`
- Java `21`

## In-Game Usage

1. Open Flashback's Select Replay screen.
2. Click `Convert from replay mcpr` (next to the built-in replay controls).
3. Pick a `.mcpr` file from the Windows file chooser.
4. Confirm conversion.

The mod writes `<input-name>-flashback.zip` into the flashback folder of your instance.

## Build

From this folder:

```text
gradlew build
```

Release jar output:

- `build/libs/flashback-converter-1.0.3.jar`

## Notes

- The conversion runs on a background thread so the client thread is not blocked.
- Internet access can affect skin enrichment lookups (Mojang session API), but conversion still works without it.

## License

The distributed mod is licensed under GPL-3.0-only because it bundles and links against ViaVersion. Code authored specifically for this project is also made available under CC0-1.0; see `LICENSE-CC0`.

See `THIRD_PARTY_NOTICES.md` for bundled component licensing and exact upstream source links.

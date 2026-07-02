# Flashback Converter (Fabric 1.21.1)

Convert ReplayMod `.mcpr` recordings into Flashback `.zip` archives from inside Minecraft.

Source repository: https://github.com/Byzantonia/replay-to-flashback-converter

## Requirements

- Minecraft `1.21.1`
- Fabric Loader `0.19.3+`
- Fabric API `0.116.13+1.21.1`
- Java `21`

## In-Game Usage

1. Open Flashback's Select Replay screen.
2. Click `Convert from replay mcpr` (next to the built-in replay controls).
3. Pick a `.mcpr` file from the Windows file chooser.
4. Confirm conversion.

The mod writes `<input-name>-flashback.zip` next to the selected input file.

## Build

From this folder:

```text
gradlew build
```

Release jar output:

- `build/libs/flashback-converter-1.0.0.jar`

## Notes

- The conversion runs on a background thread so the client thread is not blocked.
- Internet access can affect skin enrichment lookups (Mojang session API), but conversion still works without it.

## License

The distributed mod is licensed under GPL-3.0-only because it bundles and links against ViaVersion. Code authored specifically for this project is also made available under CC0-1.0; see `LICENSE-CC0`.

See `THIRD_PARTY_NOTICES.md` for bundled component licensing and exact upstream source links.

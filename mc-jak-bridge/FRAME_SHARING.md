# Jak → Minecraft frame sharing (STEVE mode)

OpenGOAL writes every finished frame into a memory-mapped file; the Fabric mod maps the same file
and draws the image in place of Minecraft's sky.

File: `%TEMP%\jakcraft-frame.bin` (Java: `java.io.tmpdir`; Linux `/tmp/jakcraft-frame.bin`).
Override on both sides with the `JAKCRAFT_FRAME_FILE` environment variable. The file is created by
OpenGOAL with a fixed size: 64-byte header + 3840 × 2160 × 4 bytes.

Only written while Minecraft is connected in STEVE mode (CAMERA flag bit 3).

| Offset | Type | Field |
|-------:|------|-------|
| 0  | u32 | magic `0x52464B4A` (bytes `J K F R`) |
| 4  | u32 | version `1` |
| 8  | u32 | seq — odd while a frame is being written, even when complete |
| 12 | u32 | width |
| 16 | u32 | height |
| 20 | u32 | format, `1` = RGBA8, bottom row first (OpenGL order) |
| 24 | u32 | frames written |
| 28 | —   | reserved |
| 64 | u8[width × height × 4] | pixels |

Reader: read `seq`; skip if odd or unchanged; use the pixels; re-read `seq` — if it changed the
frame was torn and the next one replaces it.

Implementations: `game/mods/mc_frame_share.cpp` (writer, `glReadPixels` of the window back buffer
after Jak's frame is finished), `fabric-mod/.../FrameReader.java` + `JakSky.java` (reader).

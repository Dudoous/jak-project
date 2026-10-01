# Jak → Minecraft frame sharing

OpenGOAL writes every finished frame into a memory-mapped file; the Fabric mod maps the same file
and draws the image in place of Minecraft's sky.

File: `%TEMP%\jakcraft-frame.bin` (Java: `java.io.tmpdir`; Linux `/tmp/jakcraft-frame.bin`).
Override on both sides with the `JAKCRAFT_FRAME_FILE` environment variable. The file is created by
OpenGOAL with a fixed size: 64-byte header + 3840 × 2160 × 4 bytes.

Only written while Minecraft is connected (CAMERA flag bit 3).

| Offset | Type | Field |
|-------:|------|-------|
| 0  | u32 | magic `0x52464B4A` (bytes `J K F R`) |
| 4  | u32 | version `1` |
| 8  | u32 | seq — odd while a frame is being written, even when complete |
| 12 | u32 | width |
| 16 | u32 | height |
| 20 | u32 | format, `1` = RGBA8, bottom row first (OpenGL order) |
| 24 | u32 | frames written |
| 28 | u32 | pose valid: 1 if this frame was drawn from a Minecraft camera pose |
| 32 | u32 | pose seq: the CAMERA packet's sequence number |
| 36 | f32[3] | pose x, y, z (Jak meters, eye) |
| 48 | f32 | pose yaw (Minecraft degrees) |
| 52 | f32 | pose pitch |
| 56 | f32 | pose vfov |
| 60 | —   | reserved |
| 64 | u8[width × height × 4] | pixels |

Reader: read `seq`; skip if odd or unchanged; read the rest of the header; re-read `seq` (skip if
changed); use the pixels; re-read `seq` — if it changed the pixels were torn and the next frame
replaces them.

The pose is filled in by the OpenGOAL runtime: GOAL calls `(pc-mc-pose-used)` when it puts Jak's
camera at the pose of the CAMERA packet it just read, the pose is attached to that frame's DMA
chain when it goes to the renderer (`gl_send_chain`), and the renderer writes it here with the
pixels. Minecraft then renders its own world from the same pose ("locked view"), so the two
pictures always match; the age of `pose seq` is how far Jak's picture is behind the mouse.

Implementations: `game/mods/mc_frame_share.cpp` (writer, `glReadPixels` of the window back buffer
after Jak's frame is finished), `fabric-mod/.../FrameReader.java` + `JakSky.java` (reader).

Minecraft also draws Jak's level shape (the collision triangles) into its depth buffer right after
this picture, without colour, so Minecraft blocks behind Jak's walls are hidden
(`fabric-mod/.../JakDepthProxy.java`).

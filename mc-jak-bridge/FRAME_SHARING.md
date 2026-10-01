# Jak → Minecraft frame sharing

OpenGOAL writes every finished frame into a memory-mapped file; the Fabric mod maps the same file
and draws the image in place of Minecraft's sky.

File: `%TEMP%\jakcraft-frames.bin` (Java: `java.io.tmpdir`; Linux `/tmp/jakcraft-frames.bin`).
Override on both sides with the `JAKCRAFT_FRAME_FILE` environment variable. Created by OpenGOAL
(on Windows as a "temporary" file, so it stays in memory instead of being written to disk).

Only written while Minecraft is connected (CAMERA flag bit 3).

**Triple buffered** (version 2): Jak writes each frame into the next of 3 slots and then points
the global header at it. Minecraft always reads the newest complete slot, which Jak won't touch
for another two frames, so Minecraft never has to skip a frame because Jak was busy writing, and
never shows half of one frame and half of the next. Pixels are BGRA8 (the format GPUs read back
and upload without converting); Jak windows bigger than 2560 × 1440 are shrunk to fit.

Global header (64 bytes):

| Offset | Type | Field |
|-------:|------|-------|
| 0  | u32 | magic `0x52464B4A` (bytes `J K F R`) |
| 4  | u32 | version `2` |
| 8  | u32 | latest = (frame number << 2) \| slot of the newest complete frame |
| 12 | u32 | slot count (3) |
| 16 | u32 | slot stride (bytes) |
| 20 | u32 | max width (2560) |
| 24 | u32 | max height (1440) |
| 28 | u32 | frames written |

Slot *i* at `64 + i × stride`: a 64-byte slot header, then the pixels.

| Offset | Type | Field |
|-------:|------|-------|
| 0  | u32 | seq — odd while the slot is being written |
| 4  | u32 | width |
| 8  | u32 | height |
| 12 | u32 | format, `2` = BGRA8, bottom row first (OpenGL order) |
| 16 | u32 | frame number |
| 20 | u32 | pose valid: 1 if this frame was drawn from a Minecraft camera pose |
| 24 | u32 | pose seq: the CAMERA packet's sequence number |
| 28 | f32[3] | pose x, y, z (Jak meters, eye) |
| 40 | f32 | pose yaw (Minecraft degrees) |
| 44 | f32 | pose pitch |
| 48 | f32 | pose vfov |
| 64 | u8[width × height × 4] | pixels |

Reader: read `latest`; skip if unchanged; read the slot's `seq` (skip if odd) and header, re-read
`seq` (skip if changed); upload the pixels; `seq` changing during the upload means the frame was
overwritten (only if the upload took longer than two of Jak's frames).

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

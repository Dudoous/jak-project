# Jak Bridge wire protocol (v1)

Transport: UDP on `127.0.0.1` only. The OpenGOAL runtime listens on port **47800** (override with
the `OPENGOAL_MC_PORT` environment variable on both sides). Minecraft sends from an ephemeral port;
the runtime replies to whatever address last sent it a valid packet.

UDP instead of WebSocket: the camera is a stream where only the newest sample matters. UDP never
queues stale frames behind a lost one, needs no handshake or library on either side, and on
localhost has effectively zero loss and sub-millisecond latency.

All fields are **little-endian**. Packets with a wrong magic, version, type or a short length are
ignored.

## Common header (12 bytes)

| Offset | Type | Field   | Value |
|-------:|------|---------|-------|
| 0      | u32  | magic   | `0x4B4A434D` (bytes `M C J K`) |
| 4      | u16  | version | `1` |
| 6      | u16  | type    | `1` = CAMERA, `2` = JAK_STATE |
| 8      | u32  | seq     | incrementing per sender |

## CAMERA (type 1, Minecraft → Jak, 64 bytes, sent every rendered frame)

| Offset | Type | Field   | Notes |
|-------:|------|---------|-------|
| 12     | u32  | flags   | bit 0 in world, bit 1 a screen/menu is open, bit 2 BODY mode requested |
| 16     | f64  | x       | camera (eye) position, blocks |
| 24     | f64  | y       | |
| 32     | f64  | z       | |
| 40     | f32  | yaw     | degrees, wrapped to [-180, 180). 0 faces +Z (south), 90 faces −X (west) |
| 44     | f32  | pitch   | degrees, +90 = looking straight down |
| 48     | f32  | vfov    | vertical FOV, degrees |
| 52     | f32  | forward | movement input −1..1 (W = +1) |
| 56     | f32  | strafe  | movement input −1..1 (D = +1, i.e. right) |
| 60     | u32  | buttons | 1 jump, 2 sneak, 4 sprint, 8 attack, 16 use |

The runtime considers Minecraft "connected" while the newest CAMERA packet is younger than
`(-> *mc-passthrough* timeout-ms)` (default 250 ms). After that the camera goes back to Jak.

## JAK_STATE (type 2, Jak → Minecraft, 44 bytes)

| Offset | Type   | Field | Notes |
|-------:|--------|-------|-------|
| 12     | f32[8] | data  | `[0..2]` where the Minecraft player's **eyes** should be (Minecraft coordinates), `[3]` yaw echo, `[4]` Jak health, `[5]` flags (bit 0 = BODY mode active), `[6..7]` reserved |

Sent every frame from GOAL with `(pc-mc-send-state ptr)` while BODY mode is active. The Fabric mod
teleports the player there each tick (subtracting its current eye height to get the feet) and
zeroes Steve's own movement input. If no JAK_STATE arrives for 500 ms, Minecraft behaves normally.

## Implementations

- Runtime (C++): `game/mods/mc_passthrough.cpp` in the OpenGOAL tree
- Minecraft (Java): `fabric-mod/src/main/java/dev/ignition/jakbridge/BridgeSocket.java`
- Test tool (Python): `tools/fake_minecraft.py`

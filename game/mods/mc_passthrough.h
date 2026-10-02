#pragma once

/*!
 * @file mc_passthrough.h
 * Minecraft passthrough bridge.
 *
 * Runs a small UDP listener on localhost. The companion Fabric mod sends the Minecraft player's
 * camera state every frame; GOAL code polls the latest packet with (pc-mc-get-camera ...) and
 * uses it to drive *math-camera*. GOAL can also push Jak-side state back to Minecraft with
 * (pc-mc-send-state ...).
 *
 * Wire format (little-endian) is documented in PROTOCOL.md of the companion project
 * (mc-jak-bridge), which also contains the Fabric mod and a fake-Minecraft test tool.
 */

#include "common/common_types.h"

namespace mc_passthrough {

constexpr u32 kMagic = 0x4B4A434D;  // "MCJK"
constexpr u16 kVersion = 1;
constexpr u16 kTypeCamera = 1;    // Minecraft -> Jak
constexpr u16 kTypeJakState = 2;  // Jak -> Minecraft
constexpr u16 kTypeEvent = 3;     // Minecraft -> Jak, gameplay events (explosions, ...)
// type 4 is unused (was an experimental mode)
constexpr u16 kTypeBlocks = 5;  // Minecraft -> Jak, blocks the player built near Steve
constexpr u16 kTypeActors = 6;  // Jak -> Minecraft, solid Jak objects near Steve (crates, doors)
constexpr int kMaxActors = 64;
constexpr int kMaxBlocks = 2048;
// block kinds (bit flags)
constexpr u32 kBlockSolid = 1;   // stops Jak's enemies
constexpr u32 kBlockDeadly = 2;  // lava, fire, magma, cactus...: kills Jak's enemies touching it
constexpr u32 kEventExplosion = 1;
constexpr int kDefaultPort = 47800;

// CAMERA packet flags
constexpr u32 kFlagInWorld = 1;
constexpr u32 kFlagScreenOpen = 2;
constexpr u32 kFlagSteveMode = 8;  // Minecraft is the game; Jak only renders its world for it

// Number of floats written by goal_get_camera.
constexpr int kCameraFloats = 20;
// floats in a JAK_STATE packet (see goal_send_state)
constexpr int kJakStateFloats = 11;

/// Start the listener thread (idempotent). Port can be overridden with OPENGOAL_MC_PORT.
void start();
/// Stop the listener thread and close the socket.
void stop();

/*!
 * GOAL: (pc-mc-get-camera dst) -> int
 * Writes kCameraFloats floats to dst:
 *   [0..2]  x, y, z           Minecraft eye position (blocks)
 *   [3]     yaw               Minecraft yaw (degrees, 0 = +Z / south, 90 = -X / west)
 *   [4]     pitch             Minecraft pitch (degrees, +90 = looking straight down)
 *   [5]     vfov              Minecraft vertical FOV (degrees, includes dynamic FOV)
 *   [6]     age-ms            milliseconds since the packet was received
 *   [7]     seq               packet sequence number (as float)
 *   [8]     flags             packet flags (as float)
 *   [9]     eye height        Steve's eye height above his feet (m)
 *   [10]    unused
 *   [11]    buttons           bitfield (as float): 1 jump, 2 sneak, 4 sprint, 8 attack, 16 use,
 *                             32.. Jak's buttons (see PROTOCOL.md)
 *   [12..14] feet             Steve's feet (Jak meters); not under the camera in third person
 *   [15]    feet valid        1 if [12..14] were sent
 *   [16..17] look yaw, pitch  where Steve himself looks (degrees; the camera can look the other way
 *                             in third person)
 *   [18]    look valid        1 if [16..17] were sent
 *   [19]    unused
 * Returns 1 if a packet has ever been received, 0 otherwise.
 */
u64 goal_get_camera(u32 dst);

/*!
 * GOAL: (pc-mc-send-state src) -> int
 * Sends kJakStateFloats (11) floats from src to the last Minecraft client that sent us a packet:
 *   see JAK_STATE in PROTOCOL.md (position, damage to Steve, health, flags, activation, water,
 *   where the last thing that hurt Steve was)
 * Returns 1 on success, 0 if no client is known yet.
 */
u64 goal_send_state(u32 src);

/*!
 * GOAL: (pc-mc-pop-event dst) -> int
 * Pops the oldest queued Minecraft event. Returns its type (0 = queue empty) and writes 5 floats:
 *   explosion (1): x, y, z (Jak meters), radius (m), power
 */
u64 goal_pop_event(u32 dst);

/*!
 * GOAL: (pc-mc-get-blocks dst max) -> int
 * Copies the newest list of Minecraft blocks near Steve (from the BLOCKS packet) to dst as
 * 4 x s32 per block: x, y, z (Jak meters, block corner), kind (kBlock* flags). Returns the count.
 */
u64 goal_get_blocks(u32 dst, u32 max);

/*!
 * GOAL: (pc-mc-pose-used) -> int
 * Call after putting Jak's camera at the pose last read with pc-mc-get-camera. The frame being
 * built is then tagged with that pose when it reaches the renderer, and the pose is written into
 * the shared frame header, so Minecraft can draw its world from exactly where Jak's picture was
 * taken (no lag between the two pictures).
 */
u64 goal_pose_used();

/// The camera pose a frame was built with (Jak meters, Minecraft angles).
struct FramePose {
  u32 valid = 0;
  u32 seq = 0;  // CAMERA packet sequence number
  float x = 0, y = 0, z = 0, yaw = 0, pitch = 0, vfov = 0;
};

/// Game thread, when a frame's DMA chain is handed to the renderer: that frame used this pose.
void on_chain_sent();

/// Render thread: the pose of the frame being rendered right now.
FramePose rendered_pose();

/*!
 * GOAL: (pc-mc-send-actors src count) -> int
 * Sends the solid Jak objects near Steve (crates, closed doors) so Minecraft can make them solid.
 * src = 8 floats per object: id, kind (1 box, 2 door slab), x, y, z (Jak meters, centre),
 * half size (m), normal x, normal z (doors). See ACTORS in PROTOCOL.md.
 */
u64 goal_send_actors(u32 src, u32 count);

/// True while Minecraft is connected (fresh packets) and playing in Jak's world.
/// Safe to call from any thread.
bool steve_mode_active();

}  // namespace mc_passthrough

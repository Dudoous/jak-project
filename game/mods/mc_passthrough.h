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
constexpr int kDefaultPort = 47800;

// Number of floats written by goal_get_camera.
constexpr int kCameraFloats = 12;

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
 *   [9]     forward input     -1..1 (W/S)
 *   [10]    strafe input      -1..1 (A/D, + = right)
 *   [11]    buttons           bitfield (as float): 1 jump, 2 sneak, 4 sprint, 8 attack, 16 use
 * Returns 1 if a packet has ever been received, 0 otherwise.
 */
u64 goal_get_camera(u32 dst);

/*!
 * GOAL: (pc-mc-send-state src) -> int
 * Sends 8 floats from src to the last Minecraft client that sent us a packet:
 *   [0..2] x, y, z (Minecraft coordinates)   [3] yaw   [4] health   [5] flags   [6..7] reserved
 * Returns 1 on success, 0 if no client is known yet.
 */
u64 goal_send_state(u32 src);

}  // namespace mc_passthrough

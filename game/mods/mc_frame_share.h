#pragma once

/*!
 * @file mc_frame_share.h
 * Share each rendered Jak frame with Minecraft.
 *
 * Minecraft is the game you play, and Jak only renders its world from Minecraft's
 * camera. After every frame is finished, the renderer copies the visible game image into a
 * memory-mapped file (%TEMP%/jakcraft-frames.bin, override with JAKCRAFT_FRAME_FILE). The Fabric
 * mod maps the same file and draws the image as Minecraft's sky, so Minecraft blocks, hand, mobs
 * and HUD appear on top of Jak's world.
 *
 * File layout (little-endian, version 2), see FRAME_SHARING.md in mc-jak-bridge:
 *   global header (64 bytes):
 *     0 u32 magic 'JKFR' (0x52464B4A)  4 u32 version (2)
 *     8 u32 latest = (frame number << 2) | slot of the newest complete frame
 *     12 u32 slot count (3)  16 u32 slot stride  20 u32 max width  24 u32 max height
 *     28 u32 frames written
 *   then slot i at 64 + i * stride: 64-byte slot header, then pixels
 *     0 u32 seq (odd while writing)  4 u32 width  8 u32 height  12 u32 format (2 = BGRA8,
 *     bottom row first)  16 u32 frame number  20 u32 pose valid  24 u32 pose seq (CAMERA packet)
 *     28 f32[6] pose: x y z yaw pitch vfov
 */

#include "common/common_types.h"

namespace mc_passthrough {

constexpr u32 kFrameMagic = 0x52464B4A;  // "JKFR"
constexpr u32 kFrameVersion = 2;
constexpr u32 kFrameHeaderSize = 64;
constexpr u32 kFrameSlots = 3;
// larger windows are shrunk to fit (Minecraft draws the picture at its own window size anyway)
constexpr u32 kFrameMaxWidth = 2560;
constexpr u32 kFrameMaxHeight = 1440;
constexpr u32 kFrameFormatBgra8 = 2;

/*!
 * Call on the render thread once the frame is in the window's back buffer.
 * Does nothing unless Minecraft is connected.
 * (x, y, w, h) is the game's visible region in the window framebuffer.
 */
void export_frame_if_wanted(int x, int y, int w, int h);

}  // namespace mc_passthrough

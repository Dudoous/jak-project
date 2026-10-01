#pragma once

/*!
 * @file mc_frame_share.h
 * Share each rendered Jak frame with Minecraft.
 *
 * Minecraft is the game you play, and Jak only renders its world from Minecraft's
 * camera. After every frame is finished, the renderer copies the visible game image into a
 * memory-mapped file (%TEMP%/jakcraft-frame.bin, override with JAKCRAFT_FRAME_FILE). The Fabric mod
 * maps the same file and draws the image as Minecraft's sky, so Minecraft blocks, hand, mobs and
 * HUD appear on top of Jak's world.
 *
 * File layout (little-endian), see FRAME_SHARING.md in mc-jak-bridge:
 *   0  u32 magic 'JKFR' (0x52464B4A)   4 u32 version (1)
 *   8  u32 seq (odd while writing, even when a frame is complete)
 *   12 u32 width  16 u32 height  20 u32 format (1 = RGBA8, bottom row first)
 *   24 u32 frames written  28..63 reserved
 *   64 pixels
 */

#include "common/common_types.h"

namespace mc_passthrough {

constexpr u32 kFrameMagic = 0x52464B4A;  // "JKFR"
constexpr u32 kFrameVersion = 1;
constexpr u32 kFrameHeaderSize = 64;
constexpr u32 kFrameMaxWidth = 3840;
constexpr u32 kFrameMaxHeight = 2160;

/*!
 * Call on the render thread once the frame is in the window's back buffer.
 * Does nothing unless Minecraft is connected.
 * (x, y, w, h) is the game's visible region in the window framebuffer.
 */
void export_frame_if_wanted(int x, int y, int w, int h);

}  // namespace mc_passthrough

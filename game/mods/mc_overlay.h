#pragma once

/*!
 * @file mc_overlay.h
 * JAK mode: you play Jak and Daxter in Jak's window, with Steve's Minecraft gameplay on top.
 *
 * Minecraft runs in the background, rendering from Jak's first-person camera with no sky. It
 * writes each finished frame (Steve's hand, blocks, mobs, HUD; transparent where Jak's world
 * should show) into a memory-mapped file, %TEMP%/jakcraft-mc-frame.bin (override with
 * JAKCRAFT_MC_FRAME_FILE). Minecraft already hides its blocks behind Jak's walls itself (it draws
 * Jak's level collision into its depth buffer), so here we only alpha-blend that image over Jak's
 * finished frame.
 *
 * Also collects the mouse buttons, wheel and number keys pressed in Jak's window so they can be
 * forwarded to Minecraft (break / place blocks, pick hotbar slot).
 *
 * MC frame file layout: same as mc_frame_share.h but magic 'MCFR' (0x5246434D), and the pixels are
 * premultiplied alpha.
 */

#include "common/common_types.h"

union SDL_Event;

namespace mc_passthrough {

constexpr u32 kMcFrameMagic = 0x5246434D;  // "MCFR"

// buttons forwarded to Minecraft
constexpr u32 kHostButtonAttack = 1;  // left mouse
constexpr u32 kHostButtonUse = 2;     // right mouse
constexpr u32 kHostButtonPick = 4;    // middle mouse

/// Graphics thread: look at an SDL event from Jak's window (mouse buttons, wheel, 1-9).
void on_sdl_event(const SDL_Event& evt);

/// Mouse buttons currently held in Jak's window (kHostButton*).
u32 host_buttons();
/// Wheel steps since the last call (+ = scrolled up). Resets the counter.
s32 take_host_wheel();
/// Hotbar key (1-9) pressed since the last call, or 0. Resets it.
u32 take_host_hotbar();

/*!
 * Render thread: draw Minecraft's frame over Jak's finished frame in the window back buffer.
 * Does nothing unless Minecraft is connected in JAK mode and sending frames.
 */
void draw_mc_overlay_if_wanted(int x, int y, int w, int h);

}  // namespace mc_passthrough

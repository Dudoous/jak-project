# JakCraft — Minecraft on top of Jak and Daxter

**You play Minecraft. Jak and Daxter is the world you play in.** Same idea as the universal-modder
Minecraft × GTA V passthrough: one game supplies the world, Minecraft supplies all the gameplay.

- You play in the **Minecraft window**, with all of Minecraft's gameplay: walking, sprinting,
  jumping, swimming, placing and breaking blocks, hotbar, inventory, crafting, health, mobs.
- What you see around you is **Jak's world**, rendered by OpenGOAL from Steve's eyes and drawn by
  Minecraft in place of its sky.
- **Jak and Daxter are gone** — hidden, and they can't move by themselves. There is no Jak
  gameplay; OpenGOAL keeps Jak's world, enemies and water running around you, and the world
  reacts to Steve as it would to Jak:
  - walk into **power cells, scout flies, precursor orbs and eco** to collect them; blue eco vents
    charge you up, so blue eco doors and launchers work;
  - **left click breaks crates**, including the metal **scout fly boxes**, and presses **buttons**
    (warp gate switches...); TNT breaks every crate, even steel ones;
  - when the game moves Jak somewhere by itself (warp gate, launcher, checkpoint, cutscene),
    Steve is teleported after him;
  - **cutscenes** play full screen with Jak's own camera (and Jak in them).
- **Jak's ground is solid for Steve**: Jak's level shape becomes invisible barrier blocks, so you
  walk on Jak's floors, bump into Jak's walls, and swim in Jak's water.
- **Block placing in Jak's world**: place blocks on Jak's ground and walls. They sit *inside* Jak's
  world — Jak's walls and hills hide them like any real object — and Jak's world reacts:
  - Jak's enemies have to **walk around your blocks**; a wall of blocks stops them.
  - **Lava, fire, magma, cactus, berry bushes, wither roses** kill Jak's enemies that walk into them.
  - **TNT / creepers**: the explosion kills Jak's enemies in the blast.
- **Fighting**: left click kills the Jak enemy in front of Steve. Jak's enemies hurt Steve
  (Minecraft health, you can die and respawn where you came in).
- **Jak's buttons** for people and warp gates, in the Minecraft window:

  | Key | Jak button | Used for |
  |-----|-----------|----------|
  | **R** | ○ circle | talk to people, use a warp gate, choose a warp destination |
  | **Enter** | ✕ | answer yes |
  | **Backspace** | △ | answer no, leave the warp gate |
  | **arrow keys** | d-pad | pick the warp gate destination |

  (change them in Options → Controls → Key Binds → Jak Bridge). Jak's own pause menu (to
  **save**) still opens from Jak's window with Start / Esc.
- **Locked view** (default): Minecraft draws its world from exactly where Jak's latest picture
  was taken, so your blocks never slide or swim over Jak's world. The price is that the view follows
  your mouse a few frames late (the time Jak takes to draw a frame). **F6** switches to the fast
  view (no delay, but Jak's world trails behind when you move).

```
 Minecraft (you play here) ── Steve's eyes + your blocks ──UDP──▶ Jak and Daxter (OpenGOAL)
   Jak's world as the sky  ◀── Jak's rendered frame (shared memory) ──┘
```

## Quick start (prebuilt Windows package)

1. Unzip the **JakCraft-windows** package into a new folder, double-click **`play.bat`**, drag
   your Jak and Daxter `.iso` onto the window. First run takes 5–15 minutes (it also exports
   Jak's level shape). Later runs start straight away.
2. Minecraft **1.21.1** with Fabric Loader + Fabric API. Put `jakbridge-0.7.0.jar` (from the
   package's `minecraft-mod/` folder) in `%appdata%\.minecraft\mods`, removing older jakbridge jars.
3. Minecraft settings (once): Video Settings → **FOV Effects 0%**, **Clouds OFF**, Graphics
   **Fancy** (not Fabulous).
4. In Minecraft create a world: Singleplayer → Create New World → World tab → World Type
   **JakCraft (Jak's world)** (4064 blocks tall, so all of Jak's world fits). Creative is easiest
   for building; Survival works too (bring your own blocks).
5. Run `play.bat`, load your save and get into gameplay (Jak standing in the level). Then click
   into the **Minecraft window** and play — Jak disappears and Steve appears where Jak stood.
   Keep Jak's window open (it can be behind Minecraft, just don't minimise it).

Keys in Minecraft: **F8** bridge on/off, **F7** back to where Jak was standing, **F6** locked /
fast view. Type **`/jakcraft`** in chat to see what's connected, how far Jak's picture is behind
your mouse, the collision status, and how many of your blocks Jak knows about.

**Geyser Rock** (the first island): the warp gate only works once you have its 4 power cells —
the free one, the one behind the blue eco door, the one at the top, and the one the 7 scout flies
give you (break the metal boxes with left click). Then hit the warp gate switch, stand on the
gate and press **R**; with Sandover picked (arrow keys to change), press **R** again to warp.

## What's in here

| Path | What it is |
|------|-----------|
| `opengoal-mc-passthrough.patch` | Changes to the OpenGOAL source |
| `fabric-mod/` | The Minecraft side (Fabric, Minecraft 1.21.1) |
| `windows/play.bat` | First run: builds the game from your ISO and exports collision. Later: starts it |
| `tools/fake_minecraft.py` | Pretends to be Minecraft, to test the Jak side alone (`--blocks` too) |
| `PROTOCOL.md`, `FRAME_SHARING.md` | Wire formats |

## REPL commands (only if you build OpenGOAL yourself)

| Command | Effect |
|---------|--------|
| `(mc-status)` | connection state, last packet, blocks received |
| `(mc-debug #t)` | on-screen status text |
| `(mc-off)` / `(mc-on)` | ignore Minecraft (Jak comes back) / let Minecraft take over |
| `(mc-reanchor)` | start over from where Jak is now |

## Known limitations

- Your blocks count for Jak's enemies near Steve (about 3 chunks around you, the nearest 2048
  blocks, updated once a second). Enemies that don't walk on the ground (flying ones, bosses)
  ignore them, and an enemy that's already inside a block isn't pushed out.
- Jak's moving platforms, elevators and doors aren't known to Minecraft (Steve can't ride them).
- Hiding blocks behind Jak's walls uses Jak's collision shape, which is a bit simpler than what you
  see — edges can be slightly off.
- In the locked view Minecraft moves at Jak's frame rate (60 fps) and a few frames behind your
  mouse. If Jak's picture falls more than 0.3 s behind (loading), the view unlocks until it
  catches up.
- Jak's vehicles (zoomer, Flut Flut) and bosses aren't hooked up for Steve.

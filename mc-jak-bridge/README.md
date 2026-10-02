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
  - walk into **power cells, scout flies, precursor orbs and eco** to collect them — anything
    Steve's body touches is picked up, in first or third person; blue eco vents charge you up, so
    blue eco doors and launchers work;
  - **left click breaks crates**, including the metal **scout fly boxes**, and presses **buttons**
    — punching a warp gate switch always presses it, even before you have that island's power
    cells; TNT breaks every crate, even steel ones;
  - when the game moves Jak somewhere by itself (warp gate, launcher, checkpoint, cutscene),
    you watch Jak's own camera full screen until he lands, then Steve is teleported after him —
    warp gates work with the bridge on;
  - Jak's world loads around **Steve**: the next areas, enemies and pickups load where Steve
    looks and walks (also in third person view). Jak and Daxter can only keep **2 areas** in
    memory at once, so when you fly somewhere new (creative), the area you're in and its nearest
    neighbour are loaded for you — give it a second or two to appear;
  - **cutscenes** play full screen with Jak's own camera (and Jak in them).
- **Jak's ground is solid for Steve**: Jak's level shape becomes invisible barrier blocks, so you
  walk on Jak's floors, bump into Jak's walls, and swim in Jak's water. **Crates** (scout fly
  boxes too) and **closed doors** are solid as well, until a crate breaks or a door opens.
- **Steve is Jak-sized** in Jak's world: as tall as Jak (2.8 blocks), **walks as fast as Jak runs**
  (10 blocks/s, sprinting 13), jumps 2.5 blocks and reaches further. Change it in
  `.minecraft/config/jakbridge.properties`: `steve_scale` (1 = normal Minecraft size),
  `steve_speed` (blocks per second), `steve_jump` (blocks).
- In Jak's world Minecraft's **view bobbing, hurt tilt and FOV effects are off** — they made
  Minecraft's blocks and holes swim against Jak's picture as you walked.
- **Digging into Jak's ground**: hit Jak's ground with a **pickaxe or shovel** and it turns into
  dirt (stone deeper down) that you mine like normal Minecraft ground; the hole gets dirt walls
  and you can dig down and tunnel. Fists and swords don't dig, so fighting never knocks holes in
  the floor. Holes are saved with your world.
- **Block placing in Jak's world**: place blocks on Jak's ground and walls. They sit *inside* Jak's
  world — Jak's walls and hills hide them like any real object — and Jak's world reacts:
  - Jak's enemies have to **walk around your blocks**; a wall of blocks stops them.
  - **Lava, fire, magma, cactus, berry bushes, wither roses** kill Jak's enemies that walk into them.
  - **TNT / creepers**: the explosion kills Jak's enemies in the blast.
- **Fighting**: left click kills the Jak enemy Steve looks at, up to ~7 blocks away (lurkers,
  crabs, snakes, bats, plants, spiders — every kind, armoured ones too). It aims from Steve's own
  eyes and where *he* looks, so it works in third person view and front view as well.
- **Getting hurt**: Jak's enemies hurt Steve — 2 hearts a hit, at most one hit every 0.75 s — and
  knock him back; pits, lava, dark eco and the like kill him like they kill Jak. It works in
  **creative mode too** (creative has no health bar, so your hearts show above the hotbar while
  you're hurt). You can die; you respawn where you last came into Jak's world. Set
  `jak_damage_in_creative=false` in `.minecraft/config/jakbridge.properties` to be safe in
  creative.
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
2. Minecraft **1.21.1** with Fabric Loader + Fabric API. Put `jakbridge-0.12.0.jar` (from the
   package's `minecraft-mod/` folder) in `%appdata%\.minecraft\mods`, removing older jakbridge jars.
3. Minecraft settings (once): Video Settings → **FOV Effects 0%**, **Clouds OFF**, Graphics
   **Fancy** (not Fabulous), **Max Framerate 120 or more** (or Unlimited) so Minecraft picks up
   every one of Jak's 60 frames a second.
4. In Minecraft create a world: Singleplayer → Create New World → World tab → World Type
   **JakCraft (Jak's world)** (4064 blocks tall, so all of Jak's world fits). Creative is easiest
   for building; Survival works too (bring your own blocks).
5. Run `play.bat`, load your save and get into gameplay (Jak standing in the level). Then click
   into the **Minecraft window** and play — Jak disappears and Steve appears where Jak stood.
   Keep Jak's window open (it can be behind Minecraft, just don't minimise it).

Keys in Minecraft: **F8** bridge on/off, **F7** back to where Jak was standing, **F6** locked /
fast view, **F9** sync info on screen (whether the view is locked to Jak's picture and if not why,
both frame rates, both picture shapes — red lines are the problem). Type **`/jakcraft`** in chat to see what's connected, Jak's picture frame rate (what
Jak draws and what Minecraft receives — both should say about 60), how far it is behind your
mouse, the collision status, and how many of your blocks Jak knows about.

**Geyser Rock** (the first island): normally the warp gate only works once you have its 4 power
cells (the free one, the one behind the blue eco door, the one at the top, and the one the 7
scout flies give you). Punching the warp gate switch turns the gate on anyway. Stand on the gate
and press **R**; with Sandover picked (arrow keys to change), press **R** again to warp.

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
- Holes only exist on Minecraft's side: Jak's enemies (and Jak's own collision) walk over them
  as if they weren't there, and looking up from inside a hole shows Jak's world from below.
- Moving platforms and other moving objects aren't solid for Steve yet (only crates and doors).

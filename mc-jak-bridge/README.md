# JakCraft — Minecraft gameplay inside Jak and Daxter's world

**STEVE mode (default):** you play real Minecraft — Steve's movement and physics, placing and
breaking blocks, hotbar, inventory, mobs — and the world around you is Jak and Daxter's world.
Jak is gone.

How it works:

```
            camera (eyes, look, FOV) every frame ──UDP──▶
 Minecraft  ◀──────── Jak's rendered frame (shared memory) ────────  OpenGOAL (Jak)
 (the game)                                                         renders its world from
  · draws Jak's frame where the sky would be                        Steve's eyes, Jak hidden
  · draws blocks / hand / mobs / HUD on top
  · Jak's level collision = invisible barrier blocks (exported from your disc during setup)
```

- You play in the **Minecraft window**. The Jak window stays open behind it (don't minimise it).
- Jak's collision is exported during setup (`play.bat`) and turned into invisible barrier blocks,
  so Steve walks on Jak's ground, bumps into Jak's walls and can place blocks on Jak's world.
  Slopes become 1-block steps, and Steve's step height is raised so he walks up them.
- Jak himself is hidden and frozen; he's only dragged along under Steve so Jak's levels, music
  and enemies keep loading around you.
- **Fighting:** left-click (any item or fist) kills the Jak enemy you're looking at within
  ~4.5 m. **TNT and other Minecraft explosions** kill Jak enemies in the blast. Enemies chase and
  hit Steve (Minecraft damage); lava, dark eco and other deadly Jak things kill Steve. Jak's orbs
  are collected when Steve walks over them. You respawn where you entered Jak's world.
- **Water:** Jak's water is swimmable — Minecraft swimming, sinking and drowning in Jak's lakes and sea.

Keys in Minecraft: **F7** puts Steve back where Jak was standing (if you fall off the world),
**F9** cycles STEVE → BODY → FREE mode, **F8** turns the bridge off.
BODY mode = your keys drive Jak with Jak's physics. FREE mode = Jak's camera just follows Steve.

## Quick start (prebuilt Windows package)

1. Unzip the **JakCraft-windows** package into a new folder, double-click **`play.bat`**, drag
   your Jak and Daxter `.iso` onto the window. First run takes 5–15 minutes (it also exports
   Jak's collision). Later runs start straight away.
2. Minecraft **1.21.1** with Fabric Loader + Fabric API. Put `jakbridge-0.4.0.jar` (from the
   package's `minecraft-mod/` folder) in `%appdata%\.minecraft\mods`, removing older jakbridge jars.
3. Minecraft settings (once):
   - Video Settings → **FOV Effects 0%**, **Clouds OFF**, Graphics **Fancy** (not Fabulous).
   - Make the Minecraft window the **same shape** as Jak's (both 16:9 is easiest).
4. Create a world: Singleplayer → Create New World → World tab → World Type
   **JakCraft (Jak's world)**. (It's an empty world 4064 blocks tall, so all of Jak's world —
   from Sunken City's depths to the top of Snowy Mountain — fits. Normal worlds are only 384
   blocks tall and most of Jak's ground would be missing.) Creative or Survival both work.
5. Play: run `play.bat`, load your save and get into gameplay. Then join the Minecraft world.
   Chat shows `[JakCraft] Jak collision ready ...` and Steve is placed where Jak was standing.

Type **`/jakcraft`** in chat at any time to see what's connected and what's missing (Jak game,
Jak picture, collision files, world height, your position, water).

If the chat says the JakCraft folder is unknown, run `play.bat` once (it records its location),
or put `jakcraft_dir=C:/path/to/JakCraft` (forward slashes) in `.minecraft\config\jakbridge.properties`.

`config\jakbridge.properties` also has: `visible_collision=true` (glass instead of invisible
barriers, to check the collision lines up with Jak's world) and `collision_radius=8` (chunks).
Jak's picture is rendered at the Minecraft window's shape automatically.

## What's in here

| Path | What it is |
|------|-----------|
| `opengoal-mc-passthrough.patch` | Changes to the OpenGOAL source |
| `fabric-mod/` | The Minecraft side (Fabric, Minecraft 1.21.1) |
| `windows/play.bat` | First run: builds the game from your ISO and exports collision. Later: starts it |
| `tools/fake_minecraft.py` | Pretends to be Minecraft, to test the Jak side alone |
| `PROTOCOL.md`, `FRAME_SHARING.md` | Wire formats |

## REPL commands (only if you build OpenGOAL yourself)

| Command | Effect |
|---------|--------|
| `(mc-status)` | connection state and last packet |
| `(mc-debug #t)` | on-screen status text |
| `(mc-off)` / `(mc-on)` | ignore Minecraft / follow Minecraft |
| `(mc-reanchor)` | restart the current mode (STEVE: puts Steve back where Jak is) |
| `(set! (-> *mc-passthrough* hide-jak?) #f)` | keep Jak visible |

## Known limitations

- **Your blocks always draw in front of Jak's world**, even when a Jak wall is between you and
  them (Minecraft doesn't know how far away Jak's pixels are). Fixing this needs Jak's depth buffer
  shared too — next step.
- Jak's view lags a frame or two behind Minecraft's when you turn fast.
- Moving platforms, elevators and doors in Jak's world aren't solid for Steve (only static level
  collision is exported).
- Breaking Jak's world isn't possible (it's barrier blocks); you build on top of it.
- Jak's enemies walk through Minecraft blocks you place (they only know Jak's own collision), and
  only Jak's regular enemies can be killed — bosses don't react to Steve yet.
- Water has no Minecraft underwater fog (you see Jak's own underwater view).

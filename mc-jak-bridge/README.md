# JakCraft — Steve's Minecraft gameplay inside Jak and Daxter

**JAK mode (default): you play Jak and Daxter, in Jak's window — as Steve.** Same idea as the
universal-modder Minecraft × GTA V passthrough: the host game (Jak) is the game you play and see,
and Minecraft runs in the background adding Steve on top:

- **First person, Steve's body:** Jak is hidden; the camera is at Steve's eye height and the mouse
  looks around. You move with Jak's controls and Jak's physics (walls, ledges, swimming, double
  jump), and Steve is placed at Jak's body every frame.
- **Steve's hand, held item, hotbar, health and Minecraft's blocks and mobs are drawn into Jak's
  window**, rendered by Minecraft from exactly Jak's camera. Minecraft hides its blocks behind
  Jak's walls (it knows Jak's level shape), so they sit *in* Jak's world.
- **Mouse in Jak's window → Minecraft:** left click breaks blocks / attacks, right click places
  blocks / uses items, middle click picks a block, wheel and **1–9** change the hotbar slot.
- **Fighting Jak's enemies:** left click kills the Jak enemy in front of Steve (sword or fist);
  TNT and other Minecraft explosions kill Jak enemies in the blast.
- Jak's level collision becomes invisible barrier blocks in Minecraft, so blocks can be placed
  on Jak's ground and walls, and Minecraft mobs walk on Jak's world.

```
 Jak (you play here) ── camera + body + clicks/wheel/1-9 ──UDP──▶ Minecraft (background)
   draws Minecraft's frame on top  ◀── frame with hand/blocks/HUD (shared memory) ──
```

Other modes (**F9** in the Minecraft window cycles them): **STEVE** = play in the Minecraft
window with Minecraft's own movement in Jak's world; **BODY** = Minecraft's keys drive Jak;
**FREE** = Jak's camera follows Steve.

## Quick start (prebuilt Windows package)

1. Unzip the **JakCraft-windows** package into a new folder, double-click **`play.bat`**, drag
   your Jak and Daxter `.iso` onto the window. First run takes 5–15 minutes (it also exports
   Jak's level shape). Later runs start straight away.
2. Minecraft **1.21.1** with Fabric Loader + Fabric API. Put `jakbridge-0.5.0.jar` (from the
   package's `minecraft-mod/` folder) in `%appdata%\.minecraft\mods`, removing older jakbridge jars.
3. Minecraft settings (once): Video Settings → **FOV Effects 0%**, **Clouds OFF**, Graphics
   **Fancy** (not Fabulous).
4. In Minecraft create a world: Singleplayer → Create New World → World tab → World Type
   **JakCraft (Jak's world)** (4064 blocks tall, so all of Jak's world fits). **Creative** is
   easiest. Fill your hotbar with the blocks/items you want now (open the inventory in the
   Minecraft window) — you'll pick them with the wheel / 1–9 from Jak's window.
5. Run `play.bat`, load your save and get into gameplay. Make the Minecraft window about the
   same size as Jak's and keep it open behind (don't minimise it). Click into **Jak's window**
   and play. (The mod turns on Jak's mouse-camera for you while JAK mode is active.)

Type **`/jakcraft`** in Minecraft's chat to see what's connected and what's missing.

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

- **Blocks you place are not solid for Jak yet** — in JAK mode you walk through them, and Jak's
  enemies walk through them too (Jak's physics only knows Jak's own level). That's the next step.
- Minecraft's inventory screen isn't shown in Jak's window: set up your hotbar in the Minecraft
  window, then pick with the wheel / 1–9.
- Minecraft's layer can trail Jak's camera by a frame when you turn quickly.
- Hiding blocks behind Jak's walls uses Jak's collision shape, which is a bit simpler than what you
  see — edges can be slightly off.
- Moving platforms, elevators and doors in Jak's world aren't known to Minecraft.
- Only Jak's regular enemies can be killed by Steve; bosses don't react yet.

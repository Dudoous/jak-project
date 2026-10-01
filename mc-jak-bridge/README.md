# Jak Bridge — Minecraft × Jak and Daxter passthrough

Milestone 1: **Minecraft's first-person camera drives Jak's camera.** Look around and move in
Minecraft, and Jak's world (running in OpenGOAL) follows your view in real time.

```
Minecraft + jakbridge (Fabric)  ──UDP 127.0.0.1:47800──▶  OpenGOAL gk (C++ listener)
   eye pos, yaw, pitch, FOV,        64-byte packet/frame        │
   WASD / jump / click                                          ▼
                                                   GOAL: update-camera → *math-camera*
                                   ◀── JAK_STATE replies (for milestone 2)
```

## Quick start (prebuilt Windows package)

If you downloaded the **JakCraft-windows** package (built automatically by GitHub Actions):

1. Unzip it anywhere (a path without special characters is safest, e.g. `C:\Games\JakCraft`).
2. Double-click **`play.bat`** and drag your Jak and Daxter `.iso` onto the window when asked.
   The first run extracts and compiles the game from your disc (5–15 minutes, once only).
   After that, `play.bat` just starts the game.
3. Minecraft: install Fabric Loader + Fabric API for **1.21.1**, copy the jar from the package's
   `minecraft-mod/` folder into Minecraft's `mods/` folder, and join a world while Jak is running.

Sections 1–3 below are only needed if you build everything yourself.

## What's in here

| Path | What it is |
|------|-----------|
| `opengoal-mc-passthrough.patch` | Changes to the OpenGOAL source (apply to a `jak-project` checkout) |
| `windows/play.bat` | First run: builds the game from your ISO. Later runs: starts it |
| `fabric-mod/` | The Minecraft side (Fabric, Minecraft 1.21.1) |
| `tools/fake_minecraft.py` | Pretends to be Minecraft — test the Jak side without Minecraft |
| `PROTOCOL.md` | Packet format |

The OpenGOAL patch touches 9 files: a new C++ module `game/mods/mc_passthrough.{h,cpp}`, a new GOAL
file `goal_src/jak1/pc/features/mc-passthrough.gc`, a 3-line hook in
`goal_src/jak1/engine/camera/cam-update.gc`, and build-list entries (`game/CMakeLists.txt`,
`game/kernel/jak1/kmachine.cpp`, `goal_src/jak1/game.gp`, `goal_src/jak1/dgos/game.gd`, `engine.gd`).
Everything is additive; with Minecraft closed the game plays exactly as normal.

## 1. Build OpenGOAL with the mod

You need your own legally obtained Jak and Daxter PS2 disc image (OpenGOAL ships no game assets).

```sh
git clone https://github.com/open-goal/jak-project.git
cd jak-project
git apply /path/to/mc-jak-bridge/opengoal-mc-passthrough.patch
```

Then build exactly as the OpenGOAL docs say for your OS (`docs/setup/system/windows.md` or
`linux.md`). On Windows that's Visual Studio 2022 → *Open Folder* → pick the **Release** preset →
*Build All*. On Linux:

```sh
cmake -B build -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++
ninja -C build
```

Install [Task](https://taskfile.dev), put the ISO's contents in `iso_data/jak1/`, and:

```sh
task set-game-jak1
task set-decomp-ntscv1     # or set-decomp-pal etc. to match your disc
task extract
task repl                  # in the REPL: (mi)   ← builds the game, includes the mod
```

## 2. Test the Jak side without Minecraft

1. Terminal A: `task boot-game` — when it starts you should see
   `[mc-passthrough] listening for Minecraft on UDP 127.0.0.1:47800` in the log.
2. Terminal B: `task repl`, then `(lt)` to connect, then `(mc-debug #t)` for on-screen status.
3. Get into gameplay (load a save / start a new game).
4. Terminal C: `python tools/fake_minecraft.py look`

Expected: the view starts exactly where Jak's camera was and **turns right** slowly. Stop the
script and within a quarter second the normal Jak camera comes back.

Other checks: `walk` (moves forward, turns right at each corner, returns to the start), `nod`
(tilts **down** first), `fov` (zooms in and out).

## 3. Minecraft side

1. Install Minecraft Java **1.21.1** with [Fabric Loader](https://fabricmc.net/use/) and put
   [Fabric API](https://modrinth.com/mod/fabric-api) (1.21.1 build) in `mods/`.
2. Build the mod: in `fabric-mod/`, run `gradle build` (needs Gradle 8.12+ and JDK 21; or open the
   folder in IntelliJ IDEA). If you want a `gradlew` wrapper, copy `gradlew`, `gradlew.bat` and the
   `gradle/` folder from the [Fabric example mod](https://github.com/FabricMC/fabric-example-mod).
3. Copy `fabric-mod/build/libs/jakbridge-0.1.0.jar` into Minecraft's `mods/` folder.
4. Recommended settings: *Video Settings → FOV Effects 0%* (so sprinting doesn't zoom only one
   view), a **Superflat / void creative world** with flying on, and the two game windows side by
   side.

Start OpenGOAL first, get into gameplay, then join your Minecraft world. **F8** in Minecraft toggles
streaming on/off.

## REPL commands

| Command | Effect |
|---------|--------|
| `(mc-status)` | print connection state and the last packet |
| `(mc-debug #t)` / `(mc-debug #f)` | on-screen status text |
| `(mc-reanchor)` | snap back to Jak's camera and re-lock Minecraft's origin there |
| `(mc-off)` / `(mc-on)` | ignore Minecraft / follow Minecraft |
| `(set! (-> *mc-passthrough* scale) (meters 2.0))` | 1 Minecraft block = 2 Jak meters |
| `(set! (-> *mc-passthrough* mirror-x?) #f)` | flip left/right if it ever feels mirrored |

GOAL code hot-reloads: edit `mc-passthrough.gc`, then `(ml "goal_src/jak1/pc/features/mc-passthrough.gc")`
in the REPL — no restart needed.

## Known limitations of milestone 1

- Minecraft's world isn't drawn inside Jak yet — you see Jak's world from Minecraft's eyes, and
  Minecraft's own window separately. Compositing the Minecraft frame (blocks, hand, mobs) into
  Jak's renderer is milestone 3.
- No collision between the two worlds: fly through walls freely. Milestone 2 ("Jak drives the
  body") fixes this by making Jak's physics the source of truth and teleporting the Minecraft player
  to match, using the JAK_STATE packets that are already wired up.
- Jak's level streaming uses both the camera and Jak's own position, so flying far away from
  where Jak is standing can show unloaded or half-loaded areas. Milestone 2 removes this too.
- FOV matching is exact in OpenGOAL's default (non-"PS2 culling") mode; Minecraft's dynamic FOV
  is ignored.

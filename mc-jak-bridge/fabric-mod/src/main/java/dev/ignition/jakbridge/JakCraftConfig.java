package dev.ignition.jakbridge;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Settings in .minecraft/config/jakbridge.properties. No Minecraft dependencies.
 *
 *   jakcraft_dir       folder with play.bat (auto-detected from %APPDATA%\JakCraft\location.txt,
 *                      which play.bat writes)
 *   jak_y_offset       Minecraft Y = Jak meters Y + jak_y_offset (default 0; the JakCraft world type
 *                      is 4064 blocks tall, so all of Jak's world fits without an offset)
 *   visible_collision  true = glass instead of invisible barriers (to check alignment)
 *   collision_radius   chunks around the player that get collision blocks (default 8)
 *   steve_scale        how big Steve is in Jak's world (default 1.55 = 2.8 blocks, Jak's height;
 *                      1 = normal Minecraft size)
 *   steve_speed        Steve's walking speed in Jak's world, blocks per second (default 10 = Jak's
 *                      running speed; normal Minecraft walking is 4.3). Sprinting is 30% faster.
 *   steve_jump         how high Steve jumps in Jak's world, blocks (default 2.5; Minecraft 1.25)
 *   view_sync          locked (default): Minecraft draws its world from the exact spot Jak's
 *                      picture was taken from, so blocks never slide over Jak's world (the view
 *                      follows the mouse a few frames late). fast: no delay, but Jak's world trails
 *                      behind when you move. F6 switches in game.
 */
public final class JakCraftConfig {
    private final Path file;
    private final Properties props = new Properties();

    public JakCraftConfig(Path configDir) {
        this.file = configDir.resolve("jakbridge.properties");
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(r);
            } catch (IOException e) {
                JakBridgeClient.LOG.warn("Could not read {}: {}", file, e.toString());
            }
        }
    }

    public Path file() {
        return file;
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                props.store(w, "Jak Bridge (JakCraft) settings");
            }
        } catch (IOException e) {
            JakBridgeClient.LOG.warn("Could not write {}: {}", file, e.toString());
        }
    }

    /** The JakCraft folder (the one with play.bat), or null if unknown. */
    public Path jakcraftDir() {
        String v = props.getProperty("jakcraft_dir");
        if (v != null && !v.isBlank()) {
            return Paths.get(v.trim());
        }
        String env = System.getenv("JAKCRAFT_DIR");
        if (env != null && !env.isBlank()) {
            return Paths.get(env.trim());
        }
        String appdata = System.getenv("APPDATA");
        if (appdata != null) {
            Path loc = Paths.get(appdata, "JakCraft", "location.txt");
            if (Files.exists(loc)) {
                try {
                    String s = Files.readString(loc, StandardCharsets.UTF_8).trim();
                    // play.bat writes the folder with a trailing backslash; drop quotes just in case
                    s = s.replace("\"", "");
                    if (!s.isEmpty()) return Paths.get(s);
                } catch (IOException ignored) {
                }
            }
        }
        return null;
    }

    /** Where the extractor put the collide-*.obj files, or null if not found. */
    public Path collisionDir() {
        Path dir = jakcraftDir();
        if (dir == null) return null;
        Path[] candidates = {
                dir.resolve("data").resolve("decompiler_out").resolve("jak1").resolve("collision"),
                dir.resolve("decompiler_out").resolve("jak1").resolve("collision"),
        };
        for (Path p : candidates) {
            if (Files.isDirectory(p)) return p;
        }
        return null;
    }

    public int yOffset() {
        String v = props.getProperty("jak_y_offset");
        if (v == null) return 0;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public boolean visibleCollision() {
        return Boolean.parseBoolean(props.getProperty("visible_collision", "false").trim());
    }

    public double steveScale() {
        try {
            return Math.max(0.5, Math.min(3.0, Double.parseDouble(props.getProperty("steve_scale", "1.55").trim())));
        } catch (NumberFormatException e) {
            return 1.55;
        }
    }

    public double steveSpeed() {
        try {
            return Math.max(1.0, Math.min(40.0, Double.parseDouble(props.getProperty("steve_speed", "10").trim())));
        } catch (NumberFormatException e) {
            return 10.0;
        }
    }

    public double steveJump() {
        try {
            return Math.max(0.5, Math.min(10.0, Double.parseDouble(props.getProperty("steve_jump", "2.5").trim())));
        } catch (NumberFormatException e) {
            return 2.5;
        }
    }

    public boolean viewLocked() {
        return !"fast".equalsIgnoreCase(props.getProperty("view_sync", "locked").trim());
    }

    public void setViewLocked(boolean locked) {
        props.setProperty("view_sync", locked ? "locked" : "fast");
        save();
    }

    public int collisionRadius() {
        try {
            return Math.max(2, Math.min(32, Integer.parseInt(props.getProperty("collision_radius", "8").trim())));
        } catch (NumberFormatException e) {
            return 8;
        }
    }

}

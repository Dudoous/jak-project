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
 *   y_offset           Minecraft Y = Jak meters Y + y_offset (computed once from the collision)
 *   visible_collision  true = glass instead of invisible barriers (to check alignment)
 *   collision_radius   chunks around the player that get collision blocks (default 8)
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

    public Integer yOffset() {
        String v = props.getProperty("y_offset");
        if (v == null) return null;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void setYOffset(int offset) {
        props.setProperty("y_offset", Integer.toString(offset));
        save();
    }

    public boolean visibleCollision() {
        return Boolean.parseBoolean(props.getProperty("visible_collision", "false").trim());
    }

    public int collisionRadius() {
        try {
            return Math.max(2, Math.min(32, Integer.parseInt(props.getProperty("collision_radius", "8").trim())));
        } catch (NumberFormatException e) {
            return 8;
        }
    }

    /**
     * Pick a Y offset that puts Jak's lowest collision just above Minecraft's world bottom (-64),
     * and keeps 0 if Jak's world already fits.
     */
    public static int chooseYOffset(double jakMinY) {
        int bottom = -60;
        if (Double.isInfinite(jakMinY) || jakMinY >= bottom) return 0;
        return (int) Math.ceil(bottom - jakMinY);
    }
}

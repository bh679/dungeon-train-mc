package games.brennan.dungeontrain.client.modcheck;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "Quit and Disable" — turns the unsupported mods off the way the launchers do: each mod's jar in
 * the {@code mods} folder is renamed {@code <name>.jar.disabled}. The CurseForge and Modrinth apps
 * use exactly that suffix, so they show the mod as disabled and the player can switch it back on
 * there; NeoForge skips the file on the next launch.
 *
 * <p><b>Only whole jars of unsupported mods.</b> A jar can carry several mods; it is disabled only
 * when every mod in it is on the unsupported list, so an approved mod is never switched off by
 * sharing a file. Mods that aren't a plain jar in {@code mods/} (the dev classpath, a mod nested
 * inside another mod's jar) are left alone and reported back.</p>
 *
 * <p><b>Windows.</b> The running game holds its jars open, so a rename there fails until the game
 * has exited. Those renames are handed to a small detached {@code cmd} script that retries for a
 * minute after the game quits. Elsewhere the rename works immediately.</p>
 */
public final class ModDisabler {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String SUFFIX = ".disabled";

    /** What happened: the jars renamed now, those deferred until exit, and mods that can't be. */
    public record Outcome(List<Path> renamed, List<Path> deferred, List<String> skippedModIds) {
        public boolean anyDisabled() {
            return !renamed.isEmpty() || !deferred.isEmpty();
        }
    }

    private ModDisabler() {}

    /** Disable the jars holding {@code modIds}. Never throws. */
    public static Outcome disable(Collection<String> modIds) {
        List<Path> renamed = new ArrayList<>();
        List<Path> deferred = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        try {
            Path modsDir = FMLPaths.MODSDIR.get().toAbsolutePath().normalize();
            Map<Path, Set<String>> jars = new LinkedHashMap<>();
            for (String id : modIds) {
                var info = ModList.get().getModFileById(id);
                if (info == null) { skipped.add(id); continue; }
                Path jar = info.getFile().getFilePath().toAbsolutePath().normalize();
                Set<String> inJar = Set.copyOf(info.getMods().stream().map(m -> m.getModId()).toList());
                if (!isDisableable(jar, modsDir)) { skipped.add(id); continue; }
                jars.put(jar, inJar);
            }
            for (Path jar : jarsToDisable(jars, Set.copyOf(modIds))) {
                Path target = jar.resolveSibling(jar.getFileName() + SUFFIX);
                try {
                    Files.move(jar, target);
                    renamed.add(jar);
                } catch (Exception e) {
                    deferred.add(jar);
                }
            }
            for (var e : jars.entrySet()) {
                if (!renamed.contains(e.getKey()) && !deferred.contains(e.getKey())) {
                    skipped.addAll(e.getValue().stream().filter(modIds::contains).toList());
                }
            }
            if (!deferred.isEmpty() && !scheduleAfterExit(deferred)) {
                // Nothing will rename these after exit, so they are NOT disabled — say so honestly.
                for (Path jar : deferred) skipped.addAll(jars.get(jar).stream().filter(modIds::contains).toList());
                deferred.clear();
            }
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Quit and Disable: could not disable mods: {}", t.toString());
        }
        LOGGER.info("[DungeonTrain] Quit and Disable: renamed {}, deferred {}, skipped {}",
            renamed, deferred, skipped);
        return new Outcome(List.copyOf(renamed), List.copyOf(deferred), List.copyOf(skipped));
    }

    /** A plain {@code .jar} sitting directly in the mods folder — nothing else is ours to rename. */
    static boolean isDisableable(Path jar, Path modsDir) {
        return jar.getParent() != null && jar.getParent().equals(modsDir)
            && jar.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    /** Pure: the jars whose every mod is unsupported. Package-visible for tests. */
    static List<Path> jarsToDisable(Map<Path, Set<String>> jarToModIds, Set<String> unsupported) {
        List<Path> out = new ArrayList<>();
        for (var e : jarToModIds.entrySet()) {
            if (!e.getValue().isEmpty() && unsupported.containsAll(e.getValue())) out.add(e.getKey());
        }
        return List.copyOf(out);
    }

    /**
     * Pure: the Windows batch script that renames {@code jars} once the game has let go of them —
     * retrying once a second for up to a minute, then deleting itself.
     */
    static String windowsScript(List<Path> jars) {
        StringBuilder sb = new StringBuilder("@echo off\r\nset n=0\r\n:loop\r\nset left=0\r\n");
        for (Path jar : jars) {
            String from = jar.toString();
            // `ren` takes a bare new name; cut it from the string so this is the same on any OS.
            String to = from.substring(Math.max(from.lastIndexOf('\\'), from.lastIndexOf('/')) + 1) + SUFFIX;
            sb.append("if exist \"").append(from).append("\" ren \"").append(from).append("\" \"")
              .append(to).append("\" 2>nul\r\n");
            sb.append("if exist \"").append(from).append("\" set left=1\r\n");
        }
        sb.append("if %left%==0 goto done\r\nset /a n+=1\r\nif %n% geq 60 goto done\r\n")
          .append("timeout /t 1 /nobreak >nul\r\ngoto loop\r\n:done\r\n(goto) 2>nul & del \"%~f0\"\r\n");
        return sb.toString();
    }

    /** Hand the renames to a detached script (Windows only). True if one was started. */
    private static boolean scheduleAfterExit(List<Path> jars) {
        try {
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            if (!windows) {
                LOGGER.warn("[DungeonTrain] Quit and Disable: could not rename {} — disable them in your launcher", jars);
                return false;
            }
            Path script = Files.createTempFile("dungeontrain-disable-mods", ".bat");
            Files.writeString(script, windowsScript(jars), StandardCharsets.UTF_8);
            // `start` detaches it so it outlives the game; its first quoted arg would be the window
            // title, so /min goes first and the (possibly quoted) script path comes after.
            new ProcessBuilder("cmd", "/c", "start", "/min", "cmd", "/c", script.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            return true;
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Quit and Disable: could not schedule the rename of {}: {}", jars, e.toString());
            return false;
        }
    }
}

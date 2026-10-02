package games.brennan.dungeontrain.client.modcheck;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

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
 * <p><b>Everything happens in-process, right now.</b> Nothing is scheduled, spawned or left behind
 * to run after the game exits. On Windows the running game holds its jars open, so the rename
 * normally fails there; those mods are reported as skipped and the screen falls back to a plain
 * Quit with the "disable them in your launcher" hint.</p>
 */
public final class ModDisabler {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String SUFFIX = ".disabled";

    /** What happened: the jars renamed, and the mods that could not be disabled from here. */
    public record Outcome(List<Path> renamed, List<String> skippedModIds) {
        public boolean anyDisabled() {
            return !renamed.isEmpty();
        }
    }

    private ModDisabler() {}

    /** Disable the jars holding {@code modIds}. Never throws. */
    public static Outcome disable(Collection<String> modIds) {
        List<Path> renamed = new ArrayList<>();
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
            List<Path> failed = new ArrayList<>();
            for (Path jar : jarsToDisable(jars, Set.copyOf(modIds))) {
                Path target = jar.resolveSibling(jar.getFileName() + SUFFIX);
                try {
                    Files.move(jar, target);
                    renamed.add(jar);
                } catch (Exception e) {
                    failed.add(jar);
                }
            }
            if (!failed.isEmpty()) {
                LOGGER.warn("[DungeonTrain] Quit and Disable: could not rename {} (the game is holding them open?) — disable them in your launcher", failed);
            }
            for (var e : jars.entrySet()) {
                if (!renamed.contains(e.getKey())) {
                    skipped.addAll(e.getValue().stream().filter(modIds::contains).toList());
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Quit and Disable: could not disable mods: {}", t.toString());
        }
        LOGGER.info("[DungeonTrain] Quit and Disable: renamed {}, skipped {}", renamed, skipped);
        return new Outcome(List.copyOf(renamed), List.copyOf(skipped));
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
}

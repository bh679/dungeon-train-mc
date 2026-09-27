package games.brennan.dungeontrain.cheat;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * "Put the configs back how they shipped" — the one action behind the launch prompt
 * ({@code ConfigDeviationScreen}) and {@code /fixconfig}.
 *
 * <p>Every config Dungeon Train holds to its defaults is <b>moved aside</b>, never deleted:
 * {@code <name>.bak-yyyyMMdd-HHmmss} next to the original, the naming
 * {@link AisDataIntegrity#restoreDefaults} already established. A player who had good reasons for
 * their values can rename the file back and lose nothing. If a file cannot be moved aside it is
 * left exactly as it was — the rule inherited from the AIS restore is that we never destroy the
 * player's data, so no backup means no reset.</p>
 *
 * <p>Farmers' Delight is the exception: only its stackable-soups switch is governed, so its file is
 * backed up and that one key set back in place — see {@link #resetSoupStacking}.</p>
 *
 * <p>DT, AIS, Edible Backpacks and PlayerMob all write a fresh default config on next launch
 * when theirs is missing, so the
 * reset is deliberately a <b>rename and nothing else</b>. That also means it <b>takes effect on
 * the next game start</b>: NeoForge has the old values loaded in memory for the rest of this run,
 * and nothing re-reads a file that has vanished. Every caller says so to the player.</p>
 */
public final class ConfigReset {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** PlayerMob's config (see {@link PlayerMobConfigCheck}); PlayerMob rewrites defaults when it's missing. */
    static final String PLAYERMOB_FILE = "playermob.properties";

    /** Every config file governed by an integrity check, in the order the player sees them. */
    public static final List<String> GOVERNED_FILES = List.of(
        DtConfigIntegrity.SERVER_FILE, DtConfigIntegrity.COMMON_FILE, AisDataIntegrity.FILE_NAME,
        DtConfigIntegrity.EB_FILE, PLAYERMOB_FILE);

    /** One file that was moved aside: its name, and the name of the backup it now lives under. */
    public record Moved(String file, String backup) {}

    /**
     * What the reset actually did. {@code moved} is what was set aside (empty when there was
     * nothing to reset — no config file had been written yet); {@code failed} names files that
     * could not be moved and were therefore left untouched.
     */
    public record Result(List<Moved> moved, List<String> failed) {

        public boolean success() {
            return failed.isEmpty();
        }
    }

    private ConfigReset() {}

    /**
     * Move every governed config in {@code configDir} aside. A file that isn't there is skipped
     * silently — absent already means "defaults", which is the state we're restoring.
     */
    public static Result run(Path configDir) {
        String stamp = LocalDateTime.now().format(STAMP);
        List<Moved> moved = new ArrayList<>(GOVERNED_FILES.size() + 1);
        List<String> failed = new ArrayList<>();
        moveGovernedAside(configDir, stamp, moved, failed);
        resetSoupStacking(configDir, stamp, moved, failed);
        return new Result(List.copyOf(moved), List.copyOf(failed));
    }

    private static void moveGovernedAside(Path configDir, String stamp, List<Moved> moved, List<String> failed) {
        for (String name : GOVERNED_FILES) {
            Path file = configDir.resolve(name);
            if (!Files.exists(file)) continue;
            Path backup = configDir.resolve(name + ".bak-" + stamp);
            try {
                Files.move(file, backup);
                LOGGER.info("[DungeonTrain] Config reset: moved {} aside to {}", name, backup.getFileName());
                moved.add(new Moved(name, backup.getFileName().toString()));
            } catch (IOException e) {
                // Never destroy the player's data: if it can't be set aside, it stays put.
                LOGGER.warn("[DungeonTrain] Could not move {} aside — left untouched", file, e);
                failed.add(name);
            }
        }
    }

    /**
     * Farmers' Delight's file holds dozens of the player's own settings, so it is not moved aside
     * like the files above: it is <b>copied</b> to a backup and only the stackable-soups switch is
     * set back to off in place. Untouched unless the switch is actually on. Same rule as the moves —
     * no backup, no change.
     */
    private static void resetSoupStacking(Path configDir, String stamp, List<Moved> moved, List<String> failed) {
        String name = FarmersDelightSoupStacking.FILE;
        Path file = configDir.resolve(name);
        if (!FarmersDelightSoupStacking.readFlag(file).orElse(false)) return;
        Path backup = configDir.resolve(name + ".bak-" + stamp);
        try {
            Files.copy(file, backup);
            FarmersDelightSoupStacking.writeFlag(file, false);
            LOGGER.info("[DungeonTrain] Config reset: stackable soups off in {} (backup {})", name, backup.getFileName());
            moved.add(new Moved(name, backup.getFileName().toString()));
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Could not reset stackable soups in {} — left untouched", file, t);
            failed.add(name);
        }
    }
}

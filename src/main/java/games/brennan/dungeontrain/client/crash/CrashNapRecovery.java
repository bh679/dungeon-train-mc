package games.brennan.dungeontrain.client.crash;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.RunWorldCleanup;
import games.brennan.dungeontrain.data.CrashRunState;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import games.brennan.dungeontrain.nap.CrashNapBuilder;
import games.brennan.dungeontrain.nap.NapService;
import games.brennan.dungeontrain.nap.NapSnapshot;
import games.brennan.dungeontrain.nap.NapStore;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A crashed run comes back as a nap: at the title screen, built from the crashed save
 * ({@link CrashNapBuilder}) and saved as the pending nap for its slot, so Reboard wakes it in a new
 * world at the same band and depth with what the player had on them. The crashed world is then a
 * napped world, which the title-screen sweep deletes.
 *
 * <p>Only for a real crash ({@link CrashEvidence}), only for a run that was live (not an old-style
 * salvage session), and never over another nap waiting in the same slot. Anything else, and every
 * failure, leaves the run for the Salvage card, so nobody is worse off than before. Client-only.</p>
 */
final class CrashNapRecovery {

    private static final Logger LOGGER = LogUtils.getLogger();

    private CrashNapRecovery() {}

    /** True when {@code run} was turned into a nap (the record is then cleared). */
    static boolean tryRecover(Minecraft mc, CrashRunState.RunState run) {
        try {
            return recover(mc, run);
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Crash recovery: could not turn '{}' into a nap; offering salvage.",
                run.levelId(), e);
            return false;
        }
    }

    private static boolean recover(Minecraft mc, CrashRunState.RunState run) {
        if (run.status() != CrashRunState.Status.ACTIVE) return false;
        Path gameDir = mc.gameDirectory.toPath().toAbsolutePath().normalize();
        Path workingDir = Path.of("").toAbsolutePath().normalize();
        Optional<Path> evidence = CrashEvidence.find(gameDir, List.of(gameDir, workingDir), run.startedAtEpochMs());
        if (evidence.isEmpty()) {
            LOGGER.info("[DungeonTrain] Crash recovery: no crash report or JVM log since '{}' started; offering salvage.",
                run.levelId());
            return false;
        }
        UUID owner = mc.getUser().getProfileId();
        Path levelDir = mc.getLevelSource().getLevelPath(run.levelId());
        CrashNapBuilder.Result result = CrashNapBuilder.build(levelDir, run.levelId(), owner,
            NapService.modVersion(), WorldGenCycle.fromConfig(), System.currentTimeMillis());
        if (result instanceof CrashNapBuilder.Refused refused) {
            LOGGER.info("[DungeonTrain] Crash recovery: '{}' not turned into a nap ({}); offering salvage.",
                run.levelId(), refused.why());
            return false;
        }
        CrashNapBuilder.Built built = (CrashNapBuilder.Built) result;
        NapStore store = NapStore.local();
        if (store.hasFile(owner, built.slot())) {
            LOGGER.info("[DungeonTrain] Crash recovery: a nap already waits in slot {}; offering salvage for '{}'.",
                built.slot(), run.levelId());
            return false;
        }
        NapSnapshot nap = built.nap();
        try {
            store.save(owner, built.slot(), nap);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Crash recovery: nap for '{}' could not be saved; offering salvage.",
                run.levelId(), e);
            return false;
        }
        CrashRunState.clear(PlayerDataPaths.root());
        LOGGER.info("[DungeonTrain] Crash recovery: '{}' crashed ({}); saved as nap {} ({}) at x={} carriage={} depth={}",
            run.levelId(), evidence.get().getFileName(), nap.napId(), built.slot(), Math.round(nap.x()),
            nap.carriage(), nap.worldDepth());
        RunWorldCleanup.sweepNappedAsync(mc);
        return true;
    }
}

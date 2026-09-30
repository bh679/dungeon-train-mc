package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * One INFO line at server start saying whether <b>Sable Pathfinder</b> is installed.
 *
 * <p>Sable Pathfinder is {@code optional} in {@code neoforge.mods.toml} — required on Modrinth but
 * not listed on CurseForge — so two player populations run different mob-pathing code: with it,
 * every path node that reads as air asks Sable which sub-level (carriage) contains it, and the
 * villager POI / sleep / golem / raid brain logic runs in sub-level coordinates; without it,
 * carriages are opaque to vanilla pathfinding. A support log has to say which one a player had.
 * The 2026-09-30 headless A/B found no measurable tick cost either way, so this is a diagnostic,
 * not a gate.</p>
 *
 * <p>Fires on {@link ServerAboutToStartEvent}, which covers the single-player integrated server and
 * dedicated servers alike (same seam as {@code cheat/CheatModIntegrity}). The mod-list query is
 * wrapped so a broken mod list can never take the boot down.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class SablePathfinderPresence {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Mod id Sable Pathfinder declares in its {@code neoforge.mods.toml}. */
    public static final String MOD_ID = "sable_pathfinder";

    private SablePathfinderPresence() {}

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        try {
            ModList mods = ModList.get();
            boolean present = mods.isLoaded(MOD_ID);
            String version = present
                ? mods.getModContainerById(MOD_ID)
                    .map(c -> c.getModInfo().getVersion().toString())
                    .orElse(null)
                : null;
            LOGGER.info(describe(present, version));
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Could not read the mod list for Sable Pathfinder: {}", t.toString());
        }
    }

    /**
     * Pure: the log line for a present/absent Sable Pathfinder. Package-visible for unit tests —
     * no live {@link ModList} needed.
     *
     * @param present whether the mod is loaded
     * @param version its version when present, or {@code null} when unknown
     */
    static String describe(boolean present, @Nullable String version) {
        if (!present) {
            return "[DungeonTrain] Sable Pathfinder absent — vanilla mob pathing; carriages are opaque to pathfinding";
        }
        String v = version == null ? "unknown version" : "v" + version;
        return "[DungeonTrain] Sable Pathfinder present (" + v + ") — mobs path across carriages; "
            + "villager POI/sleep/golem logic runs in sub-level coordinates";
    }
}

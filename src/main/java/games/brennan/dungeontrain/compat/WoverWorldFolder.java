package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.mixin.wover.WoverWorldConfigInvoker;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.slf4j.Logger;

/**
 * Points WorldWeaver at a new world's folder before the world loads.
 *
 * <p>WorldWeaver keeps the current world's {@code data/} folder in a static field and only moves it on its
 * {@code WORLD_FOLDER_READY} event. Vanilla's Create World screen and {@code openWorld} fire that early, but
 * {@code WorldOpenFlows.createFreshLevel}, which starts every Dungeon Train run, gets it only from a late
 * {@code MinecraftServer} hook. In between, while dimensions load, WorldWeaver saves
 * {@code wover-generator.nbt} into the <em>previous</em> world's folder. That re-creates a run world
 * {@code RunWorldCleanup} just deleted, overwrites a kept world's settings with the new world's, and leaves
 * the new world without its own. Running WorldWeaver's own folder load as soon as the new folder exists
 * puts it where WorldWeaver's Create World path would; its late event then reloads the same folder.</p>
 */
public final class WoverWorldFolder {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean warned;

    private WoverWorldFolder() {}

    /** Point WorldWeaver's per-world config at {@code access}'s folder. Never throws. */
    public static void pointAt(LevelStorageSource.LevelStorageAccess access) {
        if (access == null) return;
        try {
            WoverWorldConfigInvoker.dungeontrain$loadForWorld(access);
            LOGGER.debug("[DungeonTrain] WorldWeaver config pointed at '{}' before load.", access.getLevelId());
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                LOGGER.warn("[DungeonTrain] Could not point WorldWeaver at the new world early; "
                        + "its generator file may land in the previous world's folder.", t);
            }
        }
    }
}

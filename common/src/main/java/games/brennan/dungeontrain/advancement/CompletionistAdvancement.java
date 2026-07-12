package games.brennan.dungeontrain.advancement;
import games.brennan.dungeontrain.DtCore;

import com.mojang.logging.LogUtils;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * The "Everything Burrito" capstone — a code-driven advancement granted once a
 * player has earned every <em>other</em> Dungeon-Train gameplay advancement
 * (the {@code dungeontrain:dungeon_train/*} tree), excluding the editor tree
 * and the capstone itself.
 *
 * <p>The advancement JSON
 * ({@code data/dungeontrain/advancement/dungeon_train/completionist.json})
 * carries a single {@code minecraft:impossible} criterion, so it never fires on
 * its own: {@link #checkAndGrant(ServerPlayer)} awards it directly, exactly as
 * the cross-world replay in {@code AchievementEvents} re-grants advancements
 * from {@link GlobalAchievementStore}. Direct award (rather than a custom
 * trigger) keeps the login backfill working regardless of game mode and adds no
 * registry surface. The direct award still passes through the same
 * cross-world persistence gate as every other advancement —
 * {@link games.brennan.dungeontrain.cheat.RunIntegrity#persistsAdvancement} in
 * {@code AchievementEvents} — so a Free-Play ("cheated") run cannot bank the
 * capstone even though it was granted directly rather than via a criterion
 * trigger.</p>
 *
 * <p><b>Dynamic by construction.</b> The required set is computed at call time
 * from {@link ServerAdvancementManager#getAllAdvancements()}, filtered to the
 * {@code dungeontrain} namespace, excluding the {@code editor/} path, this
 * advancement itself, and display-less (recipe-style) advancements. A new
 * {@code dungeon_train/*} advancement added in a future update is therefore
 * required automatically, with no change to this class. The {@code editor/}
 * partition is the same one
 * {@link games.brennan.dungeontrain.cheat.RunIntegrity#isEditorAdvancement}
 * uses to tell DT gameplay advancements (gated by run integrity) from editor
 * ones (which always persist).</p>
 */
public final class CompletionistAdvancement {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Stable id of the capstone; referenced by the wiring in {@code AchievementEvents}. */
    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath(DtCore.MOD_ID, "dungeon_train/completionist");

    private CompletionistAdvancement() {}

    /**
     * Grant the capstone to {@code player} iff every other non-editor
     * {@code dungeontrain} advancement is already done for them. Cheap and
     * idempotent: returns early when the server/advancement is unavailable or
     * the capstone is already earned, scans the live advancement registry for
     * any still-incomplete prerequisite, and only when none remain awards the
     * capstone via its own criteria keys (the single {@code impossible}
     * criterion).
     */
    public static void checkAndGrant(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerAdvancementManager mgr = server.getAdvancements();
        AdvancementHolder self = mgr.get(ID);
        if (self == null) return; // capstone data not loaded (e.g. datapack stripped)
        if (player.getAdvancements().getOrStartProgress(self).isDone()) return; // already earned

        for (AdvancementHolder holder : mgr.getAllAdvancements()) {
            ResourceLocation rl = holder.id();
            if (!DtCore.MOD_ID.equals(rl.getNamespace())) continue; // other mods / vanilla
            if (rl.getPath().startsWith("editor/")) continue;             // editor tree excluded
            if (rl.equals(ID)) continue;                                  // never require itself
            if (holder.value().display().isEmpty()) continue;             // skip recipe/display-less
            if (!player.getAdvancements().getOrStartProgress(holder).isDone()) return; // not complete yet
        }

        // Every required advancement is done — grant the capstone. Award each
        // criterion key (just "everything"), mirroring the sidecar replay loop.
        boolean granted = false;
        for (String key : self.value().criteria().keySet()) {
            if (player.getAdvancements().award(self, key)) granted = true;
        }
        if (granted) {
            LOGGER.info("[DungeonTrain] Granted completionist advancement (Everything Burrito) to {}",
                player.getName().getString());
        }
    }
}

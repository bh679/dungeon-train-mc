package games.brennan.dungeontrain.advancement;
import games.brennan.dungeontrain.DtCore;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * The "Pacifist" chain — three carriage-distance milestones (100 / 250 / 1000)
 * granted only when the player has dealt zero damage to anything this life.
 * Mirrors the {@code carts_100} counter used everywhere else, gated by
 * {@link games.brennan.dungeontrain.player.PlayerRunState#damageDealt()} —
 * both reset together on death, so the tiers become reachable again each
 * fresh life.
 *
 * <p>Each tier's JSON ({@code data/dungeontrain/advancement/dungeon_train/pacifist_*.json})
 * carries a single {@code minecraft:impossible} criterion, so none of them fire
 * on their own — {@link #checkAndGrant} awards them directly, exactly like
 * {@link FarStartAdvancement}. The condition (a counter threshold AND "zero of
 * a second per-run counter") isn't expressible as one standard trigger, so the
 * direct-award pattern is the natural fit.</p>
 */
public final class PacifistAdvancement {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final ResourceLocation ID_100 =
        ResourceLocation.fromNamespaceAndPath(DtCore.MOD_ID, "dungeon_train/pacifist_100");
    public static final ResourceLocation ID_250 =
        ResourceLocation.fromNamespaceAndPath(DtCore.MOD_ID, "dungeon_train/pacifist_250");
    public static final ResourceLocation ID_1000 =
        ResourceLocation.fromNamespaceAndPath(DtCore.MOD_ID, "dungeon_train/pacifist_1000");

    private record Tier(ResourceLocation id, int threshold) {}

    private static final Tier[] TIERS = {
        new Tier(ID_100, 100),
        new Tier(ID_250, 250),
        new Tier(ID_1000, 1000),
    };

    private PacifistAdvancement() {}

    /**
     * Pure gating decision, split out so it can be unit-tested without a
     * {@link ServerPlayer}. Grant when the player has traversed at least
     * {@code threshold} carriages this life AND has dealt zero damage this life.
     */
    static boolean shouldGrant(int travelledCarriagesAbs, double damageDealtThisRun, int threshold) {
        return travelledCarriagesAbs >= threshold && damageDealtThisRun == 0.0;
    }

    /**
     * Evaluate every tier for {@code player} and award any that are newly met.
     * Cheap in the common case: a single damage check short-circuits before
     * touching the advancement manager once the player has dealt any damage
     * this life.
     *
     * @param travelledCarriagesAbs {@code Math.abs(PlayerRunState.travelledCarriageIndex())}
     *                              — the same value {@code carts_100} keys off.
     * @param damageDealtThisRun    {@code PlayerRunState.damageDealt()} for this life.
     */
    public static void checkAndGrant(ServerPlayer player, int travelledCarriagesAbs, double damageDealtThisRun) {
        if (damageDealtThisRun != 0.0) return;
        for (Tier tier : TIERS) {
            if (travelledCarriagesAbs >= tier.threshold()) {
                grant(player, tier.id());
            }
        }
    }

    /**
     * Grant the given pacifist tier to {@code player}. Idempotent: returns
     * early when the advancement data isn't loaded or it's already earned,
     * then awards each criterion key (just the single {@code impossible}
     * criterion).
     */
    private static void grant(ServerPlayer player, ResourceLocation id) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerAdvancementManager mgr = server.getAdvancements();
        AdvancementHolder self = mgr.get(id);
        if (self == null) return; // advancement data not loaded (e.g. datapack stripped)
        if (player.getAdvancements().getOrStartProgress(self).isDone()) return; // already earned

        boolean granted = false;
        for (String key : self.value().criteria().keySet()) {
            if (player.getAdvancements().award(self, key)) granted = true;
        }
        if (granted) {
            LOGGER.info("[DungeonTrain] Granted pacifist advancement {} to {}", id, player.getName().getString());
        }
    }
}

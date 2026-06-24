package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.worldgen.Disintegration;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.NetherBand;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.List;

/**
 * Grants the disintegration-band journey advancements as a player rides the train across the
 * repeating Overworld → Void → End-islands → Void → Overworld bands (all in the overworld
 * dimension; see {@link Disintegration}). Each player's world-X is classified every
 * {@link #SCAN_PERIOD_TICKS} ticks via {@link DisintegrationBand#zoneAt}:
 *
 * <ul>
 *   <li>{@code VOID} → {@code reached_void} ("Voided Warranty")</li>
 *   <li>{@code END_ISLANDS} → {@code reached_end_islands} ("Tastes like Cheese?")</li>
 *   <li>{@code OVERWORLD} → {@code reached_overworld_again} ("Re-Over-World") — only once the
 *       player has already reached the void or the End islands, so it never fires from the spawn
 *       overworld.</li>
 * </ul>
 *
 * <p>Independently of the void/End {@code zoneAt} classification, the same scan also grants
 * {@code reached_nether} ("Entered the Nether") when the player's column reads as the Nether core
 * of the cycle's Nether band (via {@link NetherBand#isInNetherBiome}). The Nether band is a
 * separate phase of the same repeating {@link WorldGenCycle}, so it is checked separately.</p>
 *
 * <p>All four are one-shot {@code gameplay_action} markers (same trigger as
 * {@code landed_on_tracks} etc.); vanilla advancement dedupe makes re-firing the same id every
 * scan a no-op. When disintegration is disabled {@link DisintegrationBand#zoneAt} always returns
 * {@code OVERWORLD} and the overworld-again gate is never satisfied, so nothing fires.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ZoneProgressEvents {

    /** Zone-scan cadence (ticks) — matches the other per-level advancement scans. */
    private static final int SCAN_PERIOD_TICKS = 20;

    /**
     * How far (blocks) past the near edge of the Nether-core band the player must be before
     * {@code reached_nether} is granted — so the advancement reads as "deep in the Nether",
     * not "just touched the edge". The default core band (~520 blocks of Nether-reading column,
     * larger in dev-test) comfortably contains this depth.
     */
    private static final int NETHER_DEPTH_BLOCKS = 100;

    private static final ResourceLocation REACHED_VOID =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dungeon_train/reached_void");
    private static final ResourceLocation REACHED_END_ISLANDS =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dungeon_train/reached_end_islands");

    private ZoneProgressEvents() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!Level.OVERWORLD.equals(level.dimension())) return;
        if (level.getGameTime() % SCAN_PERIOD_TICKS != 0) return;
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        for (ServerPlayer player : players) {
            if (player.isSpectator()) continue;

            // Nether band — a separate phase of the cycle from the void/End classification below.
            // Only grant once the player is well INSIDE the Nether (not the moment they cross the
            // edge): the train travels +X, so the player enters the contiguous Nether-core band
            // from its -X side; requiring the column NETHER_DEPTH_BLOCKS behind them to also read
            // as Nether proves they are at least that deep. The band is a single trapezoid, so two
            // in-band samples imply everything between is in-band too.
            int px = player.getBlockX();
            if (NetherBand.isInNetherBiome(level, px)
                && NetherBand.isInNetherBiome(level, px - NETHER_DEPTH_BLOCKS)) {
                ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "reached_nether");
            }

            switch (DisintegrationBand.zoneAt(level, player.getBlockX())) {
                case VOID ->
                    ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "reached_void");
                case END_ISLANDS ->
                    ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "reached_end_islands");
                case OVERWORLD -> {
                    // "Reach the OW again" — guard against the spawn overworld by requiring the
                    // player to have already been to the void or the End islands at least once.
                    if (earned(player, REACHED_VOID) || earned(player, REACHED_END_ISLANDS)) {
                        ModAdvancementTriggers.GAMEPLAY_ACTION.get()
                            .trigger(player, "reached_overworld_again");
                    }
                }
            }
        }
    }

    /**
     * True when {@code player} has already completed the advancement {@code id}. A persistent,
     * data-driven gate (mirrors {@link games.brennan.dungeontrain.advancement.FarStartAdvancement}'s
     * progress lookup) — survives relogs and the cross-world achievement sidecar, unlike a
     * transient in-memory flag.
     */
    private static boolean earned(ServerPlayer player, ResourceLocation id) {
        AdvancementHolder holder = player.getServer().getAdvancements().get(id);
        return holder != null && player.getAdvancements().getOrStartProgress(holder).isDone();
    }
}

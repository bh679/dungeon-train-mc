package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.worldgen.ChuncksBand;
import games.brennan.dungeontrain.worldgen.Disintegration;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.NetherBand;
import games.brennan.dungeontrain.worldgen.UpsideDownBand;
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
 *   <li>{@code END_ISLANDS} → {@code reached_end_islands} ("End of the Line")</li>
 *   <li>{@code OVERWORLD} → {@code reached_overworld_again} ("Re-Over-World") — only once the
 *       player has already reached the void or the End islands (so it never fires from the spawn
 *       overworld) AND is outside the upside-down band/exit crossfade (see below — {@code zoneAt}
 *       alone would otherwise fire this the instant the mirrored band begins) AND outside the
 *       chuncks band and its run-up (see below), so it lands on the overworld that follows the
 *       whole journey rather than the gap leading into the last band.</li>
 * </ul>
 *
 * <p>Independently of the void/End {@code zoneAt} classification, the same scan also grants two
 * Nether-band markers when the player's column reads as the Nether core of the cycle's Nether band
 * (via {@link NetherBand#isInNetherBiome}): {@code reached_nether} ("Entered the Nether") on any
 * Nether band, and {@code nether_return_again} ("Nether Return Again") once the player is deep in
 * the Nether band on the SECOND or later cycle repeat (via {@link NetherBand#netherPassIndex} ≥ 1)
 * — i.e. they have looped all the way out to the End and back to solid ground, then returned to the
 * Nether. That second-band gate is positional, not advancement-based: the loop-completion
 * advancement {@code reached_overworld_again} is cross-world (a returning player already holds it on
 * login), so keying off world position is what stops the return from firing on the first pass. The
 * Nether band is a separate phase of the same repeating {@link WorldGenCycle}, so it is checked
 * separately.</p>
 *
 * <p>The scan also grants two markers for the upside-down band, which flows directly out of the
 * End band ({@code ... End islands → Void → Upside-down → exit-fade → Void → OW}, per
 * {@link WorldGenCycle}'s layout): {@code the_upside_down} ("The Upside Down") once the player is
 * {@code UD_BAND_DEPTH_BLOCKS} into the mirrored band (via {@link UpsideDownBand#isInBand}), and
 * {@code reassembly_required} ("Reassembly Required") once the player is
 * {@code UD_EXIT_FADE_DEPTH_BLOCKS} into the band's floating-island exit crossfade back to overworld
 * (via {@link UpsideDownBand#isInExitFade}) — both depth-gated the same way as {@code reached_nether},
 * since each zone is a single trapezoid.</p>
 *
 * <p>Last in the cycle comes the chuncks band — the mostly-void stretch of floating chunks (see
 * {@link ChuncksBand}) — which grants {@code reached_chuncks} ("Chunk Error") once the player is
 * {@code CHUNCKS_DEPTH_BLOCKS} into the band core, depth-gated like the others. That band, and the
 * plain-overworld run-up to it, is also what blocks {@code reached_overworld_again}: the gaps between
 * the upside-down exit and the chuncks entry read as {@code OVERWORLD} to {@code zoneAt}, but the world
 * has not settled back yet, so "Re-Over-World" waits for the overworld <em>after</em> the chunks (via
 * {@link ChuncksBand#isInApproachOrBand}). With the chuncks band disabled that gate is inert and the
 * pre-chuncks behaviour is unchanged.</p>
 *
 * <p>All eight are one-shot {@code gameplay_action} markers (same trigger as
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
    private static final int NETHER_DEPTH_BLOCKS = 400;

    /**
     * How far (blocks) into the upside-down band the player must be before {@code the_upside_down} is
     * granted — so it reads as "properly inside the mirrored world", not "just touched the leading
     * edge". The train travels +X so the player enters from the -X side; requiring the column this far
     * behind them to also read as in-band proves they are at least that deep.
     */
    private static final int UD_BAND_DEPTH_BLOCKS = 500;

    /**
     * How far (blocks) into the upside-down exit crossfade the player must be before
     * {@code reassembly_required} is granted — so it reads as "well into the dispersing islands",
     * not "just crossed the band's trailing edge". The default exit-fade span (10,000 blocks)
     * comfortably contains this depth.
     */
    private static final int UD_EXIT_FADE_DEPTH_BLOCKS = 3800;

    /**
     * How far (blocks) into the chuncks band core the player must be before {@code reached_chuncks} is
     * granted — so it reads as "riding through the broken world", not "just crossed the edge". The band
     * core is a single contiguous X range, so two in-band samples imply everything between; the default
     * core (5,000 blocks) comfortably contains this depth.
     */
    private static final int CHUNCKS_DEPTH_BLOCKS = 500;

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
                // "Nether Return Again" — deep inside the Nether band on the SECOND (or later)
                // cycle repeat: the player has ridden a full loop out to the End and back to solid
                // overworld ground, then returned to the Nether. Gated purely on world position
                // (the cycle-repeat index), NOT on an earned advancement — reached_overworld_again
                // is a cross-world sidecar advancement a returning player already holds on login,
                // which would otherwise fire this on the first pass. The first Nether band is cycle
                // repeat 0, so index >= 1 is the second band onward.
                if (NetherBand.netherPassIndex(level, px) >= 1) {
                    ModAdvancementTriggers.GAMEPLAY_ACTION.get()
                        .trigger(player, "nether_return_again");
                }
            }

            // Upside-down band — a separate phase from the void/End classification below, flowing
            // directly out of the End band. Both markers are depth-gated the same way (the band and
            // the exit crossfade are each a single trapezoid, so two in-zone samples imply everything
            // between is too): requiring the column UD_BAND_DEPTH_BLOCKS / UD_EXIT_FADE_DEPTH_BLOCKS
            // behind the player to also read in-zone proves they are properly inside, not just at the
            // leading edge.
            if (UpsideDownBand.isInBand(level, px)
                && UpsideDownBand.isInBand(level, px - UD_BAND_DEPTH_BLOCKS)) {
                ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "the_upside_down");
            }
            if (UpsideDownBand.isInExitFade(level, px)
                && UpsideDownBand.isInExitFade(level, px - UD_EXIT_FADE_DEPTH_BLOCKS)) {
                ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "reassembly_required");
            }

            // Chuncks band — the cycle's last phase, a mostly-void stretch of floating chunks. Same
            // depth gate as the bands above: the core is one contiguous X range, so requiring the column
            // CHUNCKS_DEPTH_BLOCKS behind the player to also read in-band proves they are properly
            // inside it and not just at the leading edge.
            if (ChuncksBand.isInBand(level, px)
                && ChuncksBand.isInBand(level, px - CHUNCKS_DEPTH_BLOCKS)) {
                ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "reached_chuncks");
            }

            switch (DisintegrationBand.zoneAt(level, player.getBlockX())) {
                case VOID ->
                    ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "reached_void");
                case END_ISLANDS ->
                    ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "reached_end_islands");
                case OVERWORLD -> {
                    // "Reach the OW again" — guard against the spawn overworld by requiring the
                    // player to have already been to the void or the End islands at least once.
                    // Also exclude the ENTIRE upside-down stretch — the band, its entry lead-in, and
                    // its exit crossfade — so this fires only once the player is back on real ground
                    // AFTER the upside-down islands finish. The entry lead-in covers the End band's
                    // trailing fade (the OVERWORLD sliver zoneAt reads right before the mirrored band,
                    // since End flows into it with no void gap); without excluding it, "overworld
                    // again" would fire before the player ever reaches the upside-down band, and the
                    // exit-fade exclusion keeps it from firing among the dispersing islands.
                    // Finally, exclude the chuncks band AND the plain-overworld run-up to it — those
                    // gaps read as OVERWORLD but the world breaks apart again straight after them, so
                    // the overworld has not actually restarted until the band is behind the player.
                    if ((earned(player, REACHED_VOID) || earned(player, REACHED_END_ISLANDS))
                        && !UpsideDownBand.isInBandEntryLeadOrExit(level, px)
                        && !ChuncksBand.isInApproachOrBand(level, px)) {
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

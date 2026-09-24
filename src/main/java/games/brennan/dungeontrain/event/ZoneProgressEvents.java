package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.BandAdvancements;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.worldgen.ChuncksBand;
import games.brennan.dungeontrain.worldgen.Disintegration;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.NetherBand;
import games.brennan.dungeontrain.worldgen.SpheresBand;
import games.brennan.dungeontrain.worldgen.StacksBand;
import games.brennan.dungeontrain.worldgen.UpsideDownBand;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBands;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.List;

/**
 * Grants the journey advancements as a player rides the train through the repeating world-gen bands
 * (all in the overworld dimension; see {@link WorldGenCycle}). Every {@link #SCAN_PERIOD_TICKS} ticks
 * each player's world-X is run through {@link BandAdvancements#triggers()}: a band's advancement is
 * granted once the player's column <em>and</em> the column {@code depth} blocks behind them both read
 * as that band — the train travels +X, so the player enters from the -X side and two in-band samples
 * of a contiguous core prove they are properly inside it, not just at the leading edge. The table of
 * bands, their ids and their column tests lives in {@link BandAdvancements}, so a band added to the
 * {@link games.brennan.dungeontrain.worldgen.CycleLayout} is picked up here without a new branch.
 *
 * <p>Two markers stay positional in a different way, keyed on the <em>pass</em> rather than the band:</p>
 * <ul>
 *   <li>{@code nether_return_again} ("Nether Return Again") — deep in a Nether band on the SECOND or
 *       later Nether pass (via {@link NetherBand#netherPassIndex} ≥ 1): the player has looped all the
 *       way out and come back to the Nether.</li>
 *   <li>{@code reached_overworld_again} ("Re-Over-World") — on plain overworld once the player is on
 *       the second or later cycle repeat ({@link DisintegrationBand#cyclePassIndex} ≥ 1) AND outside
 *       every band's approach-or-band window, so it lands on the overworld that follows the whole
 *       journey rather than a gap between bands.</li>
 * </ul>
 *
 * <p>Both gates are positional, not advancement-based: the journey advancements are cross-world
 * sidecar advancements a returning player already holds on login, so an advancement gate would fire
 * them a few hundred blocks into a fresh world's spawn overworld.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ZoneProgressEvents {

    /** Ticks between scans — one per second is plenty; the train moves a few blocks a second. */
    private static final int SCAN_PERIOD_TICKS = 20;

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
            int px = player.getBlockX();

            for (BandAdvancements.Trigger t : BandAdvancements.triggers()) {
                if (t.test().test(level, px) && t.test().test(level, px - t.depth())) {
                    ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, t.id());
                }
            }

            // "Nether Return Again" — deep inside a Nether band on the SECOND (or later) Nether pass.
            // The first Nether band is pass 0, so index >= 1 is the second band onward.
            if (NetherBand.isInNetherBiome(level, px)
                && NetherBand.isInNetherBiome(level, px - BandAdvancements.ENTRY_DEPTH_BLOCKS)
                && NetherBand.netherPassIndex(level, px) >= 1) {
                ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "nether_return_again");
            }

            // "Re-Over-World" — plain overworld on the SECOND (or later) cycle repeat, i.e. every band of
            // the first pass is behind the player, and outside every band's approach window: the gaps
            // between bands read as OVERWORLD to zoneAt but the world has not settled back yet (the
            // upside-down entry lead-in / exit fade, and each later band's lead gap, fade and core).
            if (DisintegrationBand.zoneAt(level, px) == Disintegration.Zone.OVERWORLD
                && DisintegrationBand.cyclePassIndex(level, px) >= 1
                && !UpsideDownBand.isInBandEntryLeadOrExit(level, px)
                && !ChuncksBand.isInApproachOrBand(level, px)
                && !SpheresBand.isInApproachOrBand(level, px)
                && !StacksBand.isInApproachOrBand(level, px)
                && !LegacyBands.isInAnyApproachOrBand(level, px)) {
                ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, BandAdvancements.OVERWORLD_AGAIN);
            }
        }
    }
}

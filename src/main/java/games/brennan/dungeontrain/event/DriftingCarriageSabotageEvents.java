package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.PlayerMobLifeBridge;
import games.brennan.dungeontrain.train.SharedCarriageLookup;
import games.brennan.dungeontrain.train.SharedCarriageRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Bringing TNT into a drifting carriage is remembered, and the player's next echo comes back
 * angrier for it.
 *
 * <p>A drifting carriage is a room somebody built by hand. Nothing else in the mod distinguishes
 * a destructive edit from a constructive one — {@code SharedCarriageAdvancementEvents} awards
 * {@code drift_edit_other} for hanging a lantern and for pulling up the floor alike. This does,
 * for the one act least ambiguous about its intent, and routes it to PlayerMob's per-life record
 * (see {@code PlayerMobLifeBridge}), which weighs it into the Fight/Flight the player's echo is
 * born with.</p>
 *
 * <p><b>Deliberately silent.</b> Nothing is said at the time — no chat line, no advancement. The
 * consequence arrives later, when the echo does.</p>
 *
 * <p><b>Deliberately narrow.</b> Placing the TNT <em>block</em> is the whole trigger. A TNT
 * minecart is an entity on a different event; lighting TNT somebody else placed is not placing it;
 * breaking blocks is destructive but ordinary (players loot drifting carriages by design, and the
 * narrative books treat that as fair). Widening this is a separate decision, not an oversight.</p>
 *
 * <p>Every registered drifting carriage counts — a fresh local build you are about to author as
 * much as one leased from the pool that a stranger made. There is no consent gate: unlike the
 * advancements next door, the credit is a local {@code SavedData} write that never leaves this
 * machine.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DriftingCarriageSabotageEvents {

    private DriftingCarriageSabotageEvents() {}

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!isSabotage(event.getPlacedBlock())) return;
        SharedCarriageRegistry.Instance inst = SharedCarriageLookup.byBlockPos(level, event.getPos());
        if (inst == null || inst.isCulled()) return;
        PlayerMobLifeBridge.creditSabotage(player);
    }

    /** Whether placing {@code state} in a drifting carriage counts as sabotage. */
    static boolean isSabotage(BlockState state) {
        return state.is(Blocks.TNT);
    }
}

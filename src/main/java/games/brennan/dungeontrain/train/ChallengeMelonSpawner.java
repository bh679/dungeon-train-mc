package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.advancement.AllAtOnceAdvancement;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.event.AchievementEvents;
import games.brennan.dungeontrain.portal.PortalCarriageSelection;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Leaves a melon slice on a flatbed for players still holding "All at Once" / "The Challenge" —
 * the nothing-but-melon, no-chest, no-break rules make food otherwise hard to come by.
 *
 * <p>Once the player is {@link #MIN_TRAVELLED} carriages into the life, every
 * {@link #GROUP_INTERVAL}th flatbed gets one slice, dropped the moment the player is in a room next
 * to it. A flatbed is the FRONT half-pad of one group plus the BACK half-pad of the next; it is
 * keyed by the anchor pIdx of the group after it, and the slice lands on whichever half belongs to
 * the player's own group (already loaded and ticked). Each flatbed is remembered per life in
 * {@link ModDataAttachments#CHALLENGE_MELON_FLATBEDS_THIS_LIFE}, so a relog never drops a second one.</p>
 *
 * <p>Spawned at shipyard coordinates, so Sable carries the item with the moving carriage.</p>
 */
public final class ChallengeMelonSpawner {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Carriages travelled this life before the first melon. */
    static final int MIN_TRAVELLED = 10;
    /** One melon flatbed every this many carriage groups. */
    static final int GROUP_INTERVAL = 5;

    private ChallengeMelonSpawner() {}

    // ---- pure rules (unit-tested) ----

    /** Does the flatbed in front of the group anchored at {@code anchor} carry a melon? */
    static boolean isMelonFlatbed(int anchor, int groupSize) {
        if (anchor == 0) return false; // the spawn group's back pad is the train's tail
        long groupIndex = Math.floorDiv((long) anchor, Math.max(1, groupSize));
        return Math.floorMod(groupIndex, GROUP_INTERVAL) == 0;
    }

    /**
     * The flatbed (keyed by the anchor of the group after it) next to the room {@code playerPIdx}
     * in the group anchored at {@code anchor}, or {@code null} when that room touches no flatbed.
     */
    static Integer adjacentFlatbed(int playerPIdx, int anchor, int groupSize) {
        if (playerPIdx == anchor) return anchor;                              // first room → back pad
        if (playerPIdx == anchor + groupSize - 1) return anchor + groupSize; // last room → front pad
        return null;
    }

    // ---- live ----

    /** Called every boarding scan for each boarded player. Cheap when the player is not eligible. */
    public static void tick(ServerLevel level, List<Trains.Carriage> carriages, ServerPlayer player) {
        if (!isEligible(player)) return;

        Trains.Carriage group = PlayerMobBehindSpawner.groupContaining(
            carriages, player.getX(), player.getY(), player.getZ());
        if (group == null) return;
        TrainTransformProvider provider = group.provider();
        int anchor = provider.getPIdx();
        int groupSize = provider.getGroupSize();
        if (groupSize <= 1) return; // no half-pads, so no flatbeds

        Integer flatbed = adjacentFlatbed(pIdxWithinGroup(group, player), anchor, groupSize);
        if (flatbed == null || !isMelonFlatbed(flatbed, groupSize)) return;
        if (PortalCarriageSelection.isPortalGroup(level, anchor)) return;

        List<Integer> done = player.getData(ModDataAttachments.CHALLENGE_MELON_FLATBEDS_THIS_LIFE.get());
        if (done.contains(flatbed)) return;

        BlockPos pos = padFloorCentre(provider, flatbed == anchor);
        ItemEntity melon = new ItemEntity(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
            new ItemStack(Items.MELON_SLICE), 0.0, 0.0, 0.0);
        melon.setUnlimitedLifetime();
        if (!level.addFreshEntity(melon)) return;

        List<Integer> updated = new ArrayList<>(done);
        updated.add(flatbed);
        player.setData(ModDataAttachments.CHALLENGE_MELON_FLATBEDS_THIS_LIFE.get(), List.copyOf(updated));
        LOGGER.info("[DungeonTrain] Challenge melon for {} on flatbed before group {} at {}",
            player.getName().getString(), flatbed, pos);
    }

    private static boolean isEligible(ServerPlayer player) {
        if (!player.isAlive() || RunIntegrity.isCheated(player)) return false;
        if (AllAtOnceAdvancement.anyRuleBroken(player)) return false;
        if (isEarned(player)) return false;
        int travelled = AchievementEvents.effectiveTravelled(
            player.getData(ModDataAttachments.PLAYER_RUN_STATE.get()));
        return travelled >= MIN_TRAVELLED;
    }

    private static boolean isEarned(ServerPlayer player) {
        var server = player.getServer();
        if (server == null) return true;
        AdvancementHolder holder = server.getAdvancements().get(AllAtOnceAdvancement.THE_CHALLENGE);
        return holder != null && player.getAdvancements().getOrStartProgress(holder).isDone();
    }

    /**
     * The player's carriage index read in their own group's frame — the same reading as
     * {@code TrainCarriageAppender#pIdxWithinGroup}.
     */
    private static int pIdxWithinGroup(Trains.Carriage group, ServerPlayer player) {
        TrainTransformProvider provider = group.provider();
        CarriageDims dims = provider.dims();
        Vector3d local = new Vector3d(player.getX(), player.getY(), player.getZ());
        group.ship().worldToShip(local);
        int slot = (int) Math.floor(
            (local.x - provider.getShipyardOrigin().getX() - CarriagePlacer.halfPadLen(dims))
                / (double) dims.length());
        return provider.getPIdx() + slot;
    }

    /** Shipyard cell just above the deck at the centre of the group's back or front half-pad. */
    private static BlockPos padFloorCentre(TrainTransformProvider provider, boolean backPad) {
        CarriageDims dims = provider.dims();
        int padLen = CarriagePlacer.halfPadLen(dims);
        int padStartX = backPad ? 0 : padLen + provider.getGroupSize() * dims.length();
        return provider.getShipyardOrigin().offset(padStartX + padLen / 2, 1, dims.width() / 2);
    }
}

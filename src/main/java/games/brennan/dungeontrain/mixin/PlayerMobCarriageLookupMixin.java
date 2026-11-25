package games.brennan.dungeontrain.mixin;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.NearbyCarriages;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Answers PlayerMob's "which carriage is at this point?" from Dungeon Train's per-tick carriage index
 * ({@link NearbyCarriages#at}) instead of its own walk over {@link Trains#allCarriages}.
 *
 * <p>{@code DungeonTrainEnvironment.carriageAtPos} is behind every per-tick train question a PlayerMob
 * goal asks ({@code isOnTrain}, {@code sharesBlockFrame}, {@code toBlockFrame}, {@code fromBlockFrame},
 * {@code carriageIndex}...), several times per echo per tick. Walking every carriage each time made it
 * cost echoes x carriages. Same answer, same tie-break (first carriage in shipyard order whose box,
 * grown by PlayerMob's 1-block ride margin, holds the point), but only resident carriages.</p>
 *
 * <p>Targeted by name and {@code require = 0}: if a PlayerMob release renames the method this simply
 * stops applying and PlayerMob's own walk answers. The first redirected call logs once, so a test can
 * see it is live. Same signature from PlayerMob 0.126.0 (DT's floor) to 0.131.0.</p>
 */
@Mixin(targets = "games.brennan.playermob.neoforge.compat.DungeonTrainEnvironment", remap = false)
public abstract class PlayerMobCarriageLookupMixin {

    /** PlayerMob's {@code RIDE_MARGIN}: blocks a box is grown by when judging "on this carriage". */
    @Unique
    private static final double DUNGEONTRAIN_RIDE_MARGIN = 1.0;

    @Unique
    private static final Logger DUNGEONTRAIN_LOGGER = LogUtils.getLogger();

    @Unique
    private static boolean dungeontrain$announced;

    @Inject(method = "carriageAtPos", at = @At("HEAD"), cancellable = true, require = 0)
    private static void dungeontrain$cachedCarriageAt(ServerLevel level, double x, double y, double z,
                                                      CallbackInfoReturnable<Trains.Carriage> cir) {
        if (!dungeontrain$announced) {
            dungeontrain$announced = true;
            DUNGEONTRAIN_LOGGER.info("[DungeonTrain] PlayerMob carriage lookups served from the per-tick carriage index");
        }
        cir.setReturnValue(NearbyCarriages.at(level, x, y, z, DUNGEONTRAIN_RIDE_MARGIN));
    }
}

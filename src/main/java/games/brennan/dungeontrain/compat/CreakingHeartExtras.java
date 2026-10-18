package games.brennan.dungeontrain.compat;

import com.blackgear.vanillabackport.client.registries.ModSoundEvents;
import com.blackgear.vanillabackport.common.level.blocks.CreakingHeartBlock;
import com.blackgear.vanillabackport.common.level.blocks.states.CreakingHeartState;
import com.blackgear.vanillabackport.common.level.entities.creaking.Creaking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The Creaking a heart keeps beyond VanillaBackport's own one, when its
 * {@link CreakingHeartSettings#count} is above 1.
 *
 * <p>VanillaBackport tracks a single Creaking per heart. The extras are DT's: spawned the same way
 * (its {@code spawnProtector}, so they are home-bound to the heart), only while that main Creaking
 * is out, and held to the same rules: torn down when the heart sleeps, when one strays past the
 * 34-block leash (measured in world space, see {@link CreakingHeartPositions}), when a player is
 * stuck inside one, and with the main one when the heart is broken. The heart's mixin makes
 * {@code isProtector} accept them, which is what keeps a home-bound Creaking alive.</p>
 */
public final class CreakingHeartExtras {

    /** How often, in ticks, the extras are checked and topped up. */
    private static final int PERIOD = 20;
    /** Ticks a missing extra is waited for after load before it is forgotten (VB waits 30). */
    private static final int LOAD_GRACE = 40;
    private static final double LEASH = 34.0;
    private static final double PLAYER_RANGE = 32.0;

    private CreakingHeartExtras() {}

    /**
     * One heart tick. Returns the extras list to keep, or {@code extras} itself when unchanged.
     *
     * @param spawn spawns one home-bound Creaking for this heart (VB's {@code spawnProtector}), or null
     */
    public static List<UUID> tick(ServerLevel level, BlockPos pos, CreakingHeartSettings settings,
                                  boolean hasMain, long age, List<UUID> extras,
                                  Supplier<@Nullable Creaking> spawn) {
        if (level.getGameTime() % PERIOD != Math.floorMod(pos.asLong(), PERIOD)) return extras;
        boolean awake = isAwake(level.getBlockState(pos));
        Vec3 home = CreakingHeartPositions.worldPoint(level, pos, 0.5, 0, 0.5);

        List<UUID> kept = new ArrayList<>(extras.size());
        for (UUID id : extras) {
            if (!(level.getEntity(id) instanceof Creaking creaking) || creaking.isRemoved()) {
                if (age < LOAD_GRACE) kept.add(id);
                continue;
            }
            if (!awake || creaking.distanceToSqr(home) > LEASH * LEASH || creaking.playerIsStuckInYou()) {
                creaking.tearDown();
            } else {
                kept.add(id);
            }
        }

        if (awake && hasMain && kept.size() < settings.count() - 1 && maySpawn(level, pos, home)) {
            Creaking extra = spawn.get();
            if (extra != null) {
                kept.add(extra.getUUID());
                extra.playSound(ModSoundEvents.CREAKING_SPAWN.get());
            }
        }
        return kept.equals(extras) ? extras : List.copyOf(kept);
    }

    /** Tears every extra down: quietly ({@code source} null), or as killed by {@code source}. */
    public static void removeAll(ServerLevel level, List<UUID> extras, @Nullable DamageSource source) {
        for (UUID id : extras) {
            if (!(level.getEntity(id) instanceof Creaking creaking) || creaking.isRemoved()) continue;
            if (source == null) {
                creaking.tearDown();
            } else {
                creaking.creakingDeathEffects(source);
                creaking.setTearingDown();
                creaking.setHealth(0.0F);
            }
        }
    }

    private static boolean isAwake(BlockState state) {
        return state.getBlock() instanceof CreakingHeartBlock
            && state.getValue(CreakingHeartBlock.STATE) == CreakingHeartState.AWAKE;
    }

    /** The same gates VanillaBackport puts on its own spawn, from the heart's world position. */
    private static boolean maySpawn(ServerLevel level, BlockPos pos, Vec3 home) {
        if (level.getDifficulty() == Difficulty.PEACEFUL) return false;
        if (!CreakingHeartPositions.spawningAllowed(level, pos,
                level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING))) return false;
        return level.getNearestPlayer(home.x, home.y, home.z, PLAYER_RANGE, false) != null;
    }
}

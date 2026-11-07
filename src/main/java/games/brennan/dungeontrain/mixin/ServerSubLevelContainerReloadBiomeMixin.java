package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap;
import games.brennan.dungeontrain.ship.sable.SavedPlotBiome;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.UUID;

/**
 * Keeps a sub-level coming back from holding from waiting on a chunk for a biome it throws away.
 *
 * <p>{@code createSubLevel} seeds the plot biome with {@code level.getBiome(pose)}, guarded by
 * {@code level.isLoaded(pose)}. That guard only checks the holder's ticket level, so a chunk still
 * generating passes it and {@code getBiome} blocks the server thread in {@code getChunk}: 38.3 s and
 * 1.2 s ticks measured after a far exit from a dimensional carriage, where the pose of a culled group
 * lands in the terrain the player's arrival has just started loading. {@code fullyLoad} then calls
 * {@code plot.load}, which overwrites the biome with the saved one, so on a reload the answer was
 * never used.</p>
 *
 * <p>So a sub-level that is still in the holding map and whose save holds a biome answers "not
 * loaded", which takes Sable's own skip branch. This sits below every reload path (DT's and Sable's
 * own wake), the reload stays in the same tick, and a fresh sub-level has no holding entry and reads
 * the world as before, because there the biome is the only one it will ever get.</p>
 *
 * <p>Bytecode-verified against {@code sable-2.0.5+mc1.21.1}: {@code createSubLevel} has one
 * {@code ServerLevel.isLoaded(BlockPos)} call; {@code loadHoldingSubLevel} removes the entry from
 * {@code allHoldingSubLevels} only after {@code fullyLoad} returns; the plot biome is read only by
 * {@code newEmptyChunk}, {@code save} and {@code load}, and no {@code onSubLevelAdded} observer
 * between allocation and {@code plot.load} reaches them. <b>Re-verify on any {@code sable_version}
 * bump.</b> {@code remap = false}: Sable's own names; Minecraft's are the same in dev and
 * production on NeoForge.</p>
 */
@Mixin(value = ServerSubLevelContainer.class, remap = false)
public abstract class ServerSubLevelContainerReloadBiomeMixin {

    @Shadow
    public abstract SubLevelHoldingChunkMap getHoldingChunkMap();

    @WrapOperation(
        method = "createSubLevel",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;isLoaded(Lnet/minecraft/core/BlockPos;)Z")
    )
    private boolean dungeonTrain$skipBiomeTheSaveOverwrites(
        ServerLevel level, BlockPos pos, Operation<Boolean> original, @Local(argsOnly = true) UUID uuid
    ) {
        SubLevelHoldingChunkMap holding = getHoldingChunkMap();
        HoldingSubLevel held = holding == null ? null : holding.getHoldingSubLevel(uuid);
        boolean restored = held != null && SavedPlotBiome.restoredFromSave(held.data().fullTag());
        return !restored && original.call(level, pos);
    }
}

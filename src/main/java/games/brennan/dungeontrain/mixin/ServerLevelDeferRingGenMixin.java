package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import games.brennan.dungeontrain.worldgen.StrongholdRingGate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Skips the {@code ServerLevel} constructor's eager stronghold ring search for the overworld, so
 * {@link StrongholdRingGate#start} can run it once DT's biome context is published. Other dimensions
 * keep vanilla's eager start. Any error keeps the vanilla call, so no world is left without rings.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelDeferRingGenMixin {

    @WrapWithCondition(
        method = "<init>",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkGeneratorStructureState;ensureStructuresGenerated()V"))
    private boolean dungeontrain$deferOverworldRings(ChunkGeneratorStructureState state) {
        try {
            return ((Level) (Object) this).dimension() != Level.OVERWORLD;
        } catch (Throwable t) {
            return true;
        }
    }
}

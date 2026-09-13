package games.brennan.dungeontrain.mixin;

import com.google.gson.JsonElement;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.requirement.AdvancementRequirementOverrides;
import games.brennan.dungeontrain.advancement.requirement.RequirementJsonRewriter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.ServerAdvancementManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Map;

/**
 * Applies the relay's requirement overrides to Dungeon Train's advancements as the datapack
 * loads, and hands every requirement found to {@code AdvancementRequirements}.
 *
 * <p>Sits on the raw JSON map {@code ServerAdvancementManager.apply} receives, before vanilla
 * parses a single advancement — so the criterion's threshold and the description's argument are
 * rewritten together and the parsed {@code AdvancementHolder}s never know a jar value existed.
 * Runs on every apply: world open and {@code /reload} alike. See
 * {@link RequirementJsonRewriter} for what is rewritten and
 * {@link AdvancementRequirementOverrides} for where the values come from.</p>
 */
@Mixin(ServerAdvancementManager.class)
public abstract class ServerAdvancementManagerRequirementsMixin {

    @ModifyVariable(
        method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;"
            + "Lnet/minecraft/util/profiling/ProfilerFiller;)V",
        at = @At("HEAD"),
        argsOnly = true)
    private Map<ResourceLocation, JsonElement> dungeontrain$applyRequirementOverrides(
            Map<ResourceLocation, JsonElement> loaded) {
        Map<ResourceLocation, Long> overrides = AdvancementRequirementOverrides.effective();
        Map<ResourceLocation, JsonElement> rewritten =
            RequirementJsonRewriter.rewriteAll(loaded, overrides, DungeonTrain.MOD_ID);
        AdvancementRequirementOverrides.markApplied(overrides);
        return rewritten;
    }
}

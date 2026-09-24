package games.brennan.dungeontrain.mixin;

import com.google.gson.JsonElement;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.BandAdvancementChainRewriter;
import games.brennan.dungeontrain.advancement.BandAdvancements;
import games.brennan.dungeontrain.advancement.requirement.AdvancementDisabler;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.advancement.requirement.AdvancementFlag;
import games.brennan.dungeontrain.advancement.requirement.AdvancementRequirementOverrides;
import games.brennan.dungeontrain.advancement.requirement.ForeignAdvancementFilter;
import games.brennan.dungeontrain.advancement.requirement.RequirementJsonRewriter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.ServerAdvancementManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Map;

/**
 * Applies the relay's requirement overrides to Dungeon Train's advancements as the datapack
 * loads, drops the ones the relay has disabled, and hands every requirement found to
 * {@code AdvancementRequirements}. BetterNether's and BetterEnd's advancement tabs are dropped
 * first — see {@link ForeignAdvancementFilter}.
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
        AdvancementRequirementOverrides.Payload payload = AdvancementRequirementOverrides.effectivePayload();
        Map<ResourceLocation, JsonElement> rewritten =
            RequirementJsonRewriter.rewriteAll(ForeignAdvancementFilter.removeBlocked(loaded), payload.values(), DungeonTrain.MOD_ID);
        Map<ResourceLocation, JsonElement> enabled =
            AdvancementDisabler.removeAll(rewritten, payload.with(AdvancementFlag.DISABLED), DungeonTrain.MOD_ID);
        AdvancementRequirementOverrides.markApplied(payload);
        // Journey chain: with an ordered band layout the parents follow the layout, not the jar JSON.
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (!cycle.hasLayout()) return enabled;
        return BandAdvancementChainRewriter.rewriteParents(enabled, BandAdvancements.chain(cycle.layout()),
            BandAdvancements.ANCHOR, DungeonTrain.MOD_ID);
    }
}

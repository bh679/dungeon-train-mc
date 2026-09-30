package games.brennan.dungeontrain.mixin.terrablender;

import games.brennan.dungeontrain.worldgen.DtSurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import terrablender.api.SurfaceRuleManager;
import terrablender.worldgen.surface.NamespacedSurfaceRuleSource;

import java.util.HashMap;
import java.util.Map;

/**
 * Keeps DT's own surface rule in charge of vanilla biomes in DT's worlds.
 *
 * <p>TerraBlender's {@code getNamespacedRules(category, fallback)} builds the rule its
 * {@code NoiseGeneratorSettings} mixin returns: {@code "minecraft"} → TerraBlender's default rule, plus each
 * mod's namespace rules, with {@code fallback} (the settings' own rule) only as the base. For DT's settings
 * we drop the {@code "minecraft"} entry, so vanilla biomes fall straight through to DT's rule — the one with
 * the 1-thick bedrock floor — while other namespaces (Biomes O' Plenty's) keep their own rules first.
 * Any other world's settings are left exactly as TerraBlender builds them. See {@link DtSurfaceRules}.</p>
 */
@Mixin(value = SurfaceRuleManager.class, remap = false)
public abstract class SurfaceRuleManagerMixin {

    @Shadow
    private static Map<SurfaceRuleManager.RuleCategory, Map<String, SurfaceRules.RuleSource>> surfaceRules;

    @Inject(method = "getNamespacedRules", at = @At("HEAD"), cancellable = true)
    private static void dungeontrain$keepDtRuleForVanillaBiomes(SurfaceRuleManager.RuleCategory category,
                                                               SurfaceRules.RuleSource fallback,
                                                               CallbackInfoReturnable<SurfaceRules.RuleSource> cir) {
        if (DtSurfaceRules.ownerOf(fallback).isEmpty()) return;
        Map<String, SurfaceRules.RuleSource> namespaced = new HashMap<>(surfaceRules.get(category));
        namespaced.remove("minecraft");
        cir.setReturnValue(new NamespacedSurfaceRuleSource(fallback, Map.copyOf(namespaced)));
    }
}

package games.brennan.dungeontrain.compat;

import com.blackgear.vanillabackport.common.worldgen.ModSurfaceRuleData;
import com.mojang.logging.LogUtils;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.slf4j.Logger;

/**
 * VanillaBackport's surface rule — the sulfur / cinnabar bands through a sulfur cave's rock (and its dappled
 * forest's coarse dirt).
 *
 * <p>VanillaBackport adds it two ways and DT's worlds miss both: straight into {@code minecraft:overworld}'s
 * noise settings (DT's overworld is {@code dungeontrain:overworld}), and into TerraBlender's default
 * {@code minecraft} rule, which {@code SurfaceRuleManagerMixin} drops for DT's settings. So that mixin puts it
 * in front of DT's own rule instead. Every branch is gated on one of VanillaBackport's biomes, so no other
 * biome's surface changes.</p>
 */
public final class VanillaBackportSurfaceRules {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile SurfaceRules.RuleSource rules;
    private static volatile boolean failed;

    private VanillaBackportSurfaceRules() {}

    /** VanillaBackport's overworld surface rule, built once; {@code null} if it can't be built. */
    public static SurfaceRules.RuleSource rules() {
        SurfaceRules.RuleSource built = rules;
        if (built != null || failed) return built;
        try {
            built = ModSurfaceRuleData.makeRules();
            rules = built;
            return built;
        } catch (Throwable t) {                          // a VanillaBackport update moved or broke it
            failed = true;
            LOGGER.error("[DungeonTrain] Couldn't build VanillaBackport's surface rule — sulfur caves in"
                    + " Dungeon Train worlds will be plain stone", t);
            return null;
        }
    }
}

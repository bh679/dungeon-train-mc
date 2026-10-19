package games.brennan.dungeontrain.mixin;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.compat.mixinguard.MixinTargetCheck;
import games.brennan.dungeontrain.compat.mixinguard.MixinTargetRequirement;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.util.List;
import java.util.Set;

/**
 * Gates {@code dungeontrain.windy.mixins.json}: DT steers Windy's wind streaks through the "Living Wind" hook
 * that <b>Windy: Configurable</b> (the CurseForge fork, 1.2.1+) carries in {@code LivingWindCompat}. The
 * original Windy (the Modrinth build) has no such class, and a player may have no Windy at all — in both cases
 * the mixin is skipped and DT draws its own vanilla-particle wind instead ({@code client.wind.WindClientEvents}, which re-checks via {@code WindyPresence}).
 *
 * <p>Runs during early class transformation, so it asks {@link LoadingModList}, then reads the target class
 * bytes and checks the static methods the mixin hooks are still there with the same descriptors — a
 * renamed method in a future Windy skips the mixin with a WARN instead of failing the boot.</p>
 */
public final class WindyMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String WINDY_MODID = "windy";
    public static final String TARGET = "xyz.bonfiremc.windy.LivingWindCompat";

    private final boolean apply = decide();

    private static boolean decide() {
        try {
            if (LoadingModList.get().getModFileById(WINDY_MODID) == null) {
                LOGGER.info("[DungeonTrain] Windy not installed; train wind uses DT's own particles");
                return false;
            }
            ClassNode node = MixinService.getService().getBytecodeProvider().getClassNode(TARGET.replace('.', '/'));
            List<String> misses = MixinTargetCheck.missing(node, List.of(
                    MixinTargetRequirement.method("isInstalled", "()Z"),
                    MixinTargetRequirement.method("isActive", "()Z"),
                    MixinTargetRequirement.method("strength", "()F"),
                    MixinTargetRequirement.method("directionDegrees", "()F"),
                    MixinTargetRequirement.method("velocityX", "(Z)D"),
                    MixinTargetRequirement.method("velocityZ", "(Z)D"),
                    MixinTargetRequirement.method("spawnRateMultiplier", "(F)D")));
            if (!misses.isEmpty()) {
                LOGGER.warn("[DungeonTrain] Windy's LivingWindCompat no longer has {}; train wind uses DT's own "
                        + "particles instead of Windy's streaks", String.join(", ", misses));
                return false;
            }
            LOGGER.info("[DungeonTrain] Windy: Configurable found; train wind steers its streaks");
            return true;
        } catch (Throwable t) {
            // The original (Modrinth) Windy has no LivingWindCompat: the class read throws and lands here.
            LOGGER.info("[DungeonTrain] This Windy build has no wind-direction hook ({}); train wind uses DT's "
                    + "own particles alongside it", t.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return apply;
    }

    @Override
    public void onLoad(String mixinPackage) {
        // no-op
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // no-op
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // no-op
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // no-op
    }
}

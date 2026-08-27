package games.brennan.dungeontrain.mixin;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gates {@code dungeontrain.betteradvancements.mixins.json} so its mixins apply <em>only</em> when
 * Better Advancements is installed. It is a bundled modpack companion (shipped ON by default, but
 * NOT a compile dependency), so its target classes
 * {@code betteradvancements.common.gui.BetterAdvancementWidget} and
 * {@code betteradvancements.common.util.CriterionGrid} are absent from any install without it.
 * Without this gate Mixin would log an error applying a mixin to a missing class; with it,
 * {@link #shouldApplyMixin} short-circuits to a clean no-op.
 *
 * <p>Mirrors {@link EffortlessBuildingMixinPlugin} exactly, including its use of
 * {@link LoadingModList}: the check runs during early class transformation, before
 * {@code ModList.get()} is populated.</p>
 */
public final class BetterAdvancementsMixinPlugin implements IMixinConfigPlugin {

    private static final String BETTER_ADVANCEMENTS_MODID = "betteradvancements";

    /** Resolved once — mod presence is fixed for the JVM lifetime. */
    private final boolean betterAdvancementsLoaded = detectBetterAdvancements();

    private static boolean detectBetterAdvancements() {
        try {
            return LoadingModList.get().getModFileById(BETTER_ADVANCEMENTS_MODID) != null;
        } catch (Throwable t) {
            // If the loader state can't be read for any reason, fail safe: do not apply the mixin.
            return false;
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return betterAdvancementsLoaded;
    }

    @Override
    public void onLoad(String mixinPackage) {
        // no-op
    }

    @Override
    public String getRefMapperConfig() {
        return null; // use the refmap declared in the mixin config
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // no-op
    }

    @Override
    public List<String> getMixins() {
        return null; // mixins are listed in the config file
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

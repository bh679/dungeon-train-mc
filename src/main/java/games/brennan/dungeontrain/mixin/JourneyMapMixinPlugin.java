package games.brennan.dungeontrain.mixin;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gates {@code dungeontrain.journeymap.mixins.json} so its mixins apply <em>only</em> when JourneyMap
 * is installed. JourneyMap is neither bundled nor required (players add it themselves), and DT only
 * compiles against it, so its classes are absent from most installs; with this gate
 * {@link #shouldApplyMixin} short-circuits to a clean no-op there.
 *
 * <p>Mirrors {@link BetterAdvancementsMixinPlugin}, including {@link LoadingModList}: the check runs
 * during early class transformation, before {@code ModList.get()} is populated. {@link #postApply}
 * logs each patched class, so a JourneyMap update that moves a target shows up as a missing line.</p>
 */
public final class JourneyMapMixinPlugin implements IMixinConfigPlugin {

    private static final String JOURNEYMAP_MODID = "journeymap";

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** Resolved once — mod presence is fixed for the JVM lifetime. */
    private final boolean journeyMapLoaded = detectJourneyMap();

    private static boolean detectJourneyMap() {
        try {
            return LoadingModList.get().getModFileById(JOURNEYMAP_MODID) != null;
        } catch (Throwable t) {
            // If the loader state can't be read for any reason, fail safe: do not apply the mixin.
            return false;
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return journeyMapLoaded;
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
        LOGGER.info("[DungeonTrain] JourneyMap portal-seal map filter applied to {}", targetClassName);
    }
}

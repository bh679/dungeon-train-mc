package games.brennan.dungeontrain.mixin;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gates {@code dungeontrain.distanthorizons.mixins.json} so its mixins apply <em>only</em> when
 * Distant Horizons is installed. DH is an optional companion (never bundled, and only an API jar at
 * compile time), so its {@code GLState} class is absent from most installs; the mixin is
 * {@code @Pseudo}, and this gate keeps Mixin from even looking for it.
 *
 * <p>Mirrors {@link BetterAdvancementsMixinPlugin}, including {@link LoadingModList}: the check runs
 * during early class transformation, before {@code ModList.get()} is populated. {@link #postApply}
 * logs which {@code GLState} got patched, so a DH update that moves the class shows up as a missing
 * line rather than as the native crashes returning.</p>
 */
public final class DistantHorizonsMixinPlugin implements IMixinConfigPlugin {

    private static final String DISTANT_HORIZONS_MODID = "distanthorizons";

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** Resolved once — mod presence is fixed for the JVM lifetime. */
    private final boolean distantHorizonsLoaded = detectDistantHorizons();

    private static boolean detectDistantHorizons() {
        try {
            return LoadingModList.get().getModFileById(DISTANT_HORIZONS_MODID) != null;
        } catch (Throwable t) {
            // If the loader state can't be read for any reason, fail safe: do not apply the mixin.
            return false;
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return distantHorizonsLoaded;
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
        LOGGER.info("[DungeonTrain] Distant Horizons GL_POLYGON_MODE overflow guard applied to {}", targetClassName);
    }
}

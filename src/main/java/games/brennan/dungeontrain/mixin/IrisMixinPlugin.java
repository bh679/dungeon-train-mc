package games.brennan.dungeontrain.mixin;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gates {@code dungeontrain.iris.mixins.json} so its mixins apply <em>only</em> when Iris is
 * installed. Iris is not a compile dependency and not present in a plain dev client, so its target class
 * {@code net.irisshaders.iris.Iris} is absent from
 * plain NeoForge installs. Without this gate, Mixin would log an error trying to apply a mixin to a
 * missing class; with it, {@link #shouldApplyMixin} short-circuits to a clean no-op.
 *
 * <p>The check runs during early class transformation, before {@code ModList.get()} is populated, so
 * it uses {@link LoadingModList} (available at that phase) rather than the runtime {@code ModList}.</p>
 */
public final class IrisMixinPlugin implements IMixinConfigPlugin {

    private static final String IRIS_MODID = "iris";

    /** Resolved once — mod presence is fixed for the JVM lifetime. */
    private final boolean irisLoaded = detectIris();

    private static boolean detectIris() {
        try {
            return LoadingModList.get().getModFileById(IRIS_MODID) != null;
        } catch (Throwable t) {
            // If the loader state can't be read for any reason, fail safe: do not apply the mixin.
            return false;
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return irisLoaded;
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

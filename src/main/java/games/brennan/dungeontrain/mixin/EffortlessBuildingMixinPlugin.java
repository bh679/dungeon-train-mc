package games.brennan.dungeontrain.mixin;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gates {@code dungeontrain.effortlessbuilding.mixins.json} so its mixins apply <em>only</em> when
 * Effortless Building is installed. It is an optional modpack companion (shipped off by default, NOT
 * a compile dependency), so its target class
 * {@code neoforge.nl.requios.effortlessbuilding.network.PacketHandler} is absent from most installs.
 * Without this gate Mixin would log an error applying a mixin to a missing class; with it,
 * {@link #shouldApplyMixin} short-circuits to a clean no-op.
 *
 * <p>Mirrors {@link VivecraftMixinPlugin} exactly, including its use of {@link LoadingModList}: the
 * check runs during early class transformation, before {@code ModList.get()} is populated.</p>
 */
public final class EffortlessBuildingMixinPlugin implements IMixinConfigPlugin {

    private static final String EFFORTLESS_BUILDING_MODID = "effortlessbuilding";

    /** Resolved once — mod presence is fixed for the JVM lifetime. */
    private final boolean effortlessBuildingLoaded = detectEffortlessBuilding();

    private static boolean detectEffortlessBuilding() {
        try {
            return LoadingModList.get().getModFileById(EFFORTLESS_BUILDING_MODID) != null;
        } catch (Throwable t) {
            // If the loader state can't be read for any reason, fail safe: do not apply the mixin.
            return false;
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return effortlessBuildingLoaded;
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

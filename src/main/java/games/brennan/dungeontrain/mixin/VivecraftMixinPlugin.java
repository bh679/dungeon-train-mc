package games.brennan.dungeontrain.mixin;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gates {@code dungeontrain.vivecraft.mixins.json} so its mixins apply <em>only</em> when Vivecraft
 * is installed. Vivecraft is an optional, player-added mod (NOT a compile dependency and NOT in the
 * Dungeon Train modpack), so its target class {@code org.vivecraft.client_vr.gameplay.trackers.SwingTracker}
 * is absent from most installs. Without this gate, Mixin would log an error trying to apply a mixin
 * to a missing class; with it, {@link #shouldApplyMixin} short-circuits to a clean no-op.
 *
 * <p>The check runs during early class transformation, before {@code ModList.get()} is populated, so
 * it uses {@link LoadingModList} (available at that phase) rather than the runtime {@code ModList}.</p>
 */
public final class VivecraftMixinPlugin implements IMixinConfigPlugin {

    private static final String VIVECRAFT_MODID = "vivecraft";

    /** Resolved once — mod presence is fixed for the JVM lifetime. */
    private final boolean vivecraftLoaded = detectVivecraft();

    private static boolean detectVivecraft() {
        try {
            return LoadingModList.get().getModFileById(VIVECRAFT_MODID) != null;
        } catch (Throwable t) {
            // If the loader state can't be read for any reason, fail safe: do not apply the mixin.
            return false;
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return vivecraftLoaded;
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

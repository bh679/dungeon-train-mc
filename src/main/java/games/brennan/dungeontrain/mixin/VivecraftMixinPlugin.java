package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.client.vivecraft.VivecraftFixGate;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gates {@code dungeontrain.vivecraft.mixins.json} so DT's VR melee fix applies only when it is
 * actually needed. This class does one job — read the loader state — and hands the decision to
 * {@link VivecraftFixGate#shouldApply(boolean, boolean)}, which documents the full matrix, explains
 * why DT carries a copy at all, and says when to delete all of this.
 *
 * <p>The check runs during early class transformation, before {@code ModList.get()} is populated, so
 * it uses {@link LoadingModList} (available at that phase) rather than the runtime {@code ModList}.
 * Contrast {@code VrCompat}, which asks the same question about Vivecraft long after load and can
 * use {@code ModList}.</p>
 */
public final class VivecraftMixinPlugin implements IMixinConfigPlugin {

    /** Resolved once — mod presence is fixed for the JVM lifetime. */
    private final boolean apply = VivecraftFixGate.shouldApply(
            isLoaded(VivecraftFixGate.VIVECRAFT_MODID),
            isLoaded(VivecraftFixGate.COMPAT_MODID));

    /**
     * Is {@code modId} in the load list? {@code false} when the loader state cannot be read.
     *
     * <p>That failure direction is safe: an unreadable state reports Vivecraft absent, which skips
     * the mixin entirely, so the addon answer never gets to matter on its own.</p>
     */
    private static boolean isLoaded(String modId) {
        try {
            return LoadingModList.get().getModFileById(modId) != null;
        } catch (Throwable t) {
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

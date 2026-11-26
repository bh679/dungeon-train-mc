package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.compat.mixinguard.PositionReadoutMods;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gates {@code dungeontrain.positionreadout.mixins.json}: the hooks that make approved map and HUD mods
 * print the position the debug screen prints ({@code client/CoordinateDisguise}). Each mixin applies
 * only when its own mod is installed — none of these mods is bundled or required, and DT only
 * compiles against them — so on most installs every one is a clean no-op.
 *
 * <p>Mirrors {@link JourneyMapMixinPlugin}, {@link LoadingModList} included (it runs before
 * {@code ModList.get()} is filled). {@link #postApply} logs each patched class, so a mod update that
 * moves a target shows up as a missing line; the readout then simply tells the truth.</p>
 */
public final class PositionReadoutMixinPlugin implements IMixinConfigPlugin {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** Resolved once per mod id — mod presence is fixed for the JVM lifetime. */
    private final Map<String, Boolean> loaded = new ConcurrentHashMap<>();

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String modId = PositionReadoutMods.modFor(mixinClassName);
        return modId != null && loaded.computeIfAbsent(modId, PositionReadoutMixinPlugin::isLoaded);
    }

    private static boolean isLoaded(String modId) {
        try {
            return LoadingModList.get().getModFileById(modId) != null;
        } catch (Throwable t) {
            // If the loader state can't be read for any reason, fail safe: do not apply the mixin.
            return false;
        }
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
        LOGGER.info("[DungeonTrain] Position readout hook {} applied to {}", mixinClassName, targetClassName);
    }
}

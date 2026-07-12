package games.brennan.dungeontrain.fabric;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Fabric twin of the NeoForge {@code VivecraftMixinPlugin}: gates
 * {@code dungeontrain.vivecraft.mixins.json} so its mixin applies only when Vivecraft is
 * installed (its target class is absent otherwise). Uses {@code FabricLoader.isModLoaded}
 * (available during early class transformation on Fabric) instead of NeoForge's
 * {@code LoadingModList}.
 */
public final class FabricVivecraftMixinPlugin implements IMixinConfigPlugin {

    private static final String VIVECRAFT_MODID = "vivecraft";

    private final boolean vivecraftLoaded = detectVivecraft();

    private static boolean detectVivecraft() {
        try {
            return FabricLoader.getInstance().isModLoaded(VIVECRAFT_MODID);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return vivecraftLoaded;
    }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String t, ClassNode c, String m, IMixinInfo i) {}
    @Override public void postApply(String t, ClassNode c, String m, IMixinInfo i) {}
}

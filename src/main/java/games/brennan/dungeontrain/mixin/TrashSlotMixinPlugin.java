package games.brennan.dungeontrain.mixin;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gates {@code dungeontrain.trashslot.mixins.json} so its mixin applies <em>only</em> when TrashSlot is
 * installed. TrashSlot is a modpack companion, not a dependency, so its classes are absent from many
 * installs; there {@link #shouldApplyMixin} is a clean no-op. Mirrors {@link JourneyMapMixinPlugin}.
 */
public final class TrashSlotMixinPlugin implements IMixinConfigPlugin {

    private static final String TRASHSLOT_MODID = "trashslot";

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** Resolved once: mod presence is fixed for the JVM lifetime. */
    private final boolean trashSlotLoaded = detectTrashSlot();

    private static boolean detectTrashSlot() {
        try {
            return LoadingModList.get().getModFileById(TRASHSLOT_MODID) != null;
        } catch (Throwable t) {
            // Loader state unreadable: fail safe and leave TrashSlot as shipped.
            return false;
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return trashSlotLoaded;
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
        LOGGER.info("[DungeonTrain] TrashSlot tooltip draw-order fix applied to {}", targetClassName);
    }
}

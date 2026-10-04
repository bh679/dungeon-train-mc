package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.locale.CaseLocaleGuard;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Plugin for the always-loaded {@code dungeontrain.mixins.json}. It gates nothing — every mixin applies.
 * It exists for {@link #onLoad}, which runs during Mixin bootstrap on client and dedicated server alike,
 * before any mod's classes initialise: the earliest point DT gets to run {@link CaseLocaleGuard}, ahead of
 * the codecs that bake lowercased names. Must not touch Minecraft classes.
 */
public final class CoreMixinPlugin implements IMixinConfigPlugin {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("DungeonTrain");

    @Override
    public void onLoad(String mixinPackage) {
        CaseLocaleGuard.Result result = CaseLocaleGuard.apply();
        if (result.changed()) {
            LOGGER.info("[DungeonTrain] System locale {} lowercases 'I' to a dotless 'ı'; text casing now uses"
                    + " the root locale so mods' internal names parse (number/date formatting unchanged)",
                    result.original().toLanguageTag());
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}

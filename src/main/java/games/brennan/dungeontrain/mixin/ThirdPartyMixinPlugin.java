package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.compat.mixinguard.MixinGuardReport;
import games.brennan.dungeontrain.compat.mixinguard.MixinTargetCheck;
import games.brennan.dungeontrain.compat.mixinguard.MixinTargetRequirement;
import games.brennan.dungeontrain.compat.mixinguard.ThirdPartyMixinTargets;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gates {@code dungeontrain.betterend.mixins.json} and {@code dungeontrain.terrablender.mixins.json}: every
 * mixin there hooks a WorldWeaver / BCLib / BetterEnd / TerraBlender <b>internal</b> by name, and those
 * libraries are declared {@code [floor,)}. Before a mixin applies, this reads its target classes' bytecode
 * and checks each member and call site listed in {@link ThirdPartyMixinTargets}. If anything is missing, a
 * newer library build has moved it: the mixin is skipped with one WARN naming what moved and what the
 * player gets instead, and the skip is recorded in {@link MixinGuardReport} so the feature can fall back.
 * Without this, one renamed method would stop the game from loading.
 *
 * <p>The decision is made once per mixin, for all its targets together, so a multi-target mixin is either
 * applied everywhere or nowhere. {@code shouldApplyMixin} runs before Mixin looks the target class up, so a
 * removed class is reported here too rather than failing as "target not found".</p>
 */
public final class ThirdPartyMixinPlugin implements IMixinConfigPlugin {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private final Map<String, Boolean> decisions = new ConcurrentHashMap<>();

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return decisions.computeIfAbsent(mixinClassName, ThirdPartyMixinPlugin::decide);
    }

    private static boolean decide(String mixinClassName) {
        ThirdPartyMixinTargets.Spec spec = ThirdPartyMixinTargets.forMixin(mixinClassName);
        if (spec == null) {
            LOGGER.error("[DungeonTrain] {} has no entry in ThirdPartyMixinTargets; applying it unchecked",
                    mixinClassName);
            return true;
        }
        List<String> misses = new ArrayList<>();
        for (Map.Entry<String, List<MixinTargetRequirement>> entry : spec.allChecked().entrySet()) {
            String owner = entry.getKey();
            ClassNode node;
            try {
                node = readClass(owner);
            } catch (Exception e) {
                misses.add(owner + " (class not readable: " + e + ")");
                continue;
            }
            for (String miss : MixinTargetCheck.missing(node, entry.getValue())) {
                misses.add(owner + ": " + miss);
            }
        }
        if (misses.isEmpty()) return true;
        MixinGuardReport.recordSkipped(mixinClassName);
        LOGGER.warn("[DungeonTrain] {} {} no longer has what {} hooks ({}). Skipping that mixin: {}. "
                        + "Re-check the mixin list for {} in gradle.properties.",
                spec.modId(), libraryVersion(spec.modId()), simpleName(mixinClassName),
                String.join("; ", misses), spec.degradesTo(), spec.modId());
        return false;
    }

    /**
     * The class's bytecode, read the way Mixin's own {@code ClassInfo} reads a target before it is loaded.
     * (ModLauncher's service rejects the untransformed {@code getClassNode(name, false)} outright.)
     */
    private static ClassNode readClass(String className) throws Exception {
        return MixinService.getService().getBytecodeProvider().getClassNode(className.replace('.', '/'));
    }

    private static String libraryVersion(String modId) {
        try {
            ModFileInfo file = LoadingModList.get().getModFileById(modId);
            return file == null ? "(not loaded)" : file.versionString();
        } catch (Throwable t) {
            return "(version unknown)";
        }
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
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

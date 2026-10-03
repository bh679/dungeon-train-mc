package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.compat.mixinguard.MixinApplyCheck;
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
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Gates {@code dungeontrain.betterend.mixins.json} and {@code dungeontrain.terrablender.mixins.json}: every
 * mixin there hooks a WorldWeaver / BCLib / BetterEnd / TerraBlender <b>internal</b> by name, and those
 * libraries are declared {@code [floor,)}. Before a mixin applies, this reads its target classes' bytecode
 * and checks each member and call site listed in {@link ThirdPartyMixinTargets}. If anything is missing, a
 * newer library build has moved it: the mixin is skipped with one WARN naming what moved and what the
 * player gets instead, and the skip is recorded in {@link MixinGuardReport} so the feature can fall back.
 * Without this, one renamed method would stop the game from loading.
 *
 * <p>A miss is blamed on the library that owns the class it is in, which is not always the library the
 * mixin is named after. After a mixin applies, {@link #postApply} checks its hooks took hold, because with
 * {@code defaultRequire: 0} an injector can still miss in silence.</p>
 *
 * <p>The decision is made once per mixin, for all its targets together, so a multi-target mixin is either
 * applied everywhere or nowhere. {@code shouldApplyMixin} runs before Mixin looks the target class up, so a
 * removed class is reported here too rather than failing as "target not found".</p>
 */
public final class ThirdPartyMixinPlugin implements IMixinConfigPlugin {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private final Map<String, Boolean> decisions = new ConcurrentHashMap<>();
    private final Set<String> unhookedReported = ConcurrentHashMap.newKeySet();

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
        // Library → what moved in it. Sorted, so the WARN reads the same on every boot.
        Map<String, List<String>> missesByLibrary = new TreeMap<>();
        for (Map.Entry<String, List<MixinTargetRequirement>> entry : new TreeMap<>(spec.allChecked()).entrySet()) {
            String owner = entry.getKey();
            List<String> misses = missesIn(owner, entry.getValue());
            if (misses.isEmpty()) continue;
            missesByLibrary.computeIfAbsent(libraryLabel(owner), library -> new ArrayList<>())
                    .add(simpleName(owner) + ": " + String.join(", ", misses));
        }
        if (missesByLibrary.isEmpty()) return true;
        MixinGuardReport.recordSkipped(mixinClassName);
        LOGGER.warn("[DungeonTrain] Skipping {}: {}. Instead: {}. Re-check the {} mixin list in gradle.properties.",
                simpleName(mixinClassName), describe(missesByLibrary), spec.degradesTo(),
                String.join(" / ", missesByLibrary.keySet()));
        return false;
    }

    private static List<String> missesIn(String owner, List<MixinTargetRequirement> requirements) {
        try {
            return MixinTargetCheck.missing(readClass(owner), requirements);
        } catch (Throwable t) {
            // Throwable, not Exception: a LinkageError reading the class must skip the mixin, not stop the boot.
            return List.of("class not readable (" + t + ")");
        }
    }

    private static String describe(Map<String, List<String>> missesByLibrary) {
        return missesByLibrary.entrySet().stream()
                .map(e -> e.getKey() + " " + libraryVersion(e.getKey()) + " no longer has "
                        + String.join("; ", e.getValue()))
                .collect(Collectors.joining("; and "));
    }

    /** The library that owns {@code className} — the one a miss in that class is blamed on. */
    private static String libraryLabel(String className) {
        String library = ThirdPartyMixinTargets.libraryOf(className);
        return library == null ? "an unlisted library" : library;
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

    /**
     * The configs set {@code defaultRequire: 0}, so an injector that misses does so silently even when the
     * target check passed. Look at the applied class: a hook nothing calls did not take hold, and the mixin
     * is reported exactly as a skipped one, so the feature falls back the same way.
     */
    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        List<String> unhooked;
        try {
            unhooked = MixinApplyCheck.unhooked(targetClass, mixinInfo.getClassNode(0));
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Could not verify {} on {}: {}", simpleName(mixinClassName),
                    simpleName(targetClassName), t.toString());
            return;
        }
        if (unhooked.isEmpty()) return;
        MixinGuardReport.recordSkipped(mixinClassName);
        if (!unhookedReported.add(mixinClassName)) return; // one WARN per mixin, however many targets
        ThirdPartyMixinTargets.Spec spec = ThirdPartyMixinTargets.forMixin(mixinClassName);
        String library = libraryLabel(targetClassName);
        LOGGER.warn("[DungeonTrain] {} applied, but its hook {} did not take hold in {} {}'s {} (that code "
                        + "changed shape). Treating the mixin as skipped. Instead: {}. Re-check the {} mixin "
                        + "list in gradle.properties.",
                simpleName(mixinClassName), String.join(", ", unhooked), library, libraryVersion(library),
                simpleName(targetClassName), spec == null ? "(no fallback listed)" : spec.degradesTo(), library);
    }
}

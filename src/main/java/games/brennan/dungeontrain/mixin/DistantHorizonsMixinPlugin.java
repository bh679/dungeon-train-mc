package games.brennan.dungeontrain.mixin;

import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.neoforgespi.locating.IModFile;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Gates {@code dungeontrain.distanthorizons.mixins.json} so its mixins apply <em>only</em> when
 * Distant Horizons is installed, and only to the target classes its jar actually contains.
 *
 * <p>DH is an optional companion (never bundled, and only an API jar at compile time), so its
 * {@code GLState} class is absent from most installs; the mixin is {@code @Pseudo}, and this gate
 * keeps Mixin from even looking for it.</p>
 *
 * <p>{@code DhGlStateSaveMixin} names <em>two</em> targets because DH moved {@code GLState} between
 * its 2.x and 3.x lines. Mixin asks {@link #shouldApplyMixin} once per target <em>before</em> it
 * loads that target, so answering {@code false} for the path the installed jar lacks stops Mixin
 * probing it — that probe otherwise logs {@code Error loading class: …GLState
 * (ClassNotFoundException)} at every start, a red herring next to the real "applied to" line.
 * The check reads the jar's file listing through {@link IModFile#findResource}; if that fails for
 * any reason it answers {@code true} and lets Mixin probe as before, so a surprise in the loader
 * costs a log line, never the guard.</p>
 *
 * <p>Mirrors {@link BetterAdvancementsMixinPlugin}, including {@link LoadingModList}: the check runs
 * during early class transformation, before {@code ModList.get()} is populated. {@link #postApply}
 * logs which {@code GLState} got patched, so a DH update that moves the class shows up as a missing
 * line rather than as the native crashes returning.</p>
 */
public final class DistantHorizonsMixinPlugin implements IMixinConfigPlugin {

    private static final String DISTANT_HORIZONS_MODID = "distanthorizons";

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** DH's mod file, or {@code null} when DH is not installed. Resolved once — fixed for the JVM lifetime. */
    private final IModFile distantHorizonsFile = detectDistantHorizons();

    private static IModFile detectDistantHorizons() {
        try {
            ModFileInfo info = LoadingModList.get().getModFileById(DISTANT_HORIZONS_MODID);
            return info == null ? null : info.getFile();
        } catch (Throwable t) {
            // If the loader state can't be read for any reason, fail safe: do not apply the mixin.
            return null;
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (distantHorizonsFile == null) return false;
        boolean present = jarContains(distantHorizonsFile, targetClassName);
        if (!present) {
            LOGGER.debug("[DungeonTrain] Distant Horizons jar has no {}; skipping that target of {}",
                    targetClassName, mixinClassName);
        }
        return present;
    }

    /**
     * Whether {@code jar} ships {@code className}. Answers {@code true} on any failure so Mixin falls
     * back to probing the class itself — the pre-existing behaviour.
     */
    private static boolean jarContains(IModFile jar, String className) {
        try {
            Path entry = jar.findResource(className.replace('.', '/') + ".class");
            return entry != null && Files.exists(entry);
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Could not read the Distant Horizons jar listing for {}: {}",
                    className, t.toString());
            return true;
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
        LOGGER.info("[DungeonTrain] Distant Horizons GL_POLYGON_MODE overflow guard applied to {}", targetClassName);
    }
}

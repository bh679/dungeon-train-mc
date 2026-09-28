package games.brennan.dungeontrain.mixin;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gates {@code dungeontrain.sodium.mixins.json} so its mixins apply <em>only</em> when Sodium is
 * installed. Sodium is not a compile dependency and not present in the dev client, so its target class
 * {@code net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer} is absent from
 * plain NeoForge installs. Without this gate, Mixin would log an error trying to apply a mixin to a
 * missing class; with it, {@link #shouldApplyMixin} short-circuits to a clean no-op.
 *
 * <p>The check runs during early class transformation, before {@code ModList.get()} is populated, so
 * it uses {@link LoadingModList} (available at that phase) rather than the runtime {@code ModList}.</p>
 */
public final class SodiumMixinPlugin implements IMixinConfigPlugin {

    private static final String SODIUM_MODID = "sodium";

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /**
     * Mixins that {@code @Shadow} Sodium internals — fields and private queues, not just an injection
     * point. A shadow that no longer exists is a <b>fatal</b> apply error under {@code required:
     * true}, unlike a missing injection point under {@code defaultRequire: 0}, so these apply only on
     * the Sodium line they were written against ({@link #PREWARM_SODIUM_LINE}). Elsewhere the
     * dimensional-carriage prewarm stands down and the game still launches.
     */
    private static final Set<String> SHADOWING_MIXINS = Set.of(
        "games.brennan.dungeontrain.mixin.client.sodium.RenderSectionPrewarmMixin",
        "games.brennan.dungeontrain.mixin.client.sodium.RenderSectionManagerPrewarmMixin");

    /** The Sodium minor line whose {@code RenderSection}/{@code RenderSectionManager} shape is shadowed. */
    private static final String PREWARM_SODIUM_LINE = "0.8.";

    /** Resolved once — mod presence is fixed for the JVM lifetime. */
    private final boolean sodiumLoaded = detectSodium();

    /** Sodium's declared version, or {@code ""} when absent or unreadable. */
    private final String sodiumVersion = readSodiumVersion();

    private static boolean detectSodium() {
        try {
            return LoadingModList.get().getModFileById(SODIUM_MODID) != null;
        } catch (Throwable t) {
            // If the loader state can't be read for any reason, fail safe: do not apply the mixin.
            return false;
        }
    }

    private static String readSodiumVersion() {
        try {
            var file = LoadingModList.get().getModFileById(SODIUM_MODID);
            if (file == null || file.getMods().isEmpty()) return "";
            return String.valueOf(file.getMods().get(0).getVersion());
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!sodiumLoaded) return false;
        if (SHADOWING_MIXINS.contains(mixinClassName)) {
            boolean apply = sodiumVersion.startsWith(PREWARM_SODIUM_LINE);
            LOGGER.info("[DungeonTrain] Sodium {} — {} {}", sodiumVersion,
                apply ? "applying" : "standing down", mixinClassName);
            return apply;
        }
        return true;
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

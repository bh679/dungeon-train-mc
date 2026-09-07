package games.brennan.dungeontrain.mixin;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gates {@code dungeontrain.vanillarenderer.mixins.json} so its mixins apply <em>only</em> when
 * Sodium is <b>absent</b> — the exact inverse of {@link SodiumMixinPlugin}.
 *
 * <h2>Why this has to exist</h2>
 * <p>Sodium's {@code core.render.world.LevelRendererMixin} <b>merges</b>
 * {@code LevelRenderer.setupRender}, and Mixin refuses to inject into a method merged by another
 * mixin of equal priority. That refusal is an {@code InvalidInjectionException} raised during
 * INJECT_PREPARE, and it is <b>fatal</b>: mod loading aborts and the client never reaches the title
 * screen.</p>
 *
 * <p><b>{@code require = 0} does not help.</b> It suppresses "no injection point matched" and
 * nothing else — a distinction that cost a crash on every Sodium and Iris install on 2026-09-08,
 * shipped on the strength of a comment claiming those mixins would "vanish quietly" under Sodium.
 * They do not vanish. They take the game with them. Presence has to be tested before the injection
 * is ever prepared, which is what a config plugin is for.</p>
 *
 * <p>Nothing is lost by standing down: Sodium replaces the whole path these hooks attach to, so even
 * where they could apply they would be decorating a method that no longer runs. What Sodium needs
 * instead lives in {@code dungeontrain.sodium.mixins.json}, against Sodium's own classes.</p>
 *
 * <p><b>The fail-safe answer is inverted here</b>, and deliberately. {@link SodiumMixinPlugin} says
 * "if the loader state cannot be read, do not apply" because its mixins are useless without Sodium.
 * This one says the same — do not apply — for a stronger reason: a hook that stays away costs a
 * cosmetic flash, and one that lands on top of Sodium costs the whole game.</p>
 */
public final class VanillaRendererMixinPlugin implements IMixinConfigPlugin {

    private static final String SODIUM_MODID = "sodium";

    /** Resolved once — mod presence is fixed for the JVM lifetime. */
    private final boolean sodiumLoaded = detectSodium();

    private static boolean detectSodium() {
        try {
            return LoadingModList.get().getModFileById(SODIUM_MODID) != null;
        } catch (Throwable t) {
            // Unreadable loader state is treated as "Sodium might be here", which stands these
            // mixins down. See the class doc: the two wrong answers are not the same size.
            return true;
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return !sodiumLoaded;
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
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                         IMixinInfo mixinInfo) {
        // no-op
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                          IMixinInfo mixinInfo) {
        // no-op
    }
}

package games.brennan.dungeontrain.mixin.client.sodium;

import games.brennan.dungeontrain.client.portal.SodiumPrewarmSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Lets DT ask a Sodium {@code RenderSection} whether it has a build waiting — the duck interface
 * half of the Sodium prewarm; {@code RenderSectionManagerPrewarmMixin} is the other.
 *
 * <p>The one shadow is primitive-typed, so no Sodium type appears in DT's signatures and Sodium
 * stays off the compile classpath, as with every mixin in this package. Deliberately <em>not</em>
 * gated on {@code isBuilt()}: the room's sections are usually built-empty with a rebuild pending —
 * see the interface — and the first run that checked {@code isBuilt()} queued nothing at all.</p>
 *
 * <p>A shadow that no longer exists is a <b>fatal</b> apply error, not a quiet no-op — so
 * {@code SodiumMixinPlugin} applies this only on the Sodium line it was written against.</p>
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSection", remap = false)
public abstract class RenderSectionPrewarmMixin implements SodiumPrewarmSection {

    @Shadow(remap = false)
    public abstract int getPendingUpdate();

    @Override
    public boolean dungeontrain$wantsBuild() {
        return getPendingUpdate() != 0;
    }
}

package games.brennan.dungeontrain.client.menu;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A {@link VertexConsumer} that caps the alpha of everything written through it, so an opaque block
 * model comes out as a ghost. Shared by the door ghosts ({@link EditorDoorGhostRenderer}) and the
 * prefab ghosts ({@link EditorPrefabGhostRenderer}).
 *
 * <p>{@code min} rather than an outright overwrite so a model that is already more transparent than
 * the cap stays that way. Every override returns {@code this} rather than the delegate — the vertex
 * builders chain these calls, and handing back the raw delegate would let the rest of the chain
 * write past the cap.</p>
 *
 * @param delegate the real buffer, a {@code RenderType.translucent()} one — an opaque type would
 *                 ignore the alpha
 * @param alpha    the ceiling, 0..255
 */
@OnlyIn(Dist.CLIENT)
public record GhostBuffer(VertexConsumer delegate, int alpha) implements VertexConsumer {

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        delegate.addVertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int a) {
        delegate.setColor(red, green, blue, Math.min(a, alpha));
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        delegate.setUv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        delegate.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        delegate.setUv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float normalX, float normalY, float normalZ) {
        delegate.setNormal(normalX, normalY, normalZ);
        return this;
    }
}

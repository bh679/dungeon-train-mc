package games.brennan.dungeontrain.client.snapshot;

import net.minecraft.resources.ResourceLocation;

/**
 * One captured third-person ride photo, held in memory for the death screen.
 * The {@code texture} is a registered {@link net.minecraft.client.renderer.texture.DynamicTexture}
 * owned by {@link RideSnapshotGallery} (released when evicted or on world leave).
 */
public record RideSnapshot(ResourceLocation texture, SnapshotTag tag, int width, int height, long createdTick) {

    /** Width / height; falls back to 16:9 if height is degenerate. */
    public float aspect() {
        return height <= 0 ? 16.0f / 9.0f : (float) width / (float) height;
    }
}

package games.brennan.dungeontrain.client.builder;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * Client-side cache of the Train Builder build volumes, as sent by
 * {@code BuilderBoundsPacket}.
 *
 * <p>Same shape as {@code EditorStatusHudOverlay}: a volatile snapshot written by the network
 * thread and read by the renderer, replaced wholesale rather than mutated so a render pass can
 * never see a half-updated list. Cleared on logout so one world's bounds can't wash blocks in
 * the next.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderBoundsState {

    private static volatile List<BoundingBox> volumes = List.of();

    private BuilderBoundsState() {}

    public static void set(List<BoundingBox> newVolumes) {
        volumes = List.copyOf(newVolumes);
    }

    public static List<BoundingBox> volumes() {
        return volumes;
    }

    public static void clear() {
        volumes = List.of();
    }
}

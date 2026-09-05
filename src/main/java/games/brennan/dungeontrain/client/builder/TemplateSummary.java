package games.brennan.dungeontrain.client.builder;

import net.minecraft.core.Vec3i;

/**
 * The numbers a template's data sheet shows, counted once when its tile is baked.
 *
 * @param blocks        non-air blocks in the template
 * @param declaredSize  the size the file declares, which may be larger than the blocks occupy
 * @param blockEntities blocks carrying NBT (signs, spawners, containers, ...)
 * @param containers    the subset of those holding an item list
 * @param entities      entities saved with the template
 */
public record TemplateSummary(int blocks, Vec3i declaredSize, int blockEntities, int containers,
                              int entities) {

    /** The empty sheet — a template that could not be read. */
    public static final TemplateSummary NONE = new TemplateSummary(0, Vec3i.ZERO, 0, 0, 0);

    public boolean isEmpty() {
        return blocks == 0;
    }
}

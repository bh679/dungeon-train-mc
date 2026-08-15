package games.brennan.dungeontrain.portal;

import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;

/**
 * The one room copy a player is standing in, and the corridor volumes standing inside it — the
 * answer to "what space is this player actually in", as opposed to the whole tiled structure.
 *
 * <p>Used to frame the {@code THRESHOLD} ride photo. An endless room is copies of one room tiled
 * edge to edge with the seams carved open, so from inside any copy the neighbours are a few blocks
 * away through a doorway. A camera placed behind the player can therefore end up standing in the
 * <em>next copy</em> — or inside one of the twin corridors, whose whole job is to be an illusion
 * seen from one side. Either way the photo is of the machinery rather than of the room.</p>
 *
 * <p>So the camera is confined to {@link #body} and kept out of {@link #corridors}. Both are
 * computed on the server, where the tiling and the corridor masks live, and sent to the client with
 * the capture cue — the client has no way to derive either from blocks alone.</p>
 *
 * @param body      the room copy's own volume, in world block coordinates (inclusive)
 * @param corridors the corridor volumes that intersect {@code body}; usually empty, and only
 *                  non-empty for tiles on the row the twin corridors stand in
 */
public record DimensionalCarriageCell(BoundingBox body, List<BoundingBox> corridors) {

    public DimensionalCarriageCell {
        corridors = List.copyOf(corridors);
    }
}

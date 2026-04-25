package games.brennan.dungeontrain.track.variant;

import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.TrackTemplate;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.tunnel.TunnelTemplate;
import net.minecraft.core.Vec3i;

import java.util.Locale;

/**
 * Every track-side template kind that supports named variants — the open-air
 * track tile, both tunnel kinds (section + portal), the three pillar column
 * sections, and the stairs adjunct. Each kind has a fixed footprint (some
 * width-dependent on {@link CarriageDims}) and a stable on-disk subdirectory
 * under {@code config/dungeontrain/} where its named templates live as
 * {@code <name>.nbt} (+ optional {@code <name>.variants.json} sidecar).
 *
 * <p>Mirrors {@link games.brennan.dungeontrain.train.CarriagePartKind} for
 * carriage parts; here the kinds are heterogeneous (different physical
 * structures) so the only shared method is {@link #dims} and the subdir
 * helpers — placement logic stays in the per-kind generators (
 * {@link games.brennan.dungeontrain.track.TrackGenerator},
 * {@link games.brennan.dungeontrain.tunnel.TunnelTemplate}).</p>
 *
 * <p>Disk layout (under {@code config/dungeontrain/}):
 * <ul>
 *   <li>{@link #TILE} — {@code tracks/<name>.nbt}</li>
 *   <li>{@link #TUNNEL_SECTION} — {@code tunnels/section/<name>.nbt}</li>
 *   <li>{@link #TUNNEL_PORTAL} — {@code tunnels/portal/<name>.nbt}</li>
 *   <li>{@link #PILLAR_TOP} / {@link #PILLAR_MIDDLE} / {@link #PILLAR_BOTTOM} —
 *       {@code pillars/<section>/<name>.nbt}</li>
 *   <li>{@link #ADJUNCT_STAIRS} — {@code pillars/adjunct_stairs/<name>.nbt}</li>
 * </ul>
 *
 * <p>The seed used to deterministically pick a name for a given tile derives
 * from {@link #id()} (a stable string), not the enum ordinal — this means
 * adding a new kind in the middle of the enum doesn't re-roll variants on
 * existing worlds.</p>
 */
public enum TrackKind {
    TILE("tile", "tracks"),
    TUNNEL_SECTION("tunnel_section", "tunnels/section"),
    TUNNEL_PORTAL("tunnel_portal", "tunnels/portal"),
    PILLAR_TOP("pillar_top", "pillars/top"),
    PILLAR_MIDDLE("pillar_middle", "pillars/middle"),
    PILLAR_BOTTOM("pillar_bottom", "pillars/bottom"),
    ADJUNCT_STAIRS("adjunct_stairs", "pillars/adjunct_stairs");

    /** The default ("built-in") variant name present even when the disk is empty. */
    public static final String DEFAULT_NAME = "default";

    /** Filename extension for NBT templates. */
    public static final String NBT_EXT = ".nbt";

    /** Filename suffix for the per-block variant sidecar. */
    public static final String VARIANTS_EXT = ".variants.json";

    /** Filename for the per-kind weights file living next to its templates. */
    public static final String WEIGHTS_FILE = "weights.json";

    /** Lowercase id, e.g. {@code "tile"}, used for seeding and command tokens. */
    private final String id;

    /**
     * Subdirectory under {@code config/dungeontrain/} (and the bundled
     * classpath under {@code /data/dungeontrain/}). May contain a slash to
     * nest under a kind-grouping folder (e.g. {@code "tunnels/section"}).
     */
    private final String subdir;

    TrackKind(String id, String subdir) {
        this.id = id;
        this.subdir = subdir;
    }

    public String id() {
        return id;
    }

    public String subdir() {
        return subdir;
    }

    /** {@code config/dungeontrain/<subdir>/} relative slug. */
    public String configSubdir() {
        return "dungeontrain/" + subdir;
    }

    /** {@code /data/dungeontrain/<subdir>/} classpath prefix (with trailing slash). */
    public String bundledResourcePrefix() {
        return "/data/dungeontrain/" + subdir + "/";
    }

    /** Source-tree write path used by dev-mode promote (relative to project root). */
    public String sourceRelativePath() {
        return "src/main/resources/data/dungeontrain/" + subdir;
    }

    /**
     * Footprint of one stamped instance of this kind. Tunnels and the stairs
     * adjunct have fixed dimensions; track tile and pillar sections scale on Z
     * with the world's {@link CarriageDims#width()}.
     */
    public Vec3i dims(CarriageDims worldDims) {
        return switch (this) {
            case TILE -> new Vec3i(TrackTemplate.TILE_LENGTH, TrackTemplate.HEIGHT, worldDims.width());
            case TUNNEL_SECTION, TUNNEL_PORTAL ->
                new Vec3i(TunnelTemplate.LENGTH, TunnelTemplate.HEIGHT, TunnelTemplate.WIDTH);
            case PILLAR_TOP -> new Vec3i(1, PillarSection.TOP.height(), worldDims.width());
            case PILLAR_MIDDLE -> new Vec3i(1, PillarSection.MIDDLE.height(), worldDims.width());
            case PILLAR_BOTTOM -> new Vec3i(1, PillarSection.BOTTOM.height(), worldDims.width());
            case ADJUNCT_STAIRS ->
                new Vec3i(PillarAdjunct.STAIRS.xSize(), PillarAdjunct.STAIRS.ySize(), PillarAdjunct.STAIRS.zSize());
        };
    }

    /** Parse a lowercase id back to a kind, or {@code null} if unrecognised. */
    public static TrackKind fromId(String raw) {
        if (raw == null) return null;
        String key = raw.toLowerCase(Locale.ROOT);
        for (TrackKind k : values()) {
            if (k.id.equals(key)) return k;
        }
        return null;
    }
}

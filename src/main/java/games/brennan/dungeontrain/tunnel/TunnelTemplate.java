package games.brennan.dungeontrain.tunnel;

import games.brennan.dungeontrain.editor.TunnelTemplateStore;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.Optional;

/**
 * Tunnel blueprint — a 10×14×13 (X×Y×Z) box covering one lamp-spaced section
 * of the auto-generated stone-brick tunnel. Two variants: {@link TunnelVariant#SECTION}
 * (uninterrupted tunnel interior, stamped end-to-end along a run) and
 * {@link TunnelVariant#PORTAL} (same footprint, with a pyramid facade at
 * {@code x = 0}; mirrored on +X for exit portals).
 *
 * <p>{@link #placeSectionAt(ServerLevel, BlockPos)} and
 * {@link #placePortalAt(ServerLevel, BlockPos, boolean)} first try the
 * NBT-backed template from {@link TunnelTemplateStore}; on miss they fall
 * back to {@link LegacyTunnelPaint#paintSection} / {@link LegacyTunnelPaint#paintPortal}
 * so the pre-refactor geometry still shows up when the player hasn't saved a
 * custom template.</p>
 */
public final class TunnelTemplate {

    public enum TunnelVariant {
        SECTION,
        PORTAL
    }

    /** X extent — aligns with {@code LAMP_SPACING} so stamps are lamp-station-aligned. */
    public static final int LENGTH = 10;

    /**
     * Y extent — from tunnel {@code floorY = bedY} up to the arched apex at
     * {@code apexY = ceilingY + ARCH_TIERS + 1 = bedY + 13}, inclusive → 14 rows.
     */
    public static final int HEIGHT = 14;

    /** Z extent — {@code wallMaxZ - wallMinZ + 1 = 13}. */
    public static final int WIDTH = 13;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private TunnelTemplate() {}

    /**
     * Place a tunnel section at {@code origin} (= min corner, i.e.
     * {@code (worldX, floorY, wallMinZ)}). Uses saved template if present,
     * else delegates to the procedural fallback.
     */
    public static void placeSectionAt(ServerLevel level, BlockPos origin) {
        Optional<StructureTemplate> stored = TunnelTemplateStore.get(level, TunnelVariant.SECTION);
        if (stored.isPresent()) {
            stampTemplate(level, origin, stored.get(), false);
            return;
        }
        TunnelGeometry tg = LegacyTunnelPaint.geometryForPlot(origin);
        LegacyTunnelPaint.paintSection(level, origin.getX(), tg);
    }

    /**
     * Place a tunnel portal at {@code origin}. When {@code mirrorX == false}
     * (entrance), the pyramid facade sits at {@code origin.x}. When
     * {@code mirrorX == true} (exit), the template is mirrored along X via
     * {@link Mirror#FRONT_BACK} so the pyramid facade lands at
     * {@code origin.x + LENGTH - 1}.
     *
     * <p>Because {@code Mirror.FRONT_BACK} negates local-x, the mirrored
     * template's blocks land at {@code world.x = origin.x - local.x}. To keep
     * the footprint at {@code [origin.x .. origin.x + LENGTH - 1]} we call
     * {@link StructureTemplate#placeInWorld} with an origin shifted by
     * {@code LENGTH - 1} on X.</p>
     */
    public static void placePortalAt(ServerLevel level, BlockPos origin, boolean mirrorX) {
        Optional<StructureTemplate> stored = TunnelTemplateStore.get(level, TunnelVariant.PORTAL);
        if (stored.isPresent()) {
            BlockPos stampOrigin = mirrorX ? origin.offset(LENGTH - 1, 0, 0) : origin;
            stampTemplate(level, stampOrigin, stored.get(), mirrorX);
            return;
        }
        TunnelGeometry tg = LegacyTunnelPaint.geometryForPlot(origin);
        LegacyTunnelPaint.paintPortal(level, origin.getX(), tg, mirrorX);
    }

    /** Zero-out the 10×14×13 footprint at {@code origin}. Used by the editor. */
    public static void eraseAt(ServerLevel level, BlockPos origin) {
        for (int dx = 0; dx < LENGTH; dx++) {
            for (int dy = 0; dy < HEIGHT; dy++) {
                for (int dz = 0; dz < WIDTH; dz++) {
                    level.setBlock(origin.offset(dx, dy, dz), AIR, 3);
                }
            }
        }
    }

    private static void stampTemplate(ServerLevel level, BlockPos origin,
                                      StructureTemplate template, boolean mirrorX) {
        StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
        if (mirrorX) settings.setMirror(Mirror.FRONT_BACK);
        template.placeInWorld(level, origin, origin, settings, level.getRandom(), 3);
    }
}

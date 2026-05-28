package games.brennan.dungeontrain.portal;

import net.minecraft.world.level.block.Block;

/**
 * Decorative perimeter block of a dimensional train portal. Has no per-tick
 * logic — its only job is to delineate the visible frame around the active
 * {@link DimensionalPortalCoreBlock}.
 *
 * <p>Strength/blast-resistance are configured by the
 * {@link games.brennan.dungeontrain.registry.ModBlocks} registration call:
 * obsidian-like base with high blast resistance so cascading damage in the
 * Nether/End can't dismantle a portal during a carriage transit. We do NOT
 * mark these as bedrock-style indestructible — creative-mode players and
 * /setblock should still be able to remove them.</p>
 *
 * <p>v1 is purely cosmetic: the frame's presence does not gate transit
 * detection. The core block stores all routing state via its
 * {@link DimensionalPortalBlockEntity}.</p>
 */
public class DimensionalPortalFrameBlock extends Block {

    public DimensionalPortalFrameBlock(Properties properties) {
        super(properties);
    }
}

package games.brennan.dungeontrain.narrative.block;
import games.brennan.dungeontrain.DtCore;

import games.brennan.dungeontrain.platform.event.DtBlockEntityTypeRegistrar;
import games.brennan.dungeontrain.registry.ModBlocks;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Mod-bus subscriber that adds {@link ModBlocks#NARRATIVE_LECTERN} to
 * {@link BlockEntityType#LECTERN}'s valid blocks. Without this, vanilla
 * {@link net.minecraft.world.level.block.entity.LecternBlockEntity}
 * instances won't bind to our custom block — placing one would result in
 * a missing BE and a render glitch.
 *
 * <p>{@link BlockEntityTypeAddBlocksEvent} is the NeoForge-blessed way to
 * extend a vanilla BE type's valid-block set without mixins or reflection.</p>
 */
public final class NarrativeLecternHooks {

    private NarrativeLecternHooks() {}

    public static void onAddBlocks(DtBlockEntityTypeRegistrar registrar) {
        registrar.addBlocks(BlockEntityType.LECTERN, ModBlocks.NARRATIVE_LECTERN.get());
    }
}

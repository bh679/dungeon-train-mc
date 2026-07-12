package games.brennan.dungeontrain.platform.event;

/**
 * Loader-neutral form of NeoForge's mod-bus {@code BlockEntityTypeAddBlocksEvent}:
 * called once during setup to widen a vanilla {@link net.minecraft.world.level.block.entity.BlockEntityType}'s
 * valid-block set. Declarative — each listener is handed a
 * {@link DtBlockEntityTypeRegistrar} sink and calls {@code addBlocks(...)} (the same
 * {@code event.modify(...)}, abstracted). Not cancellable; independent. The sole DT
 * handler ({@code NarrativeLecternHooks}) binds the narrative lectern block to
 * {@code BlockEntityType.LECTERN}.
 */
@FunctionalInterface
public interface DtBlockEntityTypeAddBlocksCallback {

    void addBlocks(DtBlockEntityTypeRegistrar registrar);
}

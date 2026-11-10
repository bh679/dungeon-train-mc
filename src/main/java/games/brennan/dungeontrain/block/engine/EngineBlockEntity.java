package games.brennan.dungeontrain.block.engine;

import games.brennan.dungeontrain.registry.ModBlockEntities;
import games.brennan.dungeontrain.train.engine.DriftEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Engine Block's coal. One slot, coal only, at most {@link DriftEngine#MAX_COAL}.
 *
 * <p>Saved as a plain count ({@value DriftEngine#TAG_COAL}) rather than an item stack, so the relay
 * blob, the retrofit and the burn all read the same number without parsing an item. Hoppers may feed
 * it but never drain it — a hopper under a stranger's engine should not be a way to retire their
 * carriage.</p>
 *
 * <p>Every change tells {@link EngineFuelTracker}, which updates the drifting build's live fuel (sent
 * to the relay with its next call) and queues the cell for upload.</p>
 */
public class EngineBlockEntity extends BlockEntity implements WorldlyContainer, MenuProvider {

    private static final int[] SLOTS = {0};

    /**
     * The coal, as a real stack: vanilla slot code edits the stack {@link #getItem} returns in place,
     * so a stack built fresh on every call would drop those edits.
     */
    private ItemStack stack = ItemStack.EMPTY;

    private final ContainerData data = new ContainerData() {
        @Override public int get(int index) { return coal(); }
        @Override public void set(int index, int value) { stack = coalStack(value); }
        @Override public int getCount() { return 1; }
    };

    public EngineBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ENGINE_BLOCK.get(), pos, state);
    }

    public int coal() {
        return stack.is(Items.COAL) ? stack.getCount() : 0;
    }

    /** Set the coal outright — the spawn and lease paths, never a player. Clamped to 0..64. */
    public void setCoal(int value) {
        if (DriftEngine.clampCoal(value) == coal()) return;
        stack = coalStack(value);
        setChanged();
    }

    private static ItemStack coalStack(int count) {
        int clamped = DriftEngine.clampCoal(count);
        return clamped > 0 ? new ItemStack(Items.COAL, clamped) : ItemStack.EMPTY;
    }

    // ---- Container ----

    @Override public int getContainerSize() { return 1; }

    @Override public boolean isEmpty() { return stack.isEmpty(); }

    @Override
    public ItemStack getItem(int slot) {
        return slot == 0 ? stack : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (slot != 0 || stack.isEmpty() || amount <= 0) return ItemStack.EMPTY;
        ItemStack taken = stack.split(amount);
        setChanged();
        return taken;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (slot != 0) return ItemStack.EMPTY;
        ItemStack out = stack;
        stack = ItemStack.EMPTY;
        return out;
    }

    @Override
    public void setItem(int slot, ItemStack newStack) {
        if (slot != 0) return;
        stack = newStack.is(Items.COAL) ? newStack : ItemStack.EMPTY;
        if (stack.getCount() > DriftEngine.MAX_COAL) stack.setCount(DriftEngine.MAX_COAL);
        setChanged();
    }

    @Override public int getMaxStackSize() { return DriftEngine.MAX_COAL; }

    @Override
    public boolean canPlaceItem(int slot, ItemStack candidate) {
        return slot == 0 && candidate.is(Items.COAL);
    }

    @Override
    public boolean stillValid(Player player) {
        return net.minecraft.world.Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        stack = ItemStack.EMPTY;
        setChanged();
    }

    // ---- WorldlyContainer: hoppers feed, never drain ----

    @Override public int[] getSlotsForFace(Direction side) { return SLOTS; }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack candidate, Direction side) {
        return canPlaceItem(slot, candidate);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack candidate, Direction side) {
        return false;
    }

    // ---- menu ----

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.dungeontrain.engine_block");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new EngineMenu(id, inventory, this, data);
    }

    // ---- change propagation ----

    @Override
    public void setChanged() {
        super.setChanged();
        if (level == null || level.isClientSide) return;
        BlockState state = getBlockState();
        int coal = coal();
        boolean lit = coal > 0;
        if (state.hasProperty(EngineBlock.LIT) && state.getValue(EngineBlock.LIT) != lit) {
            level.setBlock(worldPosition, state.setValue(EngineBlock.LIT, lit), Block.UPDATE_ALL);
        }
        if (level instanceof ServerLevel server) EngineFuelTracker.onEngineChanged(server, worldPosition, coal);
    }

    // ---- NBT ----

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt(DriftEngine.TAG_COAL, coal());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        stack = coalStack(tag.getInt(DriftEngine.TAG_COAL));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}

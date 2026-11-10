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
 * The Drifting Engine Block's coal, in two parts.
 *
 * <ul>
 *   <li><b>Banked</b>: what the engine arrived with — the three it spawned with, and whatever earlier
 *       worlds put in it, less one burnt for each world it has joined. Nobody can take it out; the
 *       screen's gauge shows it.</li>
 *   <li><b>Input</b>: the one slot, coal put in during this world. It counts as fuel straight away and
 *       can be taken back out until the carriage leaves; when it joins its next world it all becomes
 *       banked and the slot starts empty again.</li>
 * </ul>
 *
 * <p>Together they never pass {@link DriftEngine#MAX_COAL}. Saved as the total
 * ({@value DriftEngine#TAG_COAL} — what the relay blob, the retrofit and the burn read) plus the input
 * count ({@value DriftEngine#TAG_INPUT}), so a reload inside this world keeps the two apart. Hoppers
 * may feed the input but never drain it.</p>
 *
 * <p>Every change tells {@link EngineFuelTracker}, which updates the drifting build's live fuel (sent
 * to the relay with its next call) and queues the cell for upload.</p>
 */
public class EngineBlockEntity extends BlockEntity implements WorldlyContainer, MenuProvider {

    private static final int[] SLOTS = {0};

    private int banked;

    /**
     * The input, as a real stack: vanilla slot code edits the stack {@link #getItem} returns in place,
     * so a stack built fresh on every call would drop those edits.
     */
    private ItemStack input = ItemStack.EMPTY;

    /** Synced to the screen: index 0 is the banked coal; the input arrives through the slot itself. */
    private final ContainerData data = new ContainerData() {
        @Override public int get(int index) { return banked; }
        @Override public void set(int index, int value) { banked = DriftEngine.clampCoal(value); }
        @Override public int getCount() { return 1; }
    };

    public EngineBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ENGINE_BLOCK.get(), pos, state);
    }

    /** All the fuel: banked plus input. */
    public int coal() {
        return banked + inputCount();
    }

    public int banked() {
        return banked;
    }

    private int inputCount() {
        return input.is(Items.COAL) ? input.getCount() : 0;
    }

    /** Room left in the input: whatever the banked coal leaves of {@link DriftEngine#MAX_COAL}. */
    public int inputCapacity() {
        return Math.max(0, DriftEngine.MAX_COAL - banked);
    }

    /**
     * Set the fuel outright, all of it banked and the input empty — the spawn and lease paths, never a
     * player. Clamped to 0..64.
     */
    public void setCoal(int value) {
        int clamped = DriftEngine.clampCoal(value);
        if (clamped == banked && input.isEmpty()) return;
        banked = clamped;
        input = ItemStack.EMPTY;
        setChanged();
    }

    // ---- Container: the input slot ----

    @Override public int getContainerSize() { return 1; }

    @Override public boolean isEmpty() { return input.isEmpty(); }

    @Override
    public ItemStack getItem(int slot) {
        return slot == 0 ? input : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (slot != 0 || input.isEmpty() || amount <= 0) return ItemStack.EMPTY;
        ItemStack taken = input.split(amount);
        setChanged();
        return taken;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (slot != 0) return ItemStack.EMPTY;
        ItemStack out = input;
        input = ItemStack.EMPTY;
        return out;
    }

    @Override
    public void setItem(int slot, ItemStack newStack) {
        if (slot != 0) return;
        input = newStack.is(Items.COAL) ? newStack : ItemStack.EMPTY;
        if (input.getCount() > inputCapacity()) input.setCount(inputCapacity());
        setChanged();
    }

    @Override public int getMaxStackSize() { return inputCapacity(); }

    @Override
    public boolean canPlaceItem(int slot, ItemStack candidate) {
        return slot == 0 && candidate.is(Items.COAL) && inputCount() < inputCapacity();
    }

    @Override
    public boolean stillValid(Player player) {
        return net.minecraft.world.Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        input = ItemStack.EMPTY;
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
        return Component.translatable("block.dungeontrain.drifting_engine_block");
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
        if (inputCount() > 0) tag.putInt(DriftEngine.TAG_INPUT, inputCount());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        int total = DriftEngine.clampCoal(tag.getInt(DriftEngine.TAG_COAL));
        int in = Math.max(0, Math.min(total, tag.getInt(DriftEngine.TAG_INPUT)));
        banked = total - in;
        input = in > 0 ? new ItemStack(Items.COAL, in) : ItemStack.EMPTY;
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

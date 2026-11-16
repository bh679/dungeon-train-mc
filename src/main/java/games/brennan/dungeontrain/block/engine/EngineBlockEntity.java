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
 *   <li><b>Input</b>: the one slot, coal or charcoal put in during this world (one or the other, as
 *       a slot holds one item). It counts as fuel straight away and
 *       can be taken back out until the carriage leaves; when it joins its next world it all becomes
 *       banked and the slot starts empty again.</li>
 * </ul>
 *
 * <p>The input only fills up to {@link DriftEngine#MAX_COAL} with the banked coal; banked coal from
 * before that cap came down may sit above it, and burns away as usual. Saved as the total
 * ({@value DriftEngine#TAG_COAL} — what the relay blob, the retrofit and the burn read) plus the input
 * count ({@value DriftEngine#TAG_INPUT}), so a reload inside this world keeps the two apart. Hoppers
 * may feed the input but never drain it.</p>
 *
 * <p>Every change tells {@link EngineFuelTracker}, which updates the drifting build's live fuel (sent
 * to the relay with its next call) and queues the cell for upload.</p>
 */
public class EngineBlockEntity extends BlockEntity implements WorldlyContainer, MenuProvider {

    private static final int[] SLOTS = {0};
    /** Set when the input holds charcoal rather than coal, so a reload gives back the same item. */
    private static final String TAG_INPUT_CHARCOAL = "InputCharcoal";

    private int banked;

    /**
     * The input, as a real stack: vanilla slot code edits the stack {@link #getItem} returns in place,
     * so a stack built fresh on every call would drop those edits.
     */
    private ItemStack input = ItemStack.EMPTY;

    /**
     * Synced to the screen: index 0 is the banked coal, index 1 the whole carriage's coal (every engine in
     * it, or just this one outside a drifting build). The input arrives through the slot itself.
     */
    private final ContainerData data = new ContainerData() {
        @Override public int get(int index) { return index == 0 ? banked : carriageCoal(); }
        @Override public void set(int index, int value) { if (index == 0) banked = DriftEngine.clampCoal(value); }
        @Override public int getCount() { return 2; }
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

    /** Coal in every engine of the drifting build this one stands in, as its tracker last summed it; this one's own outside one. */
    private int carriageCoal() {
        if (level instanceof ServerLevel server) {
            games.brennan.dungeontrain.train.SharedBuild build = EngineFuelTracker.buildAt(server, worldPosition);
            if (build != null && build.fuel() >= 0) return build.fuel();
        }
        return coal();
    }

    /** The drifting carriage or room this engine stands in, if it is live; null otherwise. Server side. */
    private games.brennan.dungeontrain.train.SharedBuild liveBuild() {
        if (!(level instanceof ServerLevel server)) return null;
        games.brennan.dungeontrain.train.SharedBuild build = EngineFuelTracker.buildAt(server, worldPosition);
        return build == null || build.isCulled() ? null : build;
    }

    /** The open-screen data for {@link EngineMenu#fromNetwork}: the carriage this engine is in, and its name id. */
    public void writeMenuExtra(net.minecraft.network.RegistryFriendlyByteBuf buf) {
        games.brennan.dungeontrain.train.SharedBuild build = liveBuild();
        EngineMenu.writeExtra(buf, build != null, build == null ? "" : build.nameId());
    }

    private int inputCount() {
        return isFuel(input) ? input.getCount() : 0;
    }

    /** What the input takes: coal or charcoal, nothing else. */
    public static boolean isFuel(ItemStack stack) {
        return stack.is(Items.COAL) || stack.is(Items.CHARCOAL);
    }

    /** Room left in the input: whatever the banked coal leaves of {@link DriftEngine#MAX_COAL}, never below none. */
    public int inputCapacity() {
        return Math.max(0, DriftEngine.MAX_COAL - banked);
    }

    /**
     * Set the fuel outright, all of it banked and the input empty — the spawn and lease paths, never a
     * player. Clamped to 0..64.
     */
    public void setCoal(int value) {
        int clamped = DriftEngine.clampCoal(value);
        if (clamped == banked && input.isEmpty()) {
            syncFire(); // same coal, but the block may have been set down showing the wrong fire
            return;
        }
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
        input = isFuel(newStack) ? newStack : ItemStack.EMPTY;
        if (input.getCount() > inputCapacity()) input.setCount(inputCapacity());
        setChanged();
    }

    @Override public int getMaxStackSize() { return inputCapacity(); }

    @Override
    public boolean canPlaceItem(int slot, ItemStack candidate) {
        return slot == 0 && isFuel(candidate) && inputCount() < inputCapacity();
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
        games.brennan.dungeontrain.train.SharedBuild build = liveBuild();
        return new EngineMenu(id, inventory, this, data, build != null, build == null ? "" : build.nameId());
    }

    // ---- change propagation ----

    @Override
    public void setChanged() {
        super.setChanged();
        if (level == null || level.isClientSide) return;
        int coal = coal();
        syncFire();
        if (level instanceof ServerLevel server) EngineFuelTracker.onEngineChanged(server, worldPosition, coal);
    }

    /** The block shows this engine's fire: lit while it has coal, embers ({@code low}) on the last one. Server side. */
    private void syncFire() {
        if (level == null || level.isClientSide) return;
        BlockState state = getBlockState();
        int coal = coal();
        boolean lit = coal > 0;
        boolean low = coal == 1;
        if (state.hasProperty(EngineBlock.LIT) && state.hasProperty(EngineBlock.LOW)
                && (state.getValue(EngineBlock.LIT) != lit || state.getValue(EngineBlock.LOW) != low)) {
            level.setBlock(worldPosition, state.setValue(EngineBlock.LIT, lit).setValue(EngineBlock.LOW, low), Block.UPDATE_ALL);
        }
    }

    // ---- NBT ----

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt(DriftEngine.TAG_COAL, coal());
        if (inputCount() > 0) tag.putInt(DriftEngine.TAG_INPUT, inputCount());
        if (input.is(Items.CHARCOAL)) tag.putBoolean(TAG_INPUT_CHARCOAL, true);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        int total = DriftEngine.clampCoal(tag.getInt(DriftEngine.TAG_COAL));
        int in = Math.max(0, Math.min(total, tag.getInt(DriftEngine.TAG_INPUT)));
        banked = total - in;
        input = in > 0 ? new ItemStack(tag.getBoolean(TAG_INPUT_CHARCOAL) ? Items.CHARCOAL : Items.COAL, in) : ItemStack.EMPTY;
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

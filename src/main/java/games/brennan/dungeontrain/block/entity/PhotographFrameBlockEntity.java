package games.brennan.dungeontrain.block.entity;

import games.brennan.dungeontrain.compat.photo.PhotoFrameLayout;
import games.brennan.dungeontrain.registry.ModBlockEntities;
import io.github.mortuusars.exposure.Exposure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The master cell of a block photo frame: what Exposure's {@code PhotographFrameEntity} kept in its
 * synced data — the frame item it was hung from, the photo in it, the photo's quarter-turns, whether
 * glow ink was used, and the frame's size. NBT keys match the entity's so the two read alike.
 *
 * <p>Synced to clients through the vanilla block-entity update packet; every setter marks the block
 * dirty and re-sends.</p>
 */
public class PhotographFrameBlockEntity extends BlockEntity {

    private static final String TAG_FRAME_ITEM = "FrameItem";
    private static final String TAG_ITEM = "Item";
    private static final String TAG_ITEM_ROTATION = "ItemRotation";
    private static final String TAG_GLOWING = "IsGlowing";
    private static final String TAG_SIZE = "Size";

    private ItemStack frameItem = ItemStack.EMPTY;
    private ItemStack photo = ItemStack.EMPTY;
    private int itemRotation;
    private boolean glowing;
    private int size;

    /**
     * Set by a creative-mode break just before the group is torn down, so the master's drops are
     * skipped the way Exposure skips them for a creative player. Never saved.
     */
    private boolean suppressDrops;

    public PhotographFrameBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PHOTOGRAPH_FRAME.get(), pos, state);
    }

    public ItemStack frameItem() {
        return frameItem.isEmpty() ? new ItemStack(Exposure.Items.PHOTOGRAPH_FRAME.get()) : frameItem;
    }

    public ItemStack photo() {
        return photo;
    }

    public boolean hasPhoto() {
        return !photo.isEmpty();
    }

    public int itemRotation() {
        return itemRotation;
    }

    public boolean glowing() {
        return glowing;
    }

    public int size() {
        return size;
    }

    public boolean suppressDrops() {
        return suppressDrops;
    }

    /** Fill a freshly placed frame. */
    public void init(ItemStack frameItem, int size) {
        this.frameItem = frameItem.copyWithCount(1);
        this.size = PhotoFrameLayout.clampSize(size);
        changed();
    }

    public void setPhoto(ItemStack photo) {
        this.photo = photo.copyWithCount(1);
        this.itemRotation = 0;
        changed();
    }

    /** Take the photo out, returning it. */
    public ItemStack removePhoto() {
        ItemStack out = photo;
        this.photo = ItemStack.EMPTY;
        this.itemRotation = 0;
        changed();
        return out;
    }

    public void rotatePhoto() {
        this.itemRotation = (itemRotation + 1) % 4;
        changed();
    }

    public void setGlowing(boolean glowing) {
        this.glowing = glowing;
        changed();
    }

    public void markNoDrops() {
        this.suppressDrops = true;
    }

    private void changed() {
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!frameItem.isEmpty()) tag.put(TAG_FRAME_ITEM, frameItem.save(registries));
        if (!photo.isEmpty()) tag.put(TAG_ITEM, photo.save(registries));
        tag.putByte(TAG_ITEM_ROTATION, (byte) itemRotation);
        tag.putBoolean(TAG_GLOWING, glowing);
        tag.putByte(TAG_SIZE, (byte) size);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        frameItem = ItemStack.parseOptional(registries, tag.getCompound(TAG_FRAME_ITEM));
        photo = ItemStack.parseOptional(registries, tag.getCompound(TAG_ITEM));
        itemRotation = Math.floorMod(tag.getByte(TAG_ITEM_ROTATION), 4);
        glowing = tag.getBoolean(TAG_GLOWING);
        size = PhotoFrameLayout.clampSize(tag.getByte(TAG_SIZE));
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

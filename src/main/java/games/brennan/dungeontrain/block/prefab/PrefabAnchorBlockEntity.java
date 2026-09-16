package games.brennan.dungeontrain.block.prefab;

import games.brennan.dungeontrain.editor.PrefabAnchorIndex;
import games.brennan.dungeontrain.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The one thing a {@link PrefabAnchorBlock} remembers: <b>which</b> prefab stands here.
 *
 * <p>Saved into the parent template's NBT with the block, carried on the block item as
 * {@code BLOCK_ENTITY_DATA} (so a bound anchor survives the Variant Clipboard and a block-variant
 * pool), and synced to the client so the author can read the binding off the block. An empty
 * name is an unbound anchor — the resolver treats it as air.</p>
 */
public final class PrefabAnchorBlockEntity extends BlockEntity {

    /** NBT key for the bound prefab's name. */
    public static final String TAG_PREFAB = "prefab";

    private String prefab = "";

    public PrefabAnchorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PREFAB_ANCHOR.get(), pos, state);
    }

    /** The bound prefab name, or {@code ""} when unbound. */
    public String prefab() {
        return prefab;
    }

    /** Bind to {@code name} (null clears) and push the change to disk + clients. */
    public void setPrefab(String name) {
        this.prefab = name == null ? "" : name;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            PrefabAnchorIndex.bump();
        }
    }

    // The editor's ghost overlay needs to know where every anchor stands without walking the plots —
    // see PrefabAnchorIndex. Server side only: the client draws what it is sent.
    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide()) PrefabAnchorIndex.add(level.dimension(), worldPosition);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && !level.isClientSide()) PrefabAnchorIndex.remove(level.dimension(), worldPosition);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString(TAG_PREFAB, prefab);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.prefab = tag.getString(TAG_PREFAB);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** The prefab name held in a block-entity tag, as the resolver reads it off a stamped template. */
    public static String prefabIn(CompoundTag tag) {
        return tag == null ? "" : tag.getString(TAG_PREFAB);
    }
}

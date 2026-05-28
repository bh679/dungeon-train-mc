package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Persistent state of a {@link DimensionalPortalCoreBlock}.
 *
 * <p>Holds the partner portal's world coordinates and target dimension. Both
 * are {@code null} until {@link #setPartner} is called — newly placed cores
 * are unpaired until {@code PortalRegistry} (Phase 2) links them to a partner
 * in the destination dimension. Carriage and player transit logic must
 * tolerate the unpaired state and skip transfer when partner data is absent.</p>
 *
 * <p>Serialization is symmetric: NBT round-trip preserves both the partner
 * coords (stored as an int triple) and the partner dimension key (stored as
 * its {@link ResourceLocation} string). On load, an unknown dimension key
 * leaves {@link #partnerDim} {@code null} — the portal will simply behave
 * as unpaired until the registry repairs it.</p>
 */
public class DimensionalPortalBlockEntity extends BlockEntity {

    private static final String TAG_PARTNER_POS = "PartnerPos";
    private static final String TAG_PARTNER_DIM = "PartnerDim";

    @Nullable private BlockPos partnerPos;
    @Nullable private ResourceKey<Level> partnerDim;

    public DimensionalPortalBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DIMENSIONAL_PORTAL.get(), pos, state);
    }

    @Nullable
    public BlockPos getPartnerPos() {
        return partnerPos;
    }

    @Nullable
    public ResourceKey<Level> getPartnerDim() {
        return partnerDim;
    }

    /**
     * Returns true once both partner coords and partner dimension are set.
     * Transit code should gate on this before reading either field.
     */
    public boolean isPaired() {
        return partnerPos != null && partnerDim != null;
    }

    /**
     * Records the partner portal. Marks the BE dirty so the pairing is
     * persisted at the next save.
     */
    public void setPartner(BlockPos pos, ResourceKey<Level> dim) {
        this.partnerPos = pos;
        this.partnerDim = dim;
        setChanged();
    }

    /** Clears the pairing — used if the partner is destroyed or the registry de-pairs. */
    public void clearPartner() {
        this.partnerPos = null;
        this.partnerDim = null;
        setChanged();
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains(TAG_PARTNER_POS)) {
            int[] arr = tag.getIntArray(TAG_PARTNER_POS);
            if (arr.length == 3) {
                partnerPos = new BlockPos(arr[0], arr[1], arr[2]);
            }
        }
        if (tag.contains(TAG_PARTNER_DIM)) {
            ResourceLocation rl = ResourceLocation.tryParse(tag.getString(TAG_PARTNER_DIM));
            if (rl != null) {
                partnerDim = ResourceKey.create(Registries.DIMENSION, rl);
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (partnerPos != null) {
            tag.putIntArray(TAG_PARTNER_POS,
                new int[] { partnerPos.getX(), partnerPos.getY(), partnerPos.getZ() });
        }
        if (partnerDim != null) {
            tag.putString(TAG_PARTNER_DIM, partnerDim.location().toString());
        }
    }
}

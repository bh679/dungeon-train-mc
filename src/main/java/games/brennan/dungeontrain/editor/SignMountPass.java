package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The spawn half of an Auto sign row ({@link VariantSign#auto}): once the overlay is in, the sign
 * at a noted position is switched to the mount its neighbours suit ({@link SignVariants#autoMount}).
 *
 * <p>A wall / standing sign and a hanging sign are different block-entity types, so swapping the
 * block drops the old entity. Its NBT — the row's text, colour, glow and wax — is read first and
 * loaded into the new one through {@link SilentBlockOps#setBlockSilent}, the call every placer
 * stamps with.</p>
 *
 * <p>Noted by {@link ConnectPass#note(ServerLevel, BlockPos, VariantState, BlockState)}, so it
 * shares the placers' existing scopes.</p>
 */
public final class SignMountPass {

    private SignMountPass() {}

    /** Re-mount the sign at {@code pos} if its neighbours suit another mount. No-op on anything else. */
    public static void apply(ServerLevel level, BlockPos pos) {
        BlockState current = level.getBlockState(pos);
        if (!SignVariants.isSign(current)) return;
        SignVariants.Mount mount = SignVariants.autoMount(level, pos, current);
        BlockState next = SignVariants.withMount(current, mount);
        if (next == current) return;
        BlockEntity be = level.getBlockEntity(pos);
        CompoundTag nbt = be == null ? null : be.saveWithoutMetadata(level.registryAccess());
        SilentBlockOps.setBlockSilent(level, pos, next, nbt);
    }
}

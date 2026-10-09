package games.brennan.dungeontrain.block;

import com.mojang.serialization.MapCodec;
import games.brennan.dungeontrain.block.entity.LiveAntennaBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * The Live Feed antenna: a Vista broadcast source that is the live stream. Link a Vista Hollow
 * Cassette to it (right-click the antenna with the cassette) and any TV playing that cassette
 * shows whoever is streaming — see {@link LiveAntennaBlockEntity}.
 */
public class LiveAntennaBlock extends Block implements EntityBlock {

    public static final MapCodec<LiveAntennaBlock> CODEC = simpleCodec(LiveAntennaBlock::new);

    public LiveAntennaBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LiveAntennaBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return (lvl, pos, st, be) -> {
            if (be instanceof LiveAntennaBlockEntity antenna) antenna.serverTick();
        };
    }
}

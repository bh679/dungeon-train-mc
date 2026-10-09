package games.brennan.dungeontrain.block.entity;

import games.brennan.dungeontrain.registry.ModBlockEntities;
import net.mehvahdjukaar.vista.client.video_source.IVideoSource;
import net.mehvahdjukaar.vista.common.broadcast.LevelBEBroadcastLocation;
import net.mehvahdjukaar.vista.common.cassette.IBroadcastSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Vista {@link IBroadcastSource} whose picture is the Live Feed.
 *
 * <p><b>Deterministic UUID.</b> Every antenna broadcasts under the same id, derived from a constant,
 * so a Hollow Cassette authored in the viewer-carriage template is already linked when the template
 * is stamped — nothing has to be rewritten at placement, and several antennas in one world simply
 * resolve to the same source. Vista's registry maps the id to the location of whichever antenna
 * linked last; the 5 s re-link below keeps it pointing at a loaded one.</p>
 *
 * <p>The video source itself is client-only ({@code client/live/LiveFeedSource}); it reaches this
 * common class through {@link #clientSource}, set during client setup, so no client class is ever
 * loaded on a dedicated server.</p>
 */
public class LiveAntennaBlockEntity extends BlockEntity implements IBroadcastSource {

    /** One channel for now; a second antenna kind would hash a different string. */
    public static final UUID MAIN_FEED_UUID =
        UUID.nameUUIDFromBytes("dungeontrain:live/main".getBytes(StandardCharsets.UTF_8));

    private static final int RELINK_EVERY_TICKS = 100;

    /** Set by the client at setup; stays null on a dedicated server. */
    @Nullable
    private static volatile Supplier<IVideoSource> clientSource;

    private int ticks;

    public LiveAntennaBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.LIVE_ANTENNA.get(), pos, state);
    }

    public static void setClientSource(Supplier<IVideoSource> source) {
        clientSource = source;
    }

    @Override
    public UUID getBroadcastUUID() {
        return MAIN_FEED_UUID;
    }

    @Override
    public @Nullable IVideoSource getBroadcastVideo() {
        Supplier<IVideoSource> s = clientSource;
        return s == null ? null : s.get();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        relink();
    }

    /** Server tick: re-assert the link so the shared id always resolves to a loaded antenna. */
    public void serverTick() {
        if (++ticks % RELINK_EVERY_TICKS == 0) relink();
    }

    private void relink() {
        Level level = getLevel();
        if (level == null || level.isClientSide) return;
        ensureLinked(level, LevelBEBroadcastLocation.of(this));
    }

    @Override
    public void setRemoved() {
        Level level = getLevel();
        if (level != null && !level.isClientSide) {
            // Only unlink if the registry still points here; another antenna may hold the id now.
            removeLink(level);
        }
        super.setRemoved();
    }
}

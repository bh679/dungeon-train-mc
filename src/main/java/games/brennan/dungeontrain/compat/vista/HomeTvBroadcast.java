package games.brennan.dungeontrain.compat.vista;

import com.mojang.serialization.MapCodec;
import net.mehvahdjukaar.vista.common.broadcast.TriResult;
import net.mehvahdjukaar.vista.client.video_source.IVideoSource;
import net.mehvahdjukaar.vista.common.broadcast.BroadcastLocationType;
import net.mehvahdjukaar.vista.common.broadcast.IBroadcastLocation;
import net.mehvahdjukaar.vista.common.cassette.IBroadcastSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The home room's TV channel as a Vista broadcast with no block behind it: one feed id, linked in
 * every server level beside the Live Feed's ({@link LiveBroadcastLocation#link}), whose picture on the
 * client is whatever {@code HomeTvVisit} is showing this player — their own death screens — and snow
 * for anybody else.
 *
 * <p>The client plugs its video source in at setup ({@link #setClientSource}), so no client class is
 * touched on a dedicated server.</p>
 */
public final class HomeTvBroadcast implements IBroadcastSource, IBroadcastLocation {

    public static final UUID FEED_UUID =
        UUID.nameUUIDFromBytes("dungeontrain:home_tv".getBytes(StandardCharsets.UTF_8));

    public static final HomeTvBroadcast LOCATION = new HomeTvBroadcast();

    /** Saved as a unit: there is one home channel. */
    public static final BroadcastLocationType TYPE = new BroadcastLocationType(
        MapCodec.unit(LOCATION), StreamCodec.unit(LOCATION));

    @Nullable
    private volatile Supplier<IVideoSource> clientSource;

    private HomeTvBroadcast() {}

    public static void setClientSource(Supplier<IVideoSource> source) {
        LOCATION.clientSource = source;
    }

    @Override
    public UUID getBroadcastUUID() {
        return FEED_UUID;
    }

    @Override
    public @Nullable IVideoSource getBroadcastVideo() {
        Supplier<IVideoSource> s = clientSource;
        return s == null ? null : s.get();
    }

    @Override
    public TriResult<IBroadcastSource> get(boolean isClient) {
        return TriResult.valid(this);
    }

    @Override
    public BroadcastLocationType type() {
        return TYPE;
    }

    @Override
    public MutableComponent getTooltipComponent(Level level) {
        return Component.translatable("item.dungeontrain.home_cassette.tooltip");
    }

    @Override
    public @Nullable GlobalPos getChunkSendPosition() {
        return null;
    }
}

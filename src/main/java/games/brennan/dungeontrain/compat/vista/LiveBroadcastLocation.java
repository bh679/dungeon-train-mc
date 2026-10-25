package games.brennan.dungeontrain.compat.vista;

import com.mojang.serialization.MapCodec;
import games.brennan.dungeontrain.DungeonTrain;
import net.mehvahdjukaar.vista.VistaMod;
import net.mehvahdjukaar.vista.common.broadcast.BroadcastLocationType;
import net.mehvahdjukaar.vista.common.broadcast.BroadcastManager;
import net.mehvahdjukaar.vista.common.broadcast.IBroadcastLocation;
import net.mehvahdjukaar.vista.common.broadcast.TriResult;
import net.mehvahdjukaar.vista.common.cassette.IBroadcastSource;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.Nullable;

/**
 * A Vista broadcast location that is nowhere: it resolves straight to a {@link LiveBroadcastSource}.
 * One per stream ({@link #INSTANCE} the first, {@link #SECOND} the second stream of the Two Streams
 * reward), each registered in Vista's broadcast-location registry so {@code BroadcastManager} can
 * persist and sync the link like any other; {@link #link} puts both feed ids → their locations into
 * every server level, which is what lets a cassette carrying either id play on any TV with no antenna
 * block.
 */
public final class LiveBroadcastLocation implements IBroadcastLocation {

    public static final LiveBroadcastLocation INSTANCE = new LiveBroadcastLocation(LiveBroadcastSource.INSTANCE);
    public static final LiveBroadcastLocation SECOND = new LiveBroadcastLocation(LiveBroadcastSource.SECOND);

    public static final BroadcastLocationType TYPE = new BroadcastLocationType(
        MapCodec.unit(INSTANCE), StreamCodec.unit(INSTANCE));
    public static final BroadcastLocationType SECOND_TYPE = new BroadcastLocationType(
        MapCodec.unit(SECOND), StreamCodec.unit(SECOND));

    private static final DeferredRegister<BroadcastLocationType> TYPES =
        DeferredRegister.create(VistaMod.BROADCAST_LOCATION_REGISTRY.key(), DungeonTrain.MOD_ID);

    static {
        TYPES.register("live", () -> TYPE);
        TYPES.register("live_second", () -> SECOND_TYPE);
    }

    private final LiveBroadcastSource source;

    private LiveBroadcastLocation(LiveBroadcastSource source) {
        this.source = source;
    }

    /** Call from the mod constructor. */
    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
    }

    /**
     * Link the feed in {@code level}. Vista's manager is per level and Sable builds sub-levels before
     * the overworld exists, so the write is deferred to the server thread when needed — the same
     * guard {@code IBroadcastSource.ensureLinked} uses.
     */
    public static void link(ServerLevel level) {
        MinecraftServer server = level.getServer();
        Runnable task = () -> {
            BroadcastManager manager = BroadcastManager.getInstance(level);
            manager.linkFeed(LiveBroadcastSource.MAIN_FEED_UUID, INSTANCE);
            manager.linkFeed(LiveBroadcastSource.SECOND_FEED_UUID, SECOND);
        };
        if (server.overworld() == null) server.tell(new TickTask(server.getTickCount(), task));
        else task.run();
    }

    @Override
    public TriResult<IBroadcastSource> get(boolean isClient) {
        return TriResult.valid(source);
    }

    @Override
    public BroadcastLocationType type() {
        return source.isSecond() ? SECOND_TYPE : TYPE;
    }

    @Override
    public MutableComponent getTooltipComponent(Level level) {
        return Component.translatable(source.isSecond() ? "item.dungeontrain.live_cassette_2.tooltip" : "item.dungeontrain.live_cassette.tooltip");
    }

    @Override
    public @Nullable GlobalPos getChunkSendPosition() {
        return null;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LiveBroadcastLocation other && other.source == source;
    }

    @Override
    public int hashCode() {
        return source.isSecond() ? 0x11FF : 0x11FE;
    }
}

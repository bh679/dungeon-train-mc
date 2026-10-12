package games.brennan.dungeontrain.compat.vista;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.CarriageContentsStore;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.player.ServerLiveStatus;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TVs are commoner on the train while someone is live and rarer while nobody is.
 *
 * <p>Every weight that puts a Vista TV in a carriage — a variant entry that is a TV, and the weight of
 * a contents template with TVs baked into it (top level, as a group member, or as a group's own self
 * entry) — is doubled while {@link ServerLiveStatus#isAnyoneLive} and halved (rounding down, so 1
 * becomes 0) while it is not.</p>
 *
 * <p>The answer is snapshotted per carriage index the first time that carriage asks, so its contents
 * pick, its block pass and its deferred mob pass all agree even if a stream starts between them. With
 * no server running (unit tests, the client) nothing is scaled.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LiveTvWeighting {

    static final ResourceLocation TV_ID = ResourceLocation.fromNamespaceAndPath("vista", "television");
    private static final String TV_NAME = TV_ID.toString();

    /** Carriage indices whose live answer is remembered — far more than are ever built at once. */
    private static final int SNAPSHOT_CAPACITY = 512;

    private static volatile MinecraftServer server;
    private static final Map<Integer, Boolean> SNAPSHOTS = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> eldest) {
            return size() > SNAPSHOT_CAPACITY;
        }
    };
    private static final Map<String, Boolean> TV_TEMPLATES = new ConcurrentHashMap<>();

    private LiveTvWeighting() {}

    /** Doubles while live, halves (1 → 0) otherwise. */
    public static int scale(int weight, boolean live) {
        if (weight <= 0) return 0;
        return live ? weight * 2 : weight / 2;
    }

    /** Whether TV weights are being adjusted at all — false with no server (tests, client). */
    public static boolean active() {
        return server != null;
    }

    /** The live answer for one carriage, fixed the first time it is asked. */
    public static boolean liveFor(int carriageIndex) {
        MinecraftServer s = server;
        if (s == null) return false;
        synchronized (SNAPSHOTS) {
            return SNAPSHOTS.computeIfAbsent(carriageIndex, k -> ServerLiveStatus.isAnyoneLive(s));
        }
    }

    /** A variant entry's weight for this carriage — TVs scaled, everything else as authored. */
    public static int variantWeight(VariantState state, int carriageIndex) {
        if (!active() || !isTv(state)) return state.weight();
        return scale(state.weight(), liveFor(carriageIndex));
    }

    /** A contents template's weight for this carriage — scaled when TVs are baked into its template. */
    public static int contentsWeight(String contentsId, int weight, int carriageIndex) {
        if (!active() || !hasBakedTv(contentsId)) return weight;
        return scale(weight, liveFor(carriageIndex));
    }

    public static boolean isTv(VariantState state) {
        if (state == null || state.isMob() || state.isGroupRef() || state.state() == null) return false;
        return TV_ID.equals(BuiltInRegistries.BLOCK.getKey(state.state().getBlock()));
    }

    /** Whether {@code contentsId}'s own template has a TV in its palette. Cached; see {@link #clearCache}. */
    public static boolean hasBakedTv(String contentsId) {
        if (contentsId == null) return false;
        return TV_TEMPLATES.computeIfAbsent(contentsId,
            id -> CarriageContentsStore.rawTag(id).map(LiveTvWeighting::paletteHasTv).orElse(false));
    }

    static boolean paletteHasTv(CompoundTag template) {
        if (paletteHasTv(template.getList("palette", Tag.TAG_COMPOUND))) return true;
        ListTag palettes = template.getList("palettes", Tag.TAG_LIST);
        for (int i = 0; i < palettes.size(); i++) {
            if (paletteHasTv(palettes.getList(i))) return true;
        }
        return false;
    }

    private static boolean paletteHasTv(ListTag palette) {
        for (int i = 0; i < palette.size(); i++) {
            if (TV_NAME.equals(palette.getCompound(i).getString("Name"))) return true;
        }
        return false;
    }

    /** Drops the per-template TV answers — called when the contents store reloads its templates. */
    public static void clearCache() {
        TV_TEMPLATES.clear();
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        server = null;
        synchronized (SNAPSHOTS) {
            SNAPSHOTS.clear();
        }
        TV_TEMPLATES.clear();
    }
}

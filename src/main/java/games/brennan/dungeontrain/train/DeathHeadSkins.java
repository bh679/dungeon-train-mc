package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.relay.DeathSkinClient;
import games.brennan.playermob.skin.PlayerMobSkin;
import games.brennan.playermob.skin.PlayerMobSkinRegistry;
import games.brennan.playermob.skin.SkinModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.component.ResolvableProfile;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The pool of skins a generated {@code player_head} can wear: players who died at this same carriage
 * index, falling back to PlayerMob's bundled skins.
 *
 * <p>The carriage index is the whole idea. A head in the fourth carriage wears the face of someone
 * whose run ended in a fourth carriage — the train remembering its dead at the point they fell —
 * which is why the pool is keyed on nothing else. Early indices have a deep pool and late ones
 * almost none; a deep carriage therefore shows mostly PlayerMob skins, which is the honest shape of
 * the data rather than a failure.</p>
 *
 * <p><b>Never blocks generation.</b> {@link #pick} reads the cache and returns immediately; a miss
 * fires an off-thread {@link DeathSkinClient} fetch for NEXT time and dresses this head from
 * PlayerMob meanwhile. Worldgen runs on a hot path and must never wait on a socket, so a warm-up
 * period where heads wear bundled skins is the deliberate trade.</p>
 *
 * <p>Gated on {@link DungeonTrainConfig#isWorldInfoToRelay()} — the same consent switch that put
 * those deaths on the relay to begin with. With it off, no request is made and the heads are dressed
 * entirely from PlayerMob's registry.</p>
 */
@EventBusSubscriber(modid = games.brennan.dungeontrain.DungeonTrain.MOD_ID)
public final class DeathHeadSkins {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Salt keeping this roll independent of every other deterministic roll on the same cell. */
    private static final long SALT_HEAD_SKIN = 0x8EAD5C1D5EEDBEEFL;

    private static final long MIX_X = 0x9E3779B97F4A7C15L;
    private static final long MIX_Y = 0xBF58476D1CE4E5B9L;
    private static final long MIX_Z = 0x94D049BB133111EBL;
    private static final long MIX_C = 0xCAFEBABE12345678L;

    /**
     * How long a fetched pool stands before the next miss refetches it. Deaths accumulate slowly and
     * the relay's own index is hours stale by design, so anything shorter would only add traffic.
     */
    private static final long TTL_MS = 30L * 60L * 1000L;

    /** Cache bound — a single run visits far fewer carriage indices than this. */
    private static final int MAX_ENTRIES = 512;

    /** carriage index → skins fetched for it (possibly empty, which is a real answer worth caching). */
    private static final Map<Integer, Entry> CACHE = new ConcurrentHashMap<>();

    private record Entry(List<DeathSkinClient.Skin> skins, long fetchedAtMs) {}

    private DeathHeadSkins() {}

    /**
     * Warm the pool for {@code carriage} ahead of generating it. Cheap and idempotent: a fresh entry
     * short-circuits, and an in-flight fetch is not duplicated because the placeholder entry is
     * written before the request goes out.
     */
    public static void prefetch(int carriage) {
        if (carriage < 0) return;
        if (!DungeonTrainConfig.isWorldInfoToRelay()) return;
        Entry cur = CACHE.get(carriage);
        if (cur != null && System.currentTimeMillis() - cur.fetchedAtMs() < TTL_MS) return;
        if (CACHE.size() >= MAX_ENTRIES) CACHE.clear();
        // Claim the slot BEFORE the request so concurrent generation doesn't fan out N identical
        // fetches; an empty list is also what we want on the failure path.
        CACHE.put(carriage, new Entry(List.of(), System.currentTimeMillis()));
        DeathSkinClient.fetch(carriage, skins ->
            CACHE.put(carriage, new Entry(List.copyOf(skins), System.currentTimeMillis())));
    }

    /**
     * The profile for the head at {@code localPos} in carriage {@code carriage}, deterministic in
     * {@code (worldSeed, carriage, localPos)} so a regenerated carriage dresses its heads identically.
     *
     * <p>Empty only when there is nothing to wear at all — no death skins cached AND no PlayerMob
     * registry loaded — in which case the head stays the default one it is today.</p>
     */
    public static Optional<ResolvableProfile> pick(int carriage, long worldSeed, BlockPos localPos) {
        long state = mix(worldSeed, carriage, localPos);
        List<DeathSkinClient.Skin> pool = poolFor(carriage);
        if (!pool.isEmpty()) {
            DeathSkinClient.Skin skin = pool.get((int) Math.floorMod(state, pool.size()));
            Optional<ResolvableProfile> profile = HeadProfiles.of(skin.url(), skin.slim(), skin.name());
            if (profile.isPresent()) {
                // Says which pool dressed this head, so "did the death path actually fire?" is
                // answerable from the log rather than by staring at faces in-game.
                LOGGER.debug("[DungeonTrain] head skin carriage={} cell={} source=deaths name={} skin=...{}",
                    carriage, localPos, skin.name().isEmpty() ? "<none>" : skin.name(), tail(skin.url()));
                return profile;
            }
        }
        return fallback(state, carriage, localPos);
    }

    /** Cached skins for {@code carriage}, kicking off a refresh when stale or absent. */
    private static List<DeathSkinClient.Skin> poolFor(int carriage) {
        Entry entry = CACHE.get(carriage);
        if (entry == null || System.currentTimeMillis() - entry.fetchedAtMs() >= TTL_MS) {
            prefetch(carriage);
            // Deliberately the OLD list (or none): this call is on the generation path and must not
            // wait for the refresh it just started.
            return entry == null ? List.of() : entry.skins();
        }
        return entry.skins();
    }

    /**
     * A bundled PlayerMob skin, chosen with the same deterministic state. The hard reference to
     * playermob is safe — it is jarJar'd into Dungeon Train, the same guarantee
     * {@link games.brennan.dungeontrain.compat.EchoIdentity} relies on.
     */
    private static Optional<ResolvableProfile> fallback(long state, int carriage, BlockPos localPos) {
        Optional<PlayerMobSkin> skin = PlayerMobSkinRegistry.pickRandom(RandomSource.create(state));
        if (skin.isEmpty()) return Optional.empty();
        LOGGER.debug("[DungeonTrain] head skin carriage={} cell={} source=playermob name={} skin=...{}",
            carriage, localPos, skin.get().displayName(), tail(skin.get().textureUrl()));
        // A bundled skin's displayName documents the real player it portrays, so it titles the head
        // the same way a dead player's name does. HeadProfiles drops it if it is not a username.
        return HeadProfiles.of(skin.get().textureUrl(), skin.get().model() == SkinModel.SLIM,
            skin.get().displayName());
    }

    /** Last few characters of a texture URL — enough to tell two skins apart in a log line. */
    private static String tail(String url) {
        return url == null || url.length() < 12 ? String.valueOf(url) : url.substring(url.length() - 12);
    }

    /** Splittable-mix of the deterministic-roll inputs, mirroring {@code ContainerContentsRoller.mix}. */
    static long mix(long worldSeed, int carriage, BlockPos localPos) {
        long state = worldSeed
            ^ ((long) localPos.getX() * MIX_X)
            ^ ((long) localPos.getY() * MIX_Y)
            ^ ((long) localPos.getZ() * MIX_Z)
            ^ ((long) carriage * MIX_C)
            ^ SALT_HEAD_SKIN;
        state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
        state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
        return state ^ (state >>> 31);
    }

    /** Drop every cached pool — the next world starts from whatever the relay says then. */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clear();
    }

    /** Drop every cached pool. */
    public static void clear() {
        CACHE.clear();
    }
}

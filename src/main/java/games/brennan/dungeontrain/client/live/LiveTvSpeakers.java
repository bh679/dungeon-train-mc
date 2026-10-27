package games.brennan.dungeontrain.client.live;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.vista.TvItems;
import net.mehvahdjukaar.vista.common.tv.TVBlockEntity;
import net.mehvahdjukaar.vista.configs.ClientConfigs;
import net.mehvahdjukaar.vista.integration.sable.SableCompatClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Plays the Live Feed's sound: out of each lit TV tuned to it (placed in the world, at its screen, so it
 * moves with a Sable carriage), and flat out of the TV the viewer wears (the pinned box) or holds.
 *
 * <p>Vista ticks every TV and hands each live one to {@link #tv} ({@link LiveFeedSource#updateAudio});
 * that only records the TV as a candidate. Once a client tick, {@link #onClientTick} keeps the nearest
 * {@link #MAX_TV_SPEAKERS} candidates playing — Minecraft has few streaming channels, and a TV room holds
 * dozens of screens — and stops the rest, along with any TV that stopped asking (switched off, retuned,
 * broken, out of range). Vista's audio mode OFF silences them all; its default, REQUIRE_SPEAKER (a note
 * block beside the TV), is not asked of a Live Feed TV — almost no TV on the train has one, and the
 * stream's sound is the point of tuning in. Loudness is Vista's TV volume, then the Blocks slider.</p>
 *
 * <p>Silence rules, checked every tick so a change is heard at once: TV stream sound off, the stream or
 * its streamer muted ({@link LiveViewerMutes}), and this client streaming — its own feed would loop back
 * into its capture, and anyone else's would be rebroadcast, their voice included. The replay loop a dark
 * channel shows plays at {@link #REPLAY_VOLUME}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveTvSpeakers {

    /** Vista's own TV sound range, blocks. */
    static final double RANGE = 16;
    /** How many placed TVs play at once: the nearest. */
    static final int MAX_TV_SPEAKERS = 4;
    /** The dark channel's looped replay, as a share of full volume. */
    static final float REPLAY_VOLUME = 0.3f;
    private static final Logger LOGGER = LogUtils.getLogger();
    /** How often the speakers' line goes to the debug log while any play. */
    private static final long PERF_MS = 30_000;
    private static long lastPerfMs;
    /** A speaker that failed to start is retried after this long. */
    private static final long RETRY_MS = 250;

    /** A placed TV asking to play this tick. */
    private record Candidate(BlockPos pos, LiveFeedSource.Hearing hearing, Vec3 at, double distance) {}

    private static final class Speaker {
        final LiveSpeakerSound sound;
        final LiveHlsSession session;
        final long startedMs;
        Speaker(LiveSpeakerSound sound, LiveHlsSession session) {
            this.sound = sound;
            this.session = session;
            this.startedMs = System.currentTimeMillis();
        }
    }

    private static final Map<BlockPos, Candidate> CANDIDATES = new HashMap<>();
    private static final Map<BlockPos, Speaker> TVS = new HashMap<>();
    @Nullable private static Speaker flat;
    /** The stream most recently heard, for {@code /livemute} and the mute buttons. */
    @Nullable private static volatile LiveFeedSource.Hearing heard;

    private LiveTvSpeakers() {}

    /** Vista's tick for a TV showing a live feed: record it as a candidate speaker if it may play. */
    static void tv(LiveFeedSource source, TVBlockEntity tv, boolean playing) {
        if (!playing || tv.getLevel() == null || !tv.getLevel().isClientSide()) return;
        LiveFeedSource.Hearing h = source.hearing();
        if (h == null) return;
        Vec3 at = SableCompatClient.projectOutOfSubLevel(tv, tv.getScreenRect().center());
        double d = at.distanceTo(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());
        if (d > RANGE) return;
        BlockPos pos = tv.getBlockPos().immutable();
        CANDIDATES.put(pos, new Candidate(pos, h, at, d));
    }

    /** The stream most recently heard, or null. */
    @Nullable
    public static LiveFeedSource.Hearing heard() {
        return heard;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            stopAll();
            CANDIDATES.clear();
            return;
        }
        // Streaming: never (echo). Master or Blocks at zero: Minecraft would refuse every start, so don't ask.
        boolean mayPlay = !LiveStreamController.get().streaming()
            && mc.options.getSoundSourceVolume(SoundSource.MASTER) > 0 && mc.options.getSoundSourceVolume(SoundSource.BLOCKS) > 0;
        LiveFeedSource.Hearing flatHearing = mayPlay ? flatHearing(mc) : null;
        if (flatHearing != null && !mayHear(flatHearing)) flatHearing = null;
        flat = reconcile(flat, flatHearing, null, mc);

        List<Candidate> wanted = new ArrayList<>();
        if (mayPlay) {
            for (Candidate c : CANDIDATES.values()) {
                // The worn or held TV already plays this stream: no second copy out of the TVs around.
                if (flatHearing != null && flatHearing.session() == c.hearing().session()) continue;
                if (mayHear(c.hearing())) wanted.add(c);
            }
            wanted.sort(Comparator.comparingDouble(Candidate::distance));
        }
        CANDIDATES.clear();
        Map<BlockPos, Candidate> keep = new HashMap<>();
        for (int i = 0; i < Math.min(MAX_TV_SPEAKERS, wanted.size()); i++) keep.put(wanted.get(i).pos(), wanted.get(i));

        for (Iterator<Map.Entry<BlockPos, Speaker>> it = TVS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, Speaker> e = it.next();
            if (!keep.containsKey(e.getKey())) {
                e.getValue().sound.end();
                it.remove();
            }
        }
        for (Candidate c : keep.values()) {
            Speaker s = reconcile(TVS.get(c.pos()), c.hearing(), c.at(), mc);
            if (s != null) TVS.put(c.pos(), s);
        }
        LiveFeedSource.Hearing now = flatHearing != null ? flatHearing : wanted.isEmpty() ? null : wanted.get(0).hearing();
        if (now != null) heard = now;
        long ms = System.currentTimeMillis();
        if ((flat != null || !TVS.isEmpty()) && ms - lastPerfMs >= PERF_MS) {
            lastPerfMs = ms;
            LOGGER.debug("[DungeonTrain] [live] {}", perf());
        }
    }

    /** What the worn TV (the pinned box) or a TV in hand plays, flat; null when neither. */
    @Nullable
    private static LiveFeedSource.Hearing flatHearing(Minecraft mc) {
        if (LiveHeadViewer.watching()) {
            return wornSource().hearing();
        }
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = mc.player.getItemInHand(hand);
            if (TvItems.isTv(stack) && TvItems.isOn(stack)) {
                return (HeldTvScreen.showsSecond(stack) ? LiveFeedSource.SECOND : LiveFeedSource.MAIN).hearing();
            }
        }
        return null;
    }

    /** The worn TV's stream: the one pane it shows, or with both up, the stream Live chat is on. */
    private static LiveFeedSource wornSource() {
        return switch (HeadViewerPanes.choice()) {
            case MAIN_ONLY -> LiveFeedSource.MAIN;
            case SECOND_ONLY -> LiveFeedSource.SECOND;
            case BOTH -> LiveChatRoom.chosen() == 2 ? LiveFeedSource.SECOND : LiveFeedSource.MAIN;
        };
    }

    /** Whether {@code h} may play: Vista's audio mode not OFF, TV stream sound on, and the viewer's mutes. */
    private static boolean mayHear(LiveFeedSource.Hearing h) {
        return ClientConfigs.AUDIO_MODE.get() != ClientConfigs.AudioMode.OFF && LiveViewerMutes.audible(h.streamKey(), h.streamer());
    }

    /**
     * Keep {@code current} playing {@code h} at {@code at} (null: flat), starting or swapping the sound
     * as needed; null {@code h} stops it. Returns the speaker now playing, or null.
     */
    @Nullable
    private static Speaker reconcile(@Nullable Speaker current, @Nullable LiveFeedSource.Hearing h, @Nullable Vec3 at, Minecraft mc) {
        if (h == null) {
            if (current != null) current.sound.end();
            return null;
        }
        float volume = volume(h);
        if (current != null && current.session == h.session()) {
            if (mc.getSoundManager().isActive(current.sound)) {
                if (at != null) current.sound.moveTo(at);
                current.sound.setVolume(volume);
                return current;
            }
            // Dropped (a hitch ran the queue dry, or no free channel): start again, but not every tick.
            if (System.currentTimeMillis() - current.startedMs < RETRY_MS) return current;
        }
        if (current != null) current.sound.end();
        boolean restart = current != null && current.session == h.session();
        if (restart) LOGGER.debug("[DungeonTrain] live speaker: restarting {} (dropped)", h.streamKey());
        else LOGGER.info("[DungeonTrain] live speaker: {} plays {}{}", at == null ? "worn/held TV" : "TV at " + at,
            h.streamKey(), h.replay() ? " (replay)" : "");
        LiveSpeakerSound sound = new LiveSpeakerSound(h.session().audio(), at, volume);
        mc.getSoundManager().play(sound);
        LiveMuteChat.heard(h);
        return new Speaker(sound, h.session());
    }

    private static float volume(LiveFeedSource.Hearing h) {
        float base = ClientConfigs.AUDIO_VOLUME.get().floatValue();
        return h.replay() ? base * REPLAY_VOLUME : base;
    }

    /** Stop every speaker (leaving a world). */
    static void stopAll() {
        for (Speaker s : TVS.values()) s.sound.end();
        TVS.clear();
        if (flat != null) flat.sound.end();
        flat = null;
    }

    /** The {@code [live]} perf line: speakers playing and the worst lag behind the feed, ms. */
    static String perf() {
        long lag = flat != null ? flat.sound.lagMs() : 0;
        StringBuilder at = new StringBuilder();
        for (Speaker s : TVS.values()) {
            lag = Math.max(lag, s.sound.lagMs());
            at.append(String.format(java.util.Locale.ROOT, " (%.1f %.1f %.1f)", s.sound.getX(), s.sound.getY(), s.sound.getZ()));
        }
        return "speakers " + (TVS.size() + (flat != null ? 1 : 0)) + (flat != null ? " flat" : "") + at
            + " audio lag " + lag + " ms";
    }
}

package games.brennan.dungeontrain.client.twitch;

import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * What this client last heard from the relay about the player's Twitch link — linked or not, which
 * channel, and the two opt-ins — for the Account tab of Options → Dungeon Train
 * ({@code DungeonTrainClientOptionsScreen}) and the name tag burned into the stream
 * ({@code StreamNameTag}). The relay is the only source of truth; this is a cache so the tab can draw
 * at once and re-draw when an answer lands. The shape of {@code DiscordAccountState}.
 *
 * <p>Render thread only: {@link #refresh} and the toggles resolve their HTTP off-thread and hop back
 * with {@code Minecraft#execute} before touching any field or running {@code onChange}.</p>
 */
public final class TwitchAccountState {

    /** Where the tab stands: still asking, an answer, or a failure it can say something about. */
    public enum Phase { LOADING, READY, FAILED }

    private static Phase phase = Phase.LOADING;
    private static TwitchLinkClient.Status status = null;
    private static TwitchLinkClient.Error error = TwitchLinkClient.Error.NONE;
    private static boolean toggleFailed = false;
    private static boolean inFlight = false;
    private static final List<Runnable> waiting = new ArrayList<>();
    private static boolean staleInFlight = false;

    private TwitchAccountState() {}

    public static Phase phase() {
        return phase;
    }

    /** The last good answer, or {@code null} before one arrived. */
    @Nullable
    public static TwitchLinkClient.Status status() {
        return status;
    }

    /** The linked twitch.tv login, or {@code null} when unlinked or unknown. */
    @Nullable
    public static String linkedLogin() {
        return status != null && status.linked() && !status.login().isEmpty() ? status.login() : null;
    }

    public static TwitchLinkClient.Error error() {
        return error;
    }

    public static boolean toggleFailed() {
        return toggleFailed;
    }

    /** Ask the relay again, then run {@code onChange} on the render thread. One request at a time. */
    public static void refresh(Runnable onChange) {
        waiting.add(onChange);
        if (!inFlight) start();
    }

    private static void start() {
        inFlight = true;
        if (status == null) phase = Phase.LOADING;
        TwitchLinkClient.status().whenComplete((s, err) -> onClient(() -> {
            inFlight = false;
            if (staleInFlight) {
                staleInFlight = false;
                start();
                return;
            }
            if (err == null && s != null && s.ok()) {
                status = s;
                phase = Phase.READY;
                error = TwitchLinkClient.Error.NONE;
                toggleFailed = false;
            } else {
                error = s == null ? TwitchLinkClient.Error.FAILED : s.error();
                if (status == null) phase = Phase.FAILED;
            }
            List<Runnable> done = List.copyOf(waiting);
            waiting.clear();
            done.forEach(Runnable::run);
        }));
    }

    /** Forget the cached answer — the next tab draw asks again. */
    public static void invalidate() {
        status = null;
        phase = Phase.LOADING;
        if (inFlight) staleInFlight = true;
    }

    /** Flip the restream opt-in; {@code onChange} runs on the render thread with the result applied. */
    public static void setRestream(boolean on, Runnable onChange) {
        TwitchLinkClient.setSettings(on, null).whenComplete((s, err) -> onClient(() -> apply(s, err, onChange)));
    }

    /** Flip the title/category opt-in; {@code onChange} runs on the render thread with the result applied. */
    public static void setTitle(boolean on, Runnable onChange) {
        TwitchLinkClient.setSettings(null, on).whenComplete((s, err) -> onClient(() -> apply(s, err, onChange)));
    }

    private static void apply(TwitchLinkClient.Status s, Throwable err, Runnable onChange) {
        if (err == null && s != null && s.ok()) {
            status = s;
            toggleFailed = false;
        } else {
            toggleFailed = true; // the widgets rebuild from the unchanged status — the click is undone
        }
        onChange.run();
    }

    private static void onClient(Runnable r) {
        Minecraft.getInstance().execute(r);
    }
}

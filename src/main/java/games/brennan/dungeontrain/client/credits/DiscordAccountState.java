package games.brennan.dungeontrain.client.credits;

import games.brennan.dungeontrain.discord.PingType;
import net.minecraft.client.Minecraft;

import java.util.Map;

/**
 * What this client last heard from the relay about the player's Discord link — linked or not, and
 * which pings are on — for the Account tab of Options → Dungeon Train
 * ({@code DungeonTrainClientOptionsScreen}). The relay is the only source of truth; this is a cache so
 * the tab can draw at once and re-draw when an answer lands.
 *
 * <p>Render thread only: {@link #refresh} and the toggles resolve their HTTP off-thread and hop back
 * with {@code Minecraft#execute} before touching any field or running {@code onChange}.</p>
 */
public final class DiscordAccountState {

    /** Where the tab stands: still asking, an answer, or a failure it can say something about. */
    public enum Phase { LOADING, READY, FAILED }

    private static Phase phase = Phase.LOADING;
    private static CommunityLinkClient.Status status = null;
    private static CommunityLinkClient.Error error = CommunityLinkClient.Error.NONE;
    /** The last toggle failed and was put back; the tab shows a one-line warning until the next answer. */
    private static boolean toggleFailed = false;
    private static boolean inFlight = false;

    private DiscordAccountState() {}

    public static Phase phase() {
        return phase;
    }

    /** The last good answer, or {@code null} before one arrived. */
    public static CommunityLinkClient.Status status() {
        return status;
    }

    public static CommunityLinkClient.Error error() {
        return error;
    }

    public static boolean toggleFailed() {
        return toggleFailed;
    }

    /** Ask the relay again, then run {@code onChange} on the render thread. One request at a time. */
    public static void refresh(Runnable onChange) {
        if (inFlight) return;
        inFlight = true;
        if (status == null) phase = Phase.LOADING;
        CommunityLinkClient.status().whenComplete((s, err) -> onClient(() -> {
            inFlight = false;
            if (err == null && s != null && s.ok()) {
                status = s;
                phase = Phase.READY;
                error = CommunityLinkClient.Error.NONE;
                toggleFailed = false;
            } else {
                error = s == null ? CommunityLinkClient.Error.FAILED : s.error();
                // Keep showing the last good answer if there is one; a blip shouldn't empty the tab.
                if (status == null) phase = Phase.FAILED;
            }
            onChange.run();
        }));
    }

    /** Forget the cached answer, e.g. after opening the link screen — the next tab draw asks again. */
    public static void invalidate() {
        status = null;
        phase = Phase.LOADING;
    }

    /** Turn the master switch on/off; {@code onChange} runs on the render thread with the result applied. */
    public static void setMaster(boolean on, Runnable onChange) {
        CommunityLinkClient.setPings(on).whenComplete((p, err) -> onClient(() -> apply(p, err, onChange)));
    }

    /** Turn one kind of ping on/off; {@code onChange} runs on the render thread with the result applied. */
    public static void setType(PingType type, boolean on, Runnable onChange) {
        CommunityLinkClient.setPingType(type, on).whenComplete((p, err) -> onClient(() -> apply(p, err, onChange)));
    }

    private static void apply(CommunityLinkClient.Pings p, Throwable err, Runnable onChange) {
        if (err == null && p != null && p.ok() && status != null) {
            status = withPings(status, p.on(), p.types());
            toggleFailed = false;
        } else {
            toggleFailed = true; // the widgets rebuild from the unchanged status — the click is undone
        }
        onChange.run();
    }

    static CommunityLinkClient.Status withPings(CommunityLinkClient.Status s, boolean on, Map<PingType, Boolean> types) {
        return new CommunityLinkClient.Status(s.ok(), s.linked(), on, types, s.error());
    }

    private static void onClient(Runnable r) {
        Minecraft.getInstance().execute(r);
    }
}

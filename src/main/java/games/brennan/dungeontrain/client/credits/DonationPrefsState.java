package games.brennan.dungeontrain.client.credits;

import net.minecraft.client.Minecraft;

/**
 * What this client last heard from the relay about how the player's donations are announced — the
 * "Donation messages" rows of the Account tab ({@code DungeonTrainClientOptionsScreen}). A cache over
 * {@link DonationPrefsClient}, shaped like {@link DiscordAccountState}: the relay is the truth, the tab
 * draws from here and re-draws when an answer lands.
 *
 * <p>Render thread only: requests resolve off-thread and hop back with {@code Minecraft#execute} before
 * touching any field or running {@code onChange}.</p>
 */
public final class DonationPrefsState {

    /** Still asking, an answer, or the relay couldn't be reached. */
    public enum Phase { LOADING, READY, FAILED }

    private static Phase phase = Phase.LOADING;
    private static DonationPrefsClient.Prefs prefs = null;
    /** The last change failed and was put back; the tab says so until the next good answer. */
    private static boolean saveFailed = false;
    private static boolean inFlight = false;

    private DonationPrefsState() {}

    public static Phase phase() {
        return phase;
    }

    /** The last good answer, or {@code null} before one arrived. */
    public static DonationPrefsClient.Prefs prefs() {
        return prefs;
    }

    public static boolean saveFailed() {
        return saveFailed;
    }

    /** Ask the relay again, then run {@code onChange} on the render thread. One request at a time. */
    public static void refresh(Runnable onChange) {
        if (inFlight) return;
        inFlight = true;
        if (prefs == null) phase = Phase.LOADING;
        DonationPrefsClient.get().whenComplete((p, err) -> onClient(() -> {
            inFlight = false;
            if (err == null && p != null && p.ok()) {
                prefs = p;
                phase = Phase.READY;
                saveFailed = false;
            } else if (prefs == null) {
                phase = Phase.FAILED; // keep a previous answer on screen through a blip
            }
            onChange.run();
        }));
    }

    /** Forget the cached answer — the next draw asks again. */
    public static void invalidate() {
        prefs = null;
        phase = Phase.LOADING;
    }

    public static void setVisibility(DonationPrefsClient.Visibility visibility, Runnable onChange) {
        DonationPrefsClient.setVisibility(visibility).whenComplete((p, err) -> onClient(() -> apply(p, err, onChange)));
    }

    public static void setTag(boolean tag, Runnable onChange) {
        DonationPrefsClient.setTag(tag).whenComplete((p, err) -> onClient(() -> apply(p, err, onChange)));
    }

    private static void apply(DonationPrefsClient.Prefs p, Throwable err, Runnable onChange) {
        if (err == null && p != null && p.ok()) {
            prefs = p;
            phase = Phase.READY;
            saveFailed = false;
        } else {
            saveFailed = true; // the widgets rebuild from the unchanged answer — the click is undone
        }
        onChange.run();
    }

    private static void onClient(Runnable r) {
        Minecraft.getInstance().execute(r);
    }
}

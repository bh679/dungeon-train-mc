package games.brennan.dungeontrain.client.version.compare;

import net.minecraft.client.Minecraft;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Session-scoped holder for each platform's modpack listing, the data behind
 * {@link VersionCompareScreen}. One JVM, one successful fetch per platform; a failed fetch is
 * retried the next time the data is asked for, so a page opened offline recovers on the next
 * open rather than staying blank all session.
 *
 * <p>Writes happen on the fetcher's single daemon thread; the render thread only reads the
 * {@code volatile} slots. When a listing lands while the Versions page is open, the page is told
 * on the render thread so its rows and changelog fill in without a resize.</p>
 */
public final class VersionCompareState {

    public enum Status { LOADING, OK, ERROR }

    private static final Map<Platform, Slot> SLOTS = new EnumMap<>(Platform.class);
    private static final Map<SiblingMod, Slot> SIBLINGS = new EnumMap<>(SiblingMod.class);
    private static final LedgerSlot LEDGER = new LedgerSlot();
    /**
     * The tags the player has chosen to see, empty for all. Session-scoped and shared by the page
     * and its fullscreen view so a choice survives switching between them. Render thread only.
     */
    private static Set<ChangelogTag> tagFilter = Set.of();

    static {
        for (Platform p : Platform.values()) {
            SLOTS.put(p, new Slot());
        }
        for (SiblingMod m : SiblingMod.values()) {
            SIBLINGS.put(m, new Slot());
        }
    }

    private VersionCompareState() {}

    /** Kick off any fetch that has not succeeded yet. Idempotent while one is in flight. */
    public static void ensureFetched() {
        for (Platform p : Platform.values()) {
            if (arm(SLOTS.get(p))) {
                PackVersionFetcher.fetchAsync(p);
            }
        }
        for (SiblingMod m : SiblingMod.values()) {
            // A sibling that is not on this client has nothing to compare against.
            if (m.installedVersion().isPresent() && arm(SIBLINGS.get(m))) {
                PackVersionFetcher.fetchSiblingAsync(m);
            }
        }
        if (arm(LEDGER)) {
            PackVersionFetcher.fetchLedgerAsync();
        }
    }

    /** Mark a slot as in flight if it has never succeeded; false when a fetch is already running or done. */
    private static boolean arm(Slot slot) {
        if (slot.attempted && slot.status != Status.ERROR) {
            return false;
        }
        slot.attempted = true;
        slot.status = Status.LOADING;
        return true;
    }

    public static Status status(Platform platform) {
        return SLOTS.get(platform).status;
    }

    public static Optional<PlatformVersions> versions(Platform platform) {
        return Optional.ofNullable(SLOTS.get(platform).versions);
    }

    static void accept(PlatformVersions versions) {
        Slot slot = SLOTS.get(versions.platform());
        slot.versions = versions;
        slot.status = Status.OK;
        notifyScreen();
    }

    static void fail(Platform platform) {
        Slot slot = SLOTS.get(platform);
        slot.status = Status.ERROR;
        notifyScreen();
    }

    public static Status siblingStatus(SiblingMod mod) {
        return SIBLINGS.get(mod).status;
    }

    public static Optional<PlatformVersions> siblingVersions(SiblingMod mod) {
        return Optional.ofNullable(SIBLINGS.get(mod).versions);
    }

    static void acceptSibling(SiblingMod mod, PlatformVersions versions) {
        Slot slot = SIBLINGS.get(mod);
        slot.versions = versions;
        slot.status = Status.OK;
        notifyScreen();
    }

    static void failSibling(SiblingMod mod) {
        SIBLINGS.get(mod).status = Status.ERROR;
        notifyScreen();
    }

    public static Status ledgerStatus() {
        return LEDGER.status;
    }

    /** The curated ledger, once it has loaded; empty while loading or after a failed fetch. */
    public static Optional<ChangelogLedger> ledger() {
        return Optional.ofNullable(LEDGER.ledger);
    }

    static void acceptLedger(ChangelogLedger ledger) {
        LEDGER.ledger = ledger;
        LEDGER.status = Status.OK;
        notifyScreen();
    }

    static void failLedger() {
        LEDGER.status = Status.ERROR;
        notifyScreen();
    }

    public static Set<ChangelogTag> tagFilter() {
        return tagFilter;
    }

    /** Replace the filter with an immutable copy of {@code tags}; empty means show everything. */
    public static void setTagFilter(Set<ChangelogTag> tags) {
        tagFilter = tags.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(tags));
    }

    /** Runs on the HTTP thread; everything Minecraft-side is deferred to the render thread. */
    private static void notifyScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            if (mc.screen instanceof VersionCompareScreen screen) {
                screen.onDataChanged();
            }
            VersionCompareBootPrompt.onDataChanged();
        });
    }

    private static class Slot {
        volatile Status status = Status.LOADING;
        volatile PlatformVersions versions;
        volatile boolean attempted;
    }

    private static final class LedgerSlot extends Slot {
        volatile ChangelogLedger ledger;
    }
}

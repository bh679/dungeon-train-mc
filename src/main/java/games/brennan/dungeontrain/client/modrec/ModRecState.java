package games.brennan.dungeontrain.client.modrec;

import games.brennan.dungeontrain.modrec.ModRoster;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Mod Recommendations page's session state: which of the player's non-Dungeon-Train mods are
 * on offer, what order they sit in, which have been sent, and what is currently typed.
 *
 * <p><b>Ordering</b> — unsent mods first, most-run-by-other-players first (ties broken by display
 * name), then everything already sent, oldest send first. A sent tile therefore sinks to the
 * bottom and stays put: re-sending a mod replaces the earlier recommendation without moving the
 * tile out from under the cursor. Popularity is used for the order only and never shown, so a
 * missing or failed fetch degrades to a plain alphabetical grid rather than a broken one.</p>
 *
 * <p>The "something not installed" request tile is not part of this list — it is pinned below the
 * grid by {@link ModRecPage} and is never marked sent, since a player may request more than one
 * mod.</p>
 *
 * <p>Pure and free of Minecraft types so the ordering rules can be unit-tested directly.</p>
 */
public final class ModRecState {

    /** Selection sentinel for the pinned "something not installed…" tile. */
    public static final String REQUEST_ID = "\0request";

    /** Longest accepted comment / requested-name, matching Discord Presence's survey comment cap. */
    public static final int MAX_COMMENT = 300;
    public static final int MAX_NAME = 80;

    /**
     * One grid tile. {@code sent} drives both the colour and the sink-to-bottom ordering;
     * {@code request} marks a card for a mod the player asked for but doesn't have, which is a
     * record of what they sent rather than something to click again.
     */
    public record Tile(String modId, String displayName, boolean sent, boolean request,
                       boolean reported) {}

    private final List<ModRoster.LoadedMod> mods;
    private final LinkedHashSet<String> sent = new LinkedHashSet<>();
    /** Mods flagged as a cheat/hack this death, in flag order — kept apart from {@link #sent}. */
    private final LinkedHashSet<String> reported = new LinkedHashSet<>();
    /** Names of mods requested this death, in send order — each becomes its own card. */
    private final List<String> sentRequests = new ArrayList<>();

    private Map<String, Integer> popularity = Map.of();
    private String selected = null;
    /** Whether the current selection is a hack report rather than a recommendation. */
    private boolean reportingHack = false;
    private String comment = "";
    private String requestedName = "";
    private int sentCount = 0;

    public ModRecState(List<ModRoster.LoadedMod> mods) {
        this.mods = List.copyOf(mods);
    }

    /** True when there is nothing to ask about — the page must not enter the deck. */
    public boolean isEmpty() {
        return mods.isEmpty();
    }

    /** Adopt a fetched popularity map ({@code modId → players running it}). Never shown, only sorted by. */
    public void setPopularity(Map<String, Integer> byModId) {
        this.popularity = byModId == null ? Map.of() : Map.copyOf(byModId);
    }

    public List<Tile> tiles() {
        return order(mods, popularity, sent, reported, sentRequests);
    }

    /**
     * Pure ordering: unsent by popularity desc then name, then sent mods in send order, then one
     * card per requested mod. {@code sentOrder} must preserve insertion order (a
     * {@link LinkedHashSet} at the call site).
     */
    public static List<Tile> order(List<ModRoster.LoadedMod> mods,
                                   Map<String, Integer> popularity,
                                   Set<String> sentOrder,
                                   Set<String> reportedOrder,
                                   List<String> sentRequests) {
        Set<String> flagged = reportedOrder == null ? Set.<String>of() : reportedOrder;
        List<Tile> unsent = new ArrayList<>();
        for (ModRoster.LoadedMod m : mods) {
            if (!sentOrder.contains(m.modId()) && !flagged.contains(m.modId())) {
                unsent.add(new Tile(m.modId(), name(m), false, false, false));
            }
        }
        unsent.sort(Comparator
                .comparingInt((Tile t) -> -popularity.getOrDefault(t.modId(), 0))
                .thenComparing(t -> t.displayName().toLowerCase(Locale.ROOT)));

        List<Tile> out = new ArrayList<>(unsent);
        for (String modId : sentOrder) {
            for (ModRoster.LoadedMod m : mods) {
                if (m.modId().equals(modId)) {
                    out.add(new Tile(modId, name(m), true, false, false));
                    break;
                }
            }
        }
        // Flagged mods sink alongside the recommended ones and keep their slot on a re-flag, for the
        // same reason: the tile must not move out from under the cursor.
        for (String modId : flagged) {
            for (ModRoster.LoadedMod m : mods) {
                if (m.modId().equals(modId)) {
                    out.add(new Tile(modId, name(m), true, false, true));
                    break;
                }
            }
        }
        // Requested mods have no modId — the id is synthesised purely so each card is distinct.
        // They are never selectable (ModRecPage registers no hit for them): there is nothing to
        // change about a mod the player doesn't have, and a second thought is a second request.
        List<String> requests = sentRequests == null ? List.of() : sentRequests;
        for (int i = 0; i < requests.size(); i++) {
            out.add(new Tile(REQUEST_ID + "#" + i, requests.get(i), true, true, false));
        }
        return List.copyOf(out);
    }

    private static String name(ModRoster.LoadedMod m) {
        return m.displayName() == null || m.displayName().isBlank() ? m.modId() : m.displayName();
    }

    // ---- selection + typing ----

    public String selected() { return selected; }

    public boolean isSelected(String modId) { return modId != null && modId.equals(selected); }

    public boolean isRequesting() { return REQUEST_ID.equals(selected); }

    /** Whether the live selection is a hack report — the page and the send path both branch on it. */
    public boolean isReportingHack() { return selected != null && reportingHack; }

    /** Click a tile: selects it for a recommendation, or clears an identical live selection. */
    public void toggle(String modId) {
        select(modId, false);
    }

    /**
     * Click a tile's ⚠ icon: selects it for a hack report. Ignored for the "not installed" tile —
     * there is nothing to report about a mod the player doesn't have.
     */
    public void toggleHack(String modId) {
        if (REQUEST_ID.equals(modId)) return;
        select(modId, true);
    }

    /**
     * Shared selection: re-clicking the same tile in the same mode clears it, while switching mode
     * on the selected tile re-arms it — the typed comment is dropped either way, since "why I'd
     * recommend it" and "what it lets you do" are not the same sentence.
     */
    private void select(String modId, boolean hack) {
        if (isSelected(modId) && reportingHack == hack) {
            clearSelection();
            return;
        }
        selected = modId;
        reportingHack = hack;
        comment = "";
        requestedName = "";
    }

    public void clearSelection() {
        selected = null;
        reportingHack = false;
        comment = "";
        requestedName = "";
    }

    public String comment() { return comment; }

    public void setComment(String s) { this.comment = s == null ? "" : s; }

    public String requestedName() { return requestedName; }

    public void setRequestedName(String s) { this.requestedName = s == null ? "" : s; }

    /**
     * Whether Send is live. A recommendation always needs a comment — a bare tile click says
     * nothing the mod list itself doesn't already say — and a request additionally needs a name.
     */
    public boolean canSend() {
        if (selected == null || comment.isBlank()) return false;
        return !isRequesting() || !requestedName.isBlank();
    }

    /** The display name of the current selection, for the button row's label. */
    public String selectedName() {
        if (selected == null) return "";
        if (isRequesting()) return requestedName;
        for (ModRoster.LoadedMod m : mods) {
            if (m.modId().equals(selected)) return name(m);
        }
        return selected;
    }

    /** Record a successful send: the tile sinks to the bottom and the inputs clear. */
    public void markSent() {
        if (selected == null) return;
        if (isReportingHack()) {
            // A mod is either recommended or flagged, never both: the two sets are kept disjoint so
            // changing your mind replaces the tile rather than drawing it twice. add() on an element
            // already present is a no-op, so a re-flag keeps the tile put.
            sent.remove(selected);
            reported.add(selected);
        } else if (isRequesting()) {
            // Each request becomes its own card, so the player can see what they've asked for and
            // ask for something else. Duplicates are kept rather than merged: sending the same name
            // twice means they sent it twice, and hiding the second one would look like a failure.
            sentRequests.add(requestedName.trim());
        } else {
            // add() on an element already present is a no-op for a LinkedHashSet, so a re-send
            // keeps the tile exactly where it was rather than jumping to the end of the grid.
            reported.remove(selected);
            sent.add(selected);
        }
        sentCount++;
        clearSelection();
    }

    public int sentCount() { return sentCount; }
}

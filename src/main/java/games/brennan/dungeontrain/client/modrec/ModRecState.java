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
    public record Tile(String modId, String displayName, boolean sent, boolean request) {}

    private final List<ModRoster.LoadedMod> mods;
    private final LinkedHashSet<String> sent = new LinkedHashSet<>();
    /** Names of mods requested this death, in send order — each becomes its own card. */
    private final List<String> sentRequests = new ArrayList<>();

    private Map<String, Integer> popularity = Map.of();
    private String selected = null;
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
        return order(mods, popularity, sent, sentRequests);
    }

    /**
     * Pure ordering: unsent by popularity desc then name, then sent mods in send order, then one
     * card per requested mod. {@code sentOrder} must preserve insertion order (a
     * {@link LinkedHashSet} at the call site).
     */
    public static List<Tile> order(List<ModRoster.LoadedMod> mods,
                                   Map<String, Integer> popularity,
                                   Set<String> sentOrder,
                                   List<String> sentRequests) {
        List<Tile> unsent = new ArrayList<>();
        for (ModRoster.LoadedMod m : mods) {
            if (!sentOrder.contains(m.modId())) {
                unsent.add(new Tile(m.modId(), name(m), false, false));
            }
        }
        unsent.sort(Comparator
                .comparingInt((Tile t) -> -popularity.getOrDefault(t.modId(), 0))
                .thenComparing(t -> t.displayName().toLowerCase(Locale.ROOT)));

        List<Tile> out = new ArrayList<>(unsent);
        for (String modId : sentOrder) {
            for (ModRoster.LoadedMod m : mods) {
                if (m.modId().equals(modId)) {
                    out.add(new Tile(modId, name(m), true, false));
                    break;
                }
            }
        }
        // Requested mods have no modId — the id is synthesised purely so each card is distinct.
        // They are never selectable (ModRecPage registers no hit for them): there is nothing to
        // change about a mod the player doesn't have, and a second thought is a second request.
        List<String> requests = sentRequests == null ? List.of() : sentRequests;
        for (int i = 0; i < requests.size(); i++) {
            out.add(new Tile(REQUEST_ID + "#" + i, requests.get(i), true, true));
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

    /** Click a tile: selects it, or clears the selection when it was already selected. */
    public void toggle(String modId) {
        if (isSelected(modId)) {
            clearSelection();
        } else {
            selected = modId;
            comment = "";
            requestedName = "";
        }
    }

    public void clearSelection() {
        selected = null;
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
        if (isRequesting()) {
            // Each request becomes its own card, so the player can see what they've asked for and
            // ask for something else. Duplicates are kept rather than merged: sending the same name
            // twice means they sent it twice, and hiding the second one would look like a failure.
            sentRequests.add(requestedName.trim());
        } else {
            // add() on an element already present is a no-op for a LinkedHashSet, so a re-send
            // keeps the tile exactly where it was rather than jumping to the end of the grid.
            sent.add(selected);
        }
        sentCount++;
        clearSelection();
    }

    public int sentCount() { return sentCount; }
}

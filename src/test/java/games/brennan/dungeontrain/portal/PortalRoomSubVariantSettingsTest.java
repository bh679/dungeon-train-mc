package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.editor.TrackVariantGroupStore;
import games.brennan.dungeontrain.template.TemplateMeta;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantGroup;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A sub-variant's boundary settings are <b>its own</b>, and they survive the group hop.
 *
 * <p>{@link TrackVariantRegistry#pickName} resolves a group to a <i>member name</i>, and
 * {@code PortalCarriageBuilder.planStructure} then reads {@code PortalRoomSettings.of(thatName)}.
 * So which room gets built and which settings it is built with come from the same name — a member
 * is an ordinary room that happens to also be reachable through a parent.</p>
 *
 * <p><b>No inheritance, deliberately.</b> Setting a parent to Random does not reach its members;
 * each design says what it does at its own walls. That is a decision rather than an oversight, and
 * it is easy to "helpfully" undo later — hence the tests below, which make a change of mind argue
 * with something.</p>
 *
 * <p>Groups and weights are injected, so nothing here touches the filesystem or needs a Forge
 * bootstrap — the same setup {@link PortalRoomSubVariantTest} uses.</p>
 */
final class PortalRoomSubVariantSettingsTest {

    private static final TrackKind KIND = TrackKind.PORTAL_ROOM;
    private static final int SEEDS = 400;

    /** A member that scatters corridors thickly and always stands its exit elsewhere. */
    private static final String MEMBER_TAG = "endless_repetition/dynamic/off/random:4:10";

    /** A parent set the same way — used to prove the member does NOT pick it up. */
    private static final String PARENT_TAG = "endless_repetition/dynamic/off/random:9:10";

    @BeforeEach
    @AfterEach
    void cleanSlate() {
        TrackVariantRegistry.clear();
        TrackVariantGroupStore.clearCache();
        TrackVariantWeights.clear();
        // The mod ships a real group sidecar for 'default', which is in every pool as the synthetic
        // fallback — inject an empty one so shipped content cannot decide these answers.
        TrackVariantGroupStore.injectForTesting(KIND, TrackKind.DEFAULT_NAME, TrackVariantGroup.EMPTY);
    }

    private static TrackVariantGroup group(String... idsAndWeights) {
        List<TrackVariantGroup.Member> ms = new ArrayList<>();
        for (int i = 0; i < idsAndWeights.length; i += 2) {
            ms.add(new TrackVariantGroup.Member(idsAndWeights[i], Integer.parseInt(idsAndWeights[i + 1])));
        }
        return new TrackVariantGroup(ms);
    }

    private static void room(String name, String modeTag) {
        TrackVariantRegistry.register(KIND, name);
        TrackVariantWeights.injectForTesting(KIND, name, TemplateMeta.of(1).withMode(modeTag));
    }

    /** Every distinct name drawn over {@link #SEEDS} pairs, minus the synthetic fallback. */
    private static Set<String> namesPicked(long worldSeed) {
        Set<String> seen = new HashSet<>();
        for (int pair = 0; pair < SEEDS; pair++) {
            seen.add(TrackVariantRegistry.pickName(KIND, worldSeed, pair, null));
        }
        seen.remove(TrackKind.DEFAULT_NAME);
        return seen;
    }

    @Test
    @DisplayName("A sub-variant's own Exits and moved-exit settings survive the group hop")
    void memberKeepsItsOwnSettings() {
        room("library", PARENT_TAG);
        room("library_tall", MEMBER_TAG);
        TrackVariantGroupStore.injectForTesting(KIND, "library", group("library_tall", "1"));

        // The pick resolves to the MEMBER's name, and that is the name the settings are read from.
        assertEquals(true, namesPicked(1234L).contains("library_tall"),
            "the member is drawn through its parent");

        PortalRoomExits exits = PortalRoomSettings.of("library_tall").effectiveExits();
        assertSame(PortalRoomExits.Kind.RANDOM, exits.kind());
        assertEquals(4, exits.every());
        assertEquals(PortalRoomExits.MOVE_ALWAYS, exits.moveChance());
        // …and it is the member's own spacing, not the parent's.
        assertNotEquals(PortalRoomSettings.of("library").exits().every(), exits.every());
    }

    @Test
    @DisplayName("A parent's settings never reach a member that said nothing — no inheritance")
    void memberDoesNotInheritTheParent() {
        room("library", PARENT_TAG);
        // Says nothing beyond its walls: no copies, no contents, no exits segment.
        room("library_plain", "endless_repetition");
        TrackVariantGroupStore.injectForTesting(KIND, "library", group("library_plain", "1"));

        PortalRoomExits parent = PortalRoomSettings.of("library").effectiveExits();
        PortalRoomExits member = PortalRoomSettings.of("library_plain").effectiveExits();

        assertSame(PortalRoomExits.Kind.RANDOM, parent.kind());
        // The member falls back to what its OWN mode wants — Endless Repetition's default, the
        // lattice — rather than to the Random its parent asked for.
        assertSame(PortalRoomExits.Kind.ON, member.kind());
        assertEquals(PortalRoomExits.DEFAULT_EVERY, member.every());
        assertEquals(PortalRoomExits.MOVE_NEVER, member.moveChance());
    }

    @Test
    @DisplayName("A member is the same room whether it was drawn through its parent or on its own")
    void directDrawsMatchGroupDraws() {
        room("library", PARENT_TAG);
        room("library_tall", MEMBER_TAG);

        // Settings before the group exists at all — the member as an ordinary top-level room.
        PortalRoomSettings alone = PortalRoomSettings.of("library_tall");

        TrackVariantGroupStore.injectForTesting(KIND, "library", group("library_tall", "1"));
        PortalRoomSettings inGroup = PortalRoomSettings.of("library_tall");

        // Joining a group must not silently change how a design behaves when drawn on its own.
        assertEquals(alone, inGroup);
        assertEquals(alone.toTag(), inGroup.toTag());
    }

    @Test
    @DisplayName("A member's plot offers exactly the rows its own tag earns")
    void memberPlotShowsTheSameRowsAnyRoomWould() {
        room("library", PARENT_TAG);
        room("library_tall", MEMBER_TAG);
        room("library_plain", "endless_repetition");
        TrackVariantGroupStore.injectForTesting(KIND, "library",
            group("library_tall", "1", "library_plain", "1"));

        // The editor gates its rows on the resolved tag, so a member's rows follow the member.
        PortalRoomSettings tall = PortalRoomSettings.of("library_tall");
        assertEquals(true, tall.exitsApply(), "an endless member offers the Exits row");
        assertEquals(true, tall.exits().movesApply(),
            "a member set to Random offers the moved-exit stepper");

        PortalRoomSettings plain = PortalRoomSettings.of("library_plain");
        assertEquals(true, plain.exitsApply());
        // Still on the lattice, so no moved-exit stepper — the same rule a top-level room follows,
        // and the reason a sub-variant can look as though the option is missing.
        assertEquals(false, plain.exits().movesApply());
    }
}

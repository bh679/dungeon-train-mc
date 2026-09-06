package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.track.variant.TrackVariantGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Renaming a room that is a member of a group.
 *
 * <p>The membership rewrite is the half of a rename that decides how often the room comes up
 * afterwards, and it is the half that would fail silently: a member re-added at the default weight
 * instead of its own would leave a room that used to be rare rolling as often as everything else,
 * with nothing in the diff but a name. So the slot, the weight, the gate and the Stage links are all
 * pinned here — the file work around it needs a server and is covered by the in-game pass.</p>
 */
final class TrackVariantRenameGroupTest {

    private static TrackVariantGroup group() {
        return new TrackVariantGroup(3, List.of(
            new TrackVariantGroup.Member("ender", 1, TemplateGate.DEFAULT, List.of()),
            new TrackVariantGroup.Member("aggressiveplayers", 2,
                TemplateGate.DEFAULT.withMaxLevel(14), List.of("stone")),
            new TrackVariantGroup.Member("deserter", 5, TemplateGate.DEFAULT, List.of())));
    }

    @Test
    @DisplayName("A renamed member keeps its slot, its weight, its gate and its stages")
    void renamedMemberKeepsEverythingButItsName() {
        TrackVariantGroup before = group();
        TrackVariantGroup after = TrackVariantRename.withMemberRenamed(before, "aggressiveplayers", "hostiles");

        assertEquals(List.of("ender", "hostiles", "deserter"),
            after.members().stream().map(TrackVariantGroup.Member::id).toList(),
            "the member is renamed in place, not appended");
        TrackVariantGroup.Member renamed = after.members().get(1);
        assertEquals(2, renamed.weight(), "its weight follows it");
        assertEquals(before.members().get(1).gate(), renamed.gate(), "and its level gate");
        assertEquals(List.of("stone"), renamed.stageIds(), "and its Stage links");
        assertEquals(3, after.selfWeight(), "the parent's own weight is not a member's business");
    }

    @Test
    @DisplayName("The other members are untouched")
    void othersAreLeftAlone() {
        TrackVariantGroup after = TrackVariantRename.withMemberRenamed(group(), "aggressiveplayers", "hostiles");
        assertEquals(1, after.members().get(0).weight());
        assertEquals(5, after.members().get(2).weight());
    }

    @Test
    @DisplayName("A group that does not hold the name is returned as it was")
    void unrelatedGroupIsUnchanged() {
        TrackVariantGroup before = group();
        assertSame(before, TrackVariantRename.withMemberRenamed(before, "somethingelse", "hostiles"),
            "no rewrite, and no new file write behind it");
    }

    @Test
    @DisplayName("The name is matched as the store spells it — lowercase")
    void matchIsCaseInsensitiveOnTheSourceName() {
        TrackVariantGroup after = TrackVariantRename.withMemberRenamed(group(), "AggressivePlayers", "hostiles");
        assertEquals("hostiles", after.members().get(1).id());
    }
}

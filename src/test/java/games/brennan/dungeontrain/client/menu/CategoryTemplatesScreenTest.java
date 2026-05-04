package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.tunnel.TunnelPlacer.TunnelVariant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the slash commands produced by
 * {@link CategoryTemplatesScreen#trackEnterCommandFor(Template)}.
 *
 * <p>Regression guard for the bug where pillar and adjunct rows in the
 * keyboard-menu drilldown spliced a {@code "pillar_"} / {@code "adjunct_"}
 * literal prefix into the {@code /dt editor pillar enter <target>} command.
 * The server parser only accepts bare enum names ({@code top}, {@code middle},
 * {@code bottom}, {@code stairs}); the prefixed forms produced
 * {@code "Unknown pillar target 'pillar_middle'"} chat errors and the
 * teleport never happened. Track and tunnel rows go through different command
 * paths and are exercised here as untouched-control assertions.</p>
 */
final class CategoryTemplatesScreenTest {

    @Test
    @DisplayName("PillarModel teleport command uses bare section id, no pillar_ prefix")
    void pillar_top_usesBareId() {
        String command = CategoryTemplatesScreen.trackEnterCommandFor(
            new Template.Pillar(PillarSection.TOP));
        assertEquals("dungeontrain editor pillar enter top", command);
    }

    @Test
    @DisplayName("PillarModel MIDDLE teleport command uses bare section id")
    void pillar_middle_usesBareId() {
        String command = CategoryTemplatesScreen.trackEnterCommandFor(
            new Template.Pillar(PillarSection.MIDDLE));
        assertEquals("dungeontrain editor pillar enter middle", command);
    }

    @Test
    @DisplayName("PillarModel BOTTOM teleport command uses bare section id")
    void pillar_bottom_usesBareId() {
        String command = CategoryTemplatesScreen.trackEnterCommandFor(
            new Template.Pillar(PillarSection.BOTTOM));
        assertEquals("dungeontrain editor pillar enter bottom", command);
    }

    @Test
    @DisplayName("AdjunctModel STAIRS teleport command uses bare adjunct id, no adjunct_ prefix")
    void adjunct_stairs_usesBareId() {
        String command = CategoryTemplatesScreen.trackEnterCommandFor(
            new Template.Adjunct(PillarAdjunct.STAIRS));
        assertEquals("dungeontrain editor pillar enter stairs", command);
    }

    // ---- Untouched-control assertions ----

    @Test
    @DisplayName("TrackModel still routes to /editor track enter (no argument)")
    void track_unchanged() {
        String command = CategoryTemplatesScreen.trackEnterCommandFor(new Template.Track());
        assertEquals("dungeontrain editor track enter", command);
    }

    @Test
    @DisplayName("TunnelModel SECTION still routes to /editor enter tunnel_<variant> (prefixed form)")
    void tunnel_section_keepsPrefixedForm() {
        String command = CategoryTemplatesScreen.trackEnterCommandFor(
            new Template.Tunnel(TunnelVariant.SECTION));
        assertEquals("dungeontrain editor enter tunnel_section", command);
    }

    @Test
    @DisplayName("TunnelModel PORTAL still routes to /editor enter tunnel_<variant>")
    void tunnel_portal_keepsPrefixedForm() {
        String command = CategoryTemplatesScreen.trackEnterCommandFor(
            new Template.Tunnel(TunnelVariant.PORTAL));
        assertEquals("dungeontrain editor enter tunnel_portal", command);
    }

    @Test
    @DisplayName("Unknown Template subtype returns null (defensive default)")
    void unknownTemplate_returnsNull() {
        // PartModel is in the Template sealed hierarchy but isn't a track-side
        // model — trackEnterCommandFor falls through to the null return.
        Template part = new Template.Part(
            games.brennan.dungeontrain.train.CarriagePartKind.FLOOR);
        assertNull(CategoryTemplatesScreen.trackEnterCommandFor(part));
    }
}

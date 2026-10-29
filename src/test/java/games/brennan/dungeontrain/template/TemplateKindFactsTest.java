package games.brennan.dungeontrain.template;

import games.brennan.dungeontrain.editor.EditorTemplateAddress;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.editor.TemplateSidecars;
import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageGroup;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.WholeCarriage;
import games.brennan.dungeontrain.tunnel.TunnelPlacer.TunnelVariant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-kind facts every {@link Template} record answers — plot key, dirty key, snapshot key,
 * category, address — pinned as the literal strings the stores, the dirty scan and the wire use.
 *
 * <p>Written out by hand on purpose: deriving them from the same helper the record calls would
 * pass no matter what either side said. {@link #everyRecordIsSampled} walks the sealed hierarchy
 * so a new kind has to be added here (and to {@link EditorTemplateAddress}'s parser table) before
 * the build is green.</p>
 */
final class TemplateKindFactsTest {

    /** One of each permitted record, by name. */
    static List<Template> samples() {
        List<Template> out = new ArrayList<>();
        out.add(new Template.Carriage(CarriageVariant.custom("brick_cabin")));
        out.add(new Template.Contents(CarriageContents.custom("maze")));
        out.add(new Template.WholeCarriage(new WholeCarriage("lounge")));
        out.add(new Template.WholeHalf(new WholeCarriage("nook")));
        out.add(new Template.CarriageGroup(new CarriageGroup("night_mail")));
        out.add(new Template.Part(CarriagePartKind.FLOOR, "stone"));
        out.add(new Template.Track("sleeperless"));
        out.add(new Template.Pillar(PillarSection.TOP, "oak"));
        out.add(new Template.Adjunct(PillarAdjunct.STAIRS_ENTRANCE, "wide"));
        out.add(new Template.Tunnel(TunnelVariant.PORTAL, "brick"));
        out.add(new Template.PortalRoom("library"));
        out.add(new Template.ChunkFrame("ivy"));
        out.add(new Template.Building("tower"));
        out.add(new Template.LostCity("bank"));
        out.add(new Template.Staged("s1"));
        return out;
    }

    @Test
    @DisplayName("every permitted record has a sample here and a parser for its address type")
    void everyRecordIsSampled() {
        Set<Class<?>> sampled = samples().stream().map(Object::getClass).collect(Collectors.toSet());
        for (Class<?> leaf : leaves(Template.class)) {
            assertTrue(sampled.contains(leaf), "no sample for " + leaf.getSimpleName());
        }
        for (Template t : samples()) {
            String type = t.address().type();
            assertTrue(EditorTemplateAddress.registeredTypes().contains(type),
                    t.getClass().getSimpleName() + " writes address type '" + type + "' but no parser reads it");
        }
    }

    private static List<Class<?>> leaves(Class<?> sealed) {
        List<Class<?>> out = new ArrayList<>();
        for (Class<?> c : sealed.getPermittedSubclasses()) {
            if (c.isSealed()) out.addAll(leaves(c)); else out.add(c);
        }
        return out;
    }

    @Test
    @DisplayName("plot keys are the containers-store spellings")
    void plotKeys() {
        assertEquals("carriage:brick_cabin", key(0));
        assertEquals("contents:maze", key(1));
        assertEquals("whole:lounge", key(2));
        assertEquals("whole_half:nook", key(3));
        assertEquals("whole_group:night_mail", key(4));
        assertEquals("part:floor:stone", key(5));
        assertEquals("track:tile:sleeperless", key(6));
        assertEquals("track:pillar_top:oak", key(7));
        assertEquals("track:adjunct_stairs_entrance:wide", key(8));
        assertEquals("track:tunnel_portal:brick", key(9));
        assertEquals("track:portal_room:library", key(10));
        assertEquals("chunk_frame:ivy", key(11));
        assertEquals(Optional.empty(), samples().get(12).plotKey(), "a building has no containers store");
        assertEquals(Optional.empty(), samples().get(13).plotKey(), "an official building has nothing of the player's");
        assertEquals("workbench:s1", key(14));
    }

    private static String key(int i) {
        return samples().get(i).plotKey().orElseThrow();
    }

    @Test
    @DisplayName("dirty keys are the scan rows' model ids; a group answers its id like its Whole row")
    void dirtyKeys() {
        assertEquals("brick_cabin", dirty(0));
        assertEquals("maze", dirty(1));
        assertEquals("lounge", dirty(2));
        assertEquals("nook", dirty(3));
        assertEquals("night_mail", dirty(4));
        assertEquals(Optional.empty(), samples().get(5).dirtyKey(), "parts are not scanned");
        assertEquals("track.sleeperless", dirty(6));
        assertEquals("pillar_top.oak", dirty(7));
        assertEquals("adjunct_stairs_entrance.wide", dirty(8));
        assertEquals("tunnel_portal.brick", dirty(9));
        assertEquals("portal_room.library", dirty(10));
        assertEquals("chunk_frame.ivy", dirty(11));
        assertEquals("building.tower", dirty(12));
        assertEquals(Optional.empty(), samples().get(13).dirtyKey(), "official buildings are view-only");
        assertEquals("s1", dirty(14));
    }

    private static String dirty(int i) {
        return samples().get(i).dirtyKey().orElseThrow();
    }

    @Test
    @DisplayName("snapshot keys are <category>:<row> as the editors wrote them")
    void snapshotKeys() {
        assertEquals("carriages:brick_cabin", snap(0));
        assertEquals("contents:maze", snap(1));
        assertEquals("whole:lounge", snap(2));
        assertEquals("whole_half:nook", snap(3));
        assertEquals("whole_group:night_mail", snap(4));
        assertEquals(Optional.empty(), samples().get(5).snapshotKey());
        assertEquals("tracks:track:sleeperless", snap(6));
        assertEquals("tracks:pillar_top:oak", snap(7));
        assertEquals("tracks:adjunct_stairs_entrance:wide", snap(8));
        assertEquals("tracks:tunnel_portal:brick", snap(9));
        assertEquals("portals:portal_room:library", snap(10));
        assertEquals("chunk_frames:chunk_frame:ivy", snap(11));
        assertEquals("buildings:building:tower", snap(12));
        assertEquals(Optional.empty(), samples().get(13).snapshotKey());
        assertEquals("workbench:s1", snap(14));
    }

    private static String snap(int i) {
        return samples().get(i).snapshotKey().orElseThrow();
    }

    @Test
    @DisplayName("categories and track kinds")
    void categoriesAndTrackKinds() {
        PlotCategory[] expected = {PlotCategory.CARRIAGES, PlotCategory.CONTENTS, PlotCategory.WHOLE,
                PlotCategory.WHOLE_HALF, PlotCategory.WHOLE_GROUP, PlotCategory.PARTS, PlotCategory.TRACKS,
                PlotCategory.TRACKS, PlotCategory.TRACKS, PlotCategory.TRACKS, PlotCategory.PORTALS,
                PlotCategory.CHUNK_FRAMES, PlotCategory.BUILDINGS, PlotCategory.LOST_CITY, PlotCategory.WORKBENCH};
        List<Template> s = samples();
        for (int i = 0; i < s.size(); i++) assertEquals(expected[i], s.get(i).plotCategory(), s.get(i).displayName());
        assertEquals(Optional.of(TrackKind.TILE), s.get(6).trackKind());
        assertEquals(Optional.of(TrackKind.PILLAR_TOP), s.get(7).trackKind());
        assertEquals(Optional.of(TrackKind.ADJUNCT_STAIRS_ENTRANCE), s.get(8).trackKind());
        assertEquals(Optional.of(TrackKind.TUNNEL_PORTAL), s.get(9).trackKind());
        assertEquals(Optional.of(TrackKind.PORTAL_ROOM), s.get(10).trackKind());
        assertEquals(Optional.empty(), s.get(0).trackKind());
        assertEquals("tracks:pillar_top:iron", ((Template.TrackSide) s.get(7)).withName("iron").snapshotKey().orElseThrow());
    }

    @Test
    @DisplayName("sidecar lists end with the containers store, keyed by the plot key, where there is one")
    void sidecarsEndWithContainers() {
        for (Template t : samples()) {
            List<TemplateSidecars.Sidecar> files = t.sidecars();
            if (t.plotKey().isEmpty() || t instanceof Template.Staged) {
                assertTrue(files.stream().noneMatch(f -> f.role().equals(TemplateSidecars.ROLE_CONTAINERS)), t.displayName());
                continue;
            }
            assertEquals(TemplateSidecars.ROLE_CONTAINERS, files.get(files.size() - 1).role(), t.displayName());
        }
    }

    @Test
    @DisplayName("addresses are the wire triples, and the registry-free kinds round-trip")
    void addresses() {
        assertEquals(new EditorTemplateAddress("carriage", "", "brick_cabin"), samples().get(0).address());
        assertEquals(new EditorTemplateAddress("part", "floor", "stone"), samples().get(5).address());
        assertEquals(new EditorTemplateAddress("pillar", "top", "oak"), samples().get(7).address());
        assertEquals(new EditorTemplateAddress("adjunct", "stairs_entrance", "wide"), samples().get(8).address());
        assertEquals(new EditorTemplateAddress("tunnel", "portal", "brick"), samples().get(9).address());
        assertEquals(new EditorTemplateAddress("portal_room", "", "library"), samples().get(10).address());
        assertEquals(new EditorTemplateAddress("staged", "", "s1"), samples().get(14).address());
        for (int i : new int[] {5, 6, 7, 8, 9, 10}) {
            Template t = samples().get(i);
            assertEquals(Optional.of(t), t.address().resolve(), t.displayName());
        }
    }
}

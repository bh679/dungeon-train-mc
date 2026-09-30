package games.brennan.dungeontrain.tunnel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tunnel editor plots laid out by group — {@link TunnelPlotOrder}. */
final class TunnelPlotOrderTest {

    @Test
    @DisplayName("a group's entrances stand over its sections; runs are as wide as the larger side")
    void groupsLineUp() {
        Map<String, List<String>> sectionGroups = Map.of(
            "default", List.of("stone"), "overgrowth", List.of("stone", "mossy"),
            "copper", List.of("copper"), "mud", List.of("mud"), "loose", List.of());
        Map<String, List<String>> portalGroups = Map.of(
            "default", List.of("stone"), "fancy", List.of("stone"), "arch", List.of("stone"),
            "copper", List.of("copper"), "brassish", List.of("mud"));
        TunnelPlotOrder.Layout layout = TunnelPlotOrder.layout(
            List.of("default", "overgrowth", "copper", "mud", "loose"), sectionGroups::get,
            List.of("default", "fancy", "arch", "copper", "brassish"), portalGroups::get);

        // copper: 1 wide at column 0.
        assertEquals(0, layout.sections().get("copper"));
        assertEquals(0, layout.portals().get("copper"));
        // overgrowth's first group is mossy, a run of its own at column 1 (no entrances there).
        assertEquals(1, layout.sections().get("overgrowth"));
        // mud at column 2, over its brassish entrance.
        assertEquals(2, layout.sections().get("mud"));
        assertEquals(2, layout.portals().get("brassish"));
        // stone: default first, then name order — three entrances, one section → 3 wide from 3.
        assertEquals(3, layout.sections().get("default"));
        assertEquals(3, layout.portals().get("default"));
        assertEquals(4, layout.portals().get("arch"));
        assertEquals(5, layout.portals().get("fancy"));
        // Ungrouped last, after stone's full run.
        assertEquals(6, layout.sections().get("loose"));
    }
}

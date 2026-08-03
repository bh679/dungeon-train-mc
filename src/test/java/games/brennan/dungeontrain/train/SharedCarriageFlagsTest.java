package games.brennan.dungeontrain.train;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedCarriageFlagsTest {

    @Test
    void fromJsonReadsSharedArrayAndSkipsBlanks() {
        JsonObject o = new JsonObject();
        JsonArray a = new JsonArray();
        a.add("shared");
        a.add("black");
        a.add("");        // blank skipped
        a.add("shared");  // dupe collapses in the set
        o.add("shared", a);
        Set<String> s = SharedCarriageFlags.fromJson(o);
        assertTrue(s.contains("shared"));
        assertTrue(s.contains("black"));
        assertFalse(s.contains(""));
    }

    @Test
    void recordNormalisesToLowercaseAndQueryIsCaseInsensitive() {
        SharedCarriageFlags f = new SharedCarriageFlags(Set.of("Shared", "STANDARD", "  "));
        assertTrue(f.isShared("shared"));
        assertTrue(f.isShared("SHARED"));   // query lower-cased
        assertTrue(f.isShared("standard"));
        assertFalse(f.isShared("windowed"));
        assertFalse(f.isShared(null));
    }

    @Test
    void jsonRoundTripPreservesTheSet() {
        SharedCarriageFlags f = new SharedCarriageFlags(Set.of("shared", "black"));
        JsonObject o = SharedCarriageFlags.toJson(f.ids());
        Set<String> back = SharedCarriageFlags.fromJson(o);
        assertEquals(f.ids(), new SharedCarriageFlags(back).ids());
    }

    @Test
    void emptyJsonYieldsEmptySet() {
        assertTrue(SharedCarriageFlags.fromJson(new JsonObject()).isEmpty());
    }
}

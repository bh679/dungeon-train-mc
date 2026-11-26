package games.brennan.dungeontrain.compat.mixinguard;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Every listed readout hook is gated on a real mod id, or it would apply (or never apply) by accident. */
class PositionReadoutMixinPluginTest {

    private static final String PLUGIN = "games.brennan.dungeontrain.mixin.PositionReadoutMixinPlugin";

    @Test
    @DisplayName("each mixin in the config is gated on its mod and exists")
    void everyMixinHasAModAndAClass() throws Exception {
        JsonObject config;
        try (var in = getClass().getResourceAsStream("/dungeontrain.positionreadout.mixins.json")) {
            assertNotNull(in, "config missing from resources");
            config = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        assertEquals(PLUGIN, config.get("plugin").getAsString());
        for (JsonElement e : config.getAsJsonArray("client")) {
            String name = config.get("package").getAsString() + "." + e.getAsString();
            assertNotNull(PositionReadoutMods.modFor(name), name + " has no mod gate");
            assertNotNull(getClass().getClassLoader().getResource(name.replace('.', '/') + ".class"),
                name + " is listed but not compiled");
        }
    }

    @Test
    @DisplayName("mod ids are the real ones the loader reports")
    void realModIds() {
        String p = "games.brennan.dungeontrain.mixin.client.positionreadout.";
        assertEquals("sodium_extra", PositionReadoutMods.modFor(p + "sodiumextra.X"));
        assertEquals("xaerominimap", PositionReadoutMods.modFor(p + "xaerominimap.X"));
        assertEquals("xaeroworldmap", PositionReadoutMods.modFor(p + "xaeroworldmap.X"));
        assertEquals("betterf3", PositionReadoutMods.modFor(p + "betterf3.X"));
        assertEquals("jade", PositionReadoutMods.modFor(p + "jade.X"));
        assertNull(PositionReadoutMods.modFor(p + "unknown.X"));
        assertNull(PositionReadoutMods.modFor("games.brennan.dungeontrain.mixin.client.Other"));
    }
}

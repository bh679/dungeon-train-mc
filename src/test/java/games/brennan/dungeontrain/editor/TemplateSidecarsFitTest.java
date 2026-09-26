package games.brennan.dungeontrain.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import games.brennan.dungeontrain.builder.relay.BuildCredits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a sidecar document is made to fit the relay's field.
 *
 * <p>Pinned because the failure it guards against is silent at both ends: a portal room whose
 * variants file was over the cap used to upload with <b>no</b> sidecars at all, so its chests arrived
 * on every other install unlinked from their loot while looking complete on the author's.</p>
 */
final class TemplateSidecarsFitTest {

    private static final String CONTAINERS =
            "{\n  \"schemaVersion\": 3,\n  \"links\": {\n    \"4,1,7\": \"bows\"\n  }\n}\n";

    /** Pretty-printed and repetitive, like a real variants file — compresses well. */
    private static String repetitiveVariants(int approxChars) {
        StringBuilder sb = new StringBuilder("{\n  \"entries\": [\n");
        int i = 0;
        while (sb.length() < approxChars) {
            sb.append("    { \"pos\": \"").append(i % 50).append(',').append(i / 50 % 30)
                    .append(",0\", \"states\": [\"minecraft:stone_bricks\", \"minecraft:mossy_stone_bricks\"] },\n");
            i++;
        }
        return sb.append("  ]\n}\n").toString();
    }

    /** Random base64 — gzip cannot shrink it, so only shedding makes it fit. */
    private static String incompressible(int chars) {
        byte[] raw = new byte[chars * 3 / 4];
        new Random(42).nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    private static Map<String, String> files(String... roleAndText) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < roleAndText.length; i += 2) out.put(roleAndText[i], roleAndText[i + 1]);
        return out;
    }

    @Test
    @DisplayName("a small document stays plain JSON — byte-identical to what uploads sent before")
    void smallStaysPlain() {
        TemplateSidecars.Collected c = TemplateSidecars.fit("room",
                files("variants", "{}", TemplateSidecars.ROLE_CONTAINERS, CONTAINERS), null, null);
        assertTrue(c.doc().startsWith("{\"files\":"), c.doc());
        assertTrue(c.dropped().isEmpty());
    }

    @Test
    @DisplayName("an oversized portal room compresses, and the chests' loot links survive the round trip")
    void oversizedCompressesAndRoundTrips() {
        String variants = repetitiveVariants(700_000);
        TemplateSidecars.Collected c = TemplateSidecars.fit("doors",
                files("variants", variants, TemplateSidecars.ROLE_CONTAINERS, CONTAINERS), null, null);

        assertTrue(c.dropped().isEmpty(), "nothing needed shedding: " + c.dropped());
        assertTrue(c.doc().length() <= TemplateSidecars.MAX_DOC_CHARS);
        assertTrue(c.doc().startsWith("{\"" + SidecarDocCodec.K_GZ + "\":"), "expected the compressed envelope");
        assertEquals(Optional.of(CONTAINERS), TemplateSidecars.containersTextOf(c.doc()));
    }

    @Test
    @DisplayName("the byline is read through a compressed envelope too")
    void creditReadThroughEnvelope() {
        JsonElement credit = BuildCredits.encode(
                new BuildCredits.Credit("e79d74abff784cf8b96fa36e343dfcef", "AntonioMacarely", 1L));
        TemplateSidecars.Collected c = TemplateSidecars.fit("doors",
                files("variants", repetitiveVariants(400_000)), null, credit);
        assertTrue(c.doc().startsWith("{\"" + SidecarDocCodec.K_GZ + "\":"));
        assertTrue(TemplateSidecars.hasCredit(c.doc()));
    }

    @Test
    @DisplayName("when even compression is not enough, the largest file goes and the loot links stay")
    void shedsLargestNeverContainersFirst() {
        TemplateSidecars.Collected c = TemplateSidecars.fit("doors",
                files("variants", incompressible(400_000), "copies", "{\"a\":1}",
                        TemplateSidecars.ROLE_CONTAINERS, CONTAINERS),
                null, null);

        assertEquals(List.of("variants"), c.dropped());
        assertFalse(c.lostContainers());
        assertEquals(Optional.of(CONTAINERS), TemplateSidecars.containersTextOf(c.doc()));
    }

    @Test
    @DisplayName("containers alone too big is the only way the loot is lost — and it is reported")
    void containersDroppedLastAndReported() {
        TemplateSidecars.Collected c = TemplateSidecars.fit("doors",
                files("variants", "{}", TemplateSidecars.ROLE_CONTAINERS, incompressible(400_000)),
                new JsonObject(), null);

        assertEquals(List.of("variants", TemplateSidecars.ROLE_CONTAINERS), c.dropped(),
                "everything else is shed before the containers store");
        assertEquals("{\"weights\":{}}", c.doc(), "the weights entry still travels");
        assertTrue(c.lostContainers());
        assertEquals(Optional.empty(), TemplateSidecars.containersTextOf(c.doc()));
    }

    @Test
    @DisplayName("a garbage or bomb-sized envelope reads as carrying nothing, never throws out of the readers")
    void badEnvelopeIsEmpty() throws Exception {
        assertEquals(Optional.empty(), TemplateSidecars.containersTextOf("{\"gz\":\"not base64!!\"}"));
        assertFalse(TemplateSidecars.hasCredit("{\"gz\":\"AAAA\"}"));

        String bomb = SidecarDocCodec.compress(" ".repeat(SidecarDocCodec.MAX_INFLATED_BYTES + 1));
        assertThrows(Exception.class, () -> SidecarDocCodec.parse(bomb));
        assertEquals(Optional.empty(), TemplateSidecars.containersTextOf(bomb));
    }
}

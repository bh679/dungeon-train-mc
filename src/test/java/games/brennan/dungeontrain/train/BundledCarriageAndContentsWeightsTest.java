package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.portal.PortalCorridorKind;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.portal.PortalCarriageBuilder;
import games.brennan.dungeontrain.train.CarriagePlacer.CarriageType;
import games.brennan.dungeontrain.util.BundledNbtScanner;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the shipped <b>carriage</b> and <b>contents</b> {@code weights.json} files against keys
 * that name no variant — the two registries
 * {@link games.brennan.dungeontrain.track.variant.BundledTrackWeightsTest} leaves out because they
 * are not {@code TrackKind}s.
 *
 * <h2>Why this needs its own test</h2>
 * <p>The failure mode is the one that test documents: a weights entry is only ever read <i>from the
 * variant side</i> — {@code CarriageWeights.weightFor/gateFor/stageIdFor} are each a lookup <b>by an
 * id the registry already has</b> (every call site passes {@code variant.id()}). Nothing walks the
 * map looking for keys that match nothing, so an entry for a variant that does not exist is loaded,
 * never read, and never mentioned. That is how {@code fancy} (added as an explicit "placeholder for
 * future template" that never arrived), {@code open} (a wall <i>part</i> name — see
 * {@code fancywood.parts.json} — never a carriage) and {@code shop} (a <i>contents</i> name, already
 * correctly weighted in {@code contents/weights.json}) each sat in the carriage file for months.</p>
 *
 * <h2>Why a bare .nbt scan is the wrong rule here</h2>
 * <p>Neither registry's name set is "the {@code .nbt} basenames in its directory", so this test
 * reconstructs each one from the same sources the registry itself uses:</p>
 * <ul>
 *   <li><b>Carriages</b> ({@link CarriageVariantRegistry}) — the {@link CarriageType} built-ins,
 *       plus the bundled {@code .nbt} scan, plus the portal names the code registers on its own.
 *       {@code portal} is force-added by {@link CarriageVariantRegistry#reload()}; {@code portal_middle}
 *       ships sidecars and no {@code .nbt}, and registers via the config-dir scan the moment the
 *       editor saves {@code user/templates/portal_middle.nbt} — at which point its weight of 0 is
 *       what keeps it out of the ordinary random pool. Both are read off
 *       {@link PortalCarriageBuilder} rather than spelled out here, so a rename there cannot
 *       silently orphan the entries.</li>
 *   <li><b>Contents</b> ({@link CarriageContentsRegistry}) — the {@link CarriageContents.ContentsType}
 *       built-ins, plus <b>both</b> halves of that registry's bundled scan: {@code .nbt} basenames
 *       <i>and</i> {@code .group.json} basenames. A group parent is a real contents name with no
 *       template file of its own, so checking {@code .nbt} alone would report false orphans.</li>
 * </ul>
 *
 * <p>Only the <b>bundled</b> classpath resources are checked. The per-install config tier
 * deliberately tolerates unknown ids — {@link CarriageWeights} documents that as the way an admin
 * pre-registers weights for customs they have not installed yet — and that affordance is untouched
 * by this test.</p>
 */
final class BundledCarriageAndContentsWeightsTest {

    private static final Logger LOGGER =
        LoggerFactory.getLogger(BundledCarriageAndContentsWeightsTest.class);

    /**
     * {@link PortalCarriageBuilder} holds {@code Blocks.*} constants in static fields, so touching
     * it needs the registries up. Same pattern as
     * {@code games.brennan.dungeontrain.track.variant.TrackVariantBlocksCopyTest}.
     */
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("every shipped carriage weights.json key names a real carriage variant")
    void everyCarriageWeightsKeyResolvesToAVariant() {
        Set<String> names = carriageNames();
        assertScanReached(names, CarriageVariantRegistry.BUNDLED_RESOURCE_PREFIX);
        assertNoOrphans(CarriageWeights.BUNDLED_RESOURCE, names, "carriage variant");
    }

    @Test
    @DisplayName("every shipped contents weights.json key names a real contents variant")
    void everyContentsWeightsKeyResolvesToAVariant() {
        Set<String> names = contentsNames();
        assertScanReached(names, CarriageContentsRegistry.BUNDLED_RESOURCE_PREFIX);
        assertNoOrphans(CarriageContentsWeights.BUNDLED_RESOURCE, names, "contents variant");
    }

    @Test
    @DisplayName("both shipped weights.json files are JSON objects")
    void bothWeightsFilesAreObjects() {
        for (String resource : List.of(CarriageWeights.BUNDLED_RESOURCE,
                                       CarriageContentsWeights.BUNDLED_RESOURCE)) {
            JsonElement root = readRoot(resource);
            assertNotNull(root, resource + " must ship and must parse");
            // A non-object root makes loadInto drop the whole file with a warning, quietly
            // returning every variant in the registry to its default weight and gate.
            assertTrue(root.isJsonObject(), resource + " must be a JSON object");
        }
    }

    /** Every id {@link CarriageVariantRegistry} can hold from sources that ship in the jar. */
    private static Set<String> carriageNames() {
        TreeSet<String> names = new TreeSet<>();
        for (CarriageType t : CarriageType.values()) names.add(t.id());
        names.addAll(BundledNbtScanner.scanBasenames(
            BundledCarriageAndContentsWeightsTest.class,
            CarriageVariantRegistry.BUNDLED_RESOURCE_PREFIX, LOGGER));
        // Registered by code rather than discovered by file — see this class's javadoc. Both
        // corridor kinds, since CarriageVariantRegistry.reload force-adds each.
        for (PortalCorridorKind kind : PortalCorridorKind.values()) {
            names.add(PortalCarriageBuilder.portalVariant(kind).id());
        }
        names.add(PortalCarriageBuilder.middleVariant().id());
        return names;
    }

    /** Every id {@link CarriageContentsRegistry} can hold from sources that ship in the jar. */
    private static Set<String> contentsNames() {
        TreeSet<String> names = new TreeSet<>();
        for (CarriageContents.ContentsType t : CarriageContents.ContentsType.values()) {
            names.add(t.id());
        }
        names.addAll(BundledNbtScanner.scanBasenames(
            BundledCarriageAndContentsWeightsTest.class,
            CarriageContentsRegistry.BUNDLED_RESOURCE_PREFIX, LOGGER));
        // Group parents are real names with no template file of their own.
        names.addAll(BundledNbtScanner.scanBasenames(
            BundledCarriageAndContentsWeightsTest.class,
            CarriageContentsRegistry.BUNDLED_RESOURCE_PREFIX, LOGGER, ".group.json"));
        // Both corridors' contents are force-added by CarriageContentsRegistry.reload for the same
        // reason their shells are: the plot has to exist before the .nbt authored in it does.
        for (PortalCorridorKind kind : PortalCorridorKind.values()) {
            names.add(PortalCarriageBuilder.portalContents(kind).id());
        }
        return names;
    }

    /**
     * Check the scan before trusting it. An unresolvable resources directory returns an empty set
     * rather than throwing, which would turn every key into a bogus orphan — a broken test
     * masquerading as broken data.
     */
    private static void assertScanReached(Set<String> names, String prefix) {
        assertTrue(names.size() > CarriageType.values().length,
            "the bundled scan of " + prefix + " found nothing beyond the built-in ids. This is far "
                + "more likely a classpath-resolution failure in BundledNbtScanner under the test "
                + "runtime than a genuinely empty template directory — check that "
                + "src/main/resources is on the test classpath before editing any data.");
    }

    private static void assertNoOrphans(String resource, Set<String> names, String what) {
        JsonElement root = readRoot(resource);
        assertNotNull(root, resource + " must ship and must parse");
        assertTrue(root.isJsonObject(), resource + " must be a JSON object");
        JsonObject weights = root.getAsJsonObject();
        assertTrue(!weights.keySet().isEmpty(), resource + " reached the test classpath but is empty");

        List<String> orphans = new ArrayList<>();
        for (String rawKey : weights.keySet()) {
            // loadInto lowercases keys on the way in and every lookup lowercases the id, so match
            // the same way: case is the only normalisation.
            if (names.contains(rawKey.toLowerCase(Locale.ROOT))) continue;
            orphans.add(rawKey);
        }

        assertTrue(orphans.isEmpty(),
            "weights entries in " + resource + " naming no " + what + " — each is dead config that "
                + "applies to nothing, while the variant it was probably meant for silently falls "
                + "through to the default weight and gate. Either delete the entry or re-key it to "
                + "the real name:\n    " + String.join("\n    ", orphans));
    }

    /** The parsed root of the bundled resource at {@code resource}, or null when it is absent. */
    private static JsonElement readRoot(String resource) {
        try (InputStream in = BundledCarriageAndContentsWeightsTest.class.getResourceAsStream(resource)) {
            if (in == null) return null;
            try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(r);
            }
        } catch (Exception e) {
            throw new AssertionError("bundled " + resource + " failed to read: " + e, e);
        }
    }
}

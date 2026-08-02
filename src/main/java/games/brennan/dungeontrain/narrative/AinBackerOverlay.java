package games.brennan.dungeontrain.narrative;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.NameChain;
import games.brennan.adventureitemnames.api.NamePool;
import games.brennan.adventureitemnames.api.NameSegment;
import games.brennan.adventureitemnames.api.NamingConfig;
import games.brennan.adventureitemnames.internal.NameRegistry;
import games.brennan.adventureitemnames.internal.SegmentOverrides;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Injects the backer name pool ({@link BackerPool}) into AdventureItemNames, so a backer's name —
 * and optionally a short phrase they authored — turns up in generated item names, mob names,
 * PlayerMob names and NPC dialogue.
 *
 * <h2>Why {@code NamingConfig.addEntry} and not {@code putPoolInMemory}</h2>
 *
 * <p>{@code NameRegistry.putPoolInMemory} REPLACES a pool wholesale, so using it to add backers to
 * AIN's shipped {@code adventureitemnames:discord_people} would delete the community names already
 * in it. {@link NamingConfig#addEntry} instead writes to AIN's API override layer, which is
 * additive, kept separate from the player's own config edits, consulted on every roll via
 * {@code effectivePoolEntries}, and never cleared by a datapack reload. {@code clearEntryOverride}
 * touches only that layer, so re-pushing a refreshed pool is safe and idempotent.</p>
 *
 * <h2>Why the chain refs are wired at RUNTIME rather than shipped as JSON</h2>
 *
 * <p>This is the subtle one. AIN's composer skips a whole segment when its chosen ref resolves to
 * an empty string:</p>
 *
 * <pre>if (fragment == null || fragment.isEmpty()) continue;</pre>
 *
 * <p>That is good — it means an empty backer pool can never produce a dangling "Forged by " — but
 * it also means a <em>statically shipped</em> chain layer adding a backer ref at weight W would
 * silently delete that segment W/(W+total) of the time whenever the pool is empty. Empty is the
 * normal state: offline players, an unreachable relay, a first launch before the fetch lands. So
 * DT would be degrading naming for everyone in order to serve a handful of backers.</p>
 *
 * <p>Instead the ref is added only while the pool is non-empty, through
 * {@link NamingConfig#overrideSegment} (API layer, precedence API → user → shipped), and removed
 * again via {@code clearSegmentOverride} the moment it empties — restoring AIN's shipped behaviour
 * exactly.</p>
 *
 * <h2>Why segments are located by their existing {@code discord_people} ref</h2>
 *
 * <p>An override is keyed by (chain id, segment index), and hardcoding indices would couple DT to
 * AIN's internal chain layout and break silently on an AIN bump that reorders segments. Instead
 * each chain is scanned for the segment that already references AIN's community-names pool. That
 * targets exactly the right surfaces by construction, self-heals across AIN versions, and simply
 * finds nothing (a no-op) if AIN ever restructures those chains.</p>
 *
 * <p>Server-side, like the rest of naming. Everything here is no-throw: any failure leaves AIN
 * untouched and naming exactly as it would have been without the feature.</p>
 */
public final class AinBackerOverlay {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String AIN_NS = "adventureitemnames";
    private static final String DT_NS = "dungeontrain";

    /** AIN's shipped community-names pool. Used as the beacon for locating the right segments. */
    static final ResourceLocation DISCORD_PEOPLE =
            ResourceLocation.fromNamespaceAndPath(AIN_NS, "discord_people");

    /** DT's own pools, shipped empty and filled at runtime. See {@code data/dungeontrain/naming/pools/}. */
    public static final ResourceLocation BACKERS_POOL =
            ResourceLocation.fromNamespaceAndPath(DT_NS, "backers");
    public static final ResourceLocation BACKER_PHRASES_POOL =
            ResourceLocation.fromNamespaceAndPath(DT_NS, "backer_phrases");

    /**
     * Chains whose backer-bearing segment carries a NAME. These are the four surfaces a name reads
     * naturally in: item titles, mob names, PlayerMob names, and the "of X" title construction.
     */
    static final List<ResourceLocation> NAME_CHAINS = List.of(
            ResourceLocation.fromNamespaceAndPath(AIN_NS, "weapon_name_full"),
            ResourceLocation.fromNamespaceAndPath(AIN_NS, "title_combinations"),
            ResourceLocation.fromNamespaceAndPath(AIN_NS, "mob_name"),
            ResourceLocation.fromNamespaceAndPath(AIN_NS, "playermob_name"));

    /**
     * Chains that read as lore / NPC dialogue, where an authored PHRASE fits. A phrase is
     * sentence-shaped and would read badly inside an item name that already carries two or three
     * other segments, so it deliberately does not go into {@link #NAME_CHAINS}.
     */
    static final List<ResourceLocation> PHRASE_CHAINS = List.of(
            ResourceLocation.fromNamespaceAndPath(AIN_NS, "forged_by"),
            ResourceLocation.fromNamespaceAndPath(AIN_NS, "looks_like_something"),
            ResourceLocation.fromNamespaceAndPath(AIN_NS, "do_you_know_x"));

    /** Segment overrides currently installed, so they can be removed precisely. */
    private static final List<SegmentKey> installed = new ArrayList<>();

    private record SegmentKey(ResourceLocation chainId, int index) {}

    private AinBackerOverlay() {}

    /**
     * Push {@code snapshot} into AIN, replacing whatever was pushed before. An empty snapshot fully
     * stands the overlay down, leaving AIN byte-identical to a build without this feature.
     */
    public static synchronized void apply(BackerPool.Snapshot snapshot) {
        try {
            BackerPool.Snapshot snap = snapshot == null ? BackerPool.Snapshot.EMPTY : snapshot;
            clearSegmentOverrides();
            fillPool(BACKERS_POOL, snap.names());
            fillPool(BACKER_PHRASES_POOL, snap.phrases());
            // Only wire a ref once its pool actually has something to yield — see the class javadoc.
            if (!snap.names().isEmpty()) wireRef(NAME_CHAINS, BACKERS_POOL);
            if (!snap.phrases().isEmpty()) wireRef(PHRASE_CHAINS, BACKER_PHRASES_POOL);
            LOGGER.debug("[DungeonTrain] backer overlay: {} names, {} phrases, {} segments wired",
                    snap.names().size(), snap.phrases().size(), installed.size());
        } catch (Throwable t) {
            // Naming is cosmetic; never let it break a world.
            LOGGER.warn("[DungeonTrain] backer overlay failed — leaving AIN naming untouched: {}", t.toString());
        }
    }

    /** Stand the overlay down entirely (server shutdown, or an empty pool). */
    public static synchronized void clear() {
        apply(BackerPool.Snapshot.EMPTY);
    }

    /** Replace DT's API-layer entries for one pool. Clearing first keeps repeat pushes idempotent. */
    private static void fillPool(ResourceLocation poolId, List<String> texts) {
        NamingConfig.clearEntryOverride(poolId);
        for (String text : texts) {
            NamingConfig.addEntry(poolId, NamePool.PoolEntry.universal(text));
        }
    }

    /**
     * Add {@code poolId} as a weighted ref on every segment of {@code chainIds} that already
     * references AIN's community-names pool.
     */
    private static void wireRef(List<ResourceLocation> chainIds, ResourceLocation poolId) {
        float weight = backerWeight();
        // Weight 0 is the operator switching the feature off. Skip installing the override entirely
        // rather than adding a zero-weight ref, so AIN's segments stay untouched.
        if (weight <= 0f) return;
        for (ResourceLocation chainId : chainIds) {
            NameChain chain = NameRegistry.chain(chainId).orElse(null);
            if (chain == null) continue;
            List<NameSegment> segments = chain.segments();
            for (int i : targetSegments(segments)) {
                List<NameSegment.WeightedRef> refs = withRef(segments.get(i).refs(), poolId, weight);
                NamingConfig.overrideSegment(chainId, i,
                        SegmentOverrides.SegmentEdit.empty().withRefs(refs));
                installed.add(new SegmentKey(chainId, i));
            }
        }
    }

    /**
     * Indices of the segments in {@code segments} that already reference AIN's community-names pool
     * — the ones a backer string belongs alongside. Pure; unit-tested.
     *
     * <p>Returning indices (rather than hardcoding them) is what keeps DT decoupled from AIN's chain
     * layout: an AIN release that reorders, inserts or removes segments moves the match with it, and
     * one that drops the pool from a chain simply yields no match and no override.</p>
     */
    static List<Integer> targetSegments(List<NameSegment> segments) {
        List<Integer> out = new ArrayList<>();
        if (segments == null) return out;
        for (int i = 0; i < segments.size(); i++) {
            NameSegment seg = segments.get(i);
            if (seg == null || seg.refs() == null) continue;
            for (NameSegment.WeightedRef ref : seg.refs()) {
                if (ref != null && DISCORD_PEOPLE.equals(ref.ref())) {
                    out.add(i);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * {@code shipped} plus one more weighted ref. AIN's segment override REPLACES the refs list
     * rather than merging, so the shipped refs must be carried across or the segment would lose
     * every option it had. Pure; unit-tested.
     */
    static List<NameSegment.WeightedRef> withRef(List<NameSegment.WeightedRef> shipped,
                                                 ResourceLocation poolId, float weight) {
        List<NameSegment.WeightedRef> refs = new ArrayList<>(shipped == null ? List.of() : shipped);
        refs.add(new NameSegment.WeightedRef(poolId, weight));
        return List.copyOf(refs);
    }

    /** Remove every segment override we installed, restoring AIN's shipped refs. */
    private static void clearSegmentOverrides() {
        for (SegmentKey key : installed) {
            NamingConfig.clearSegmentOverride(key.chainId(), key.index());
        }
        installed.clear();
    }

    /**
     * How heavily a backer string is weighted against the other refs in the same segment. Config so
     * backer frequency is tunable independently of AIN's community pool — the whole reason DT uses
     * its own pool id rather than injecting into {@code discord_people}.
     */
    private static float backerWeight() {
        try {
            return (float) DungeonTrainCommonConfig.getBackerNameWeight();
        } catch (Throwable t) {
            return (float) DungeonTrainCommonConfig.DEFAULT_BACKER_NAME_WEIGHT;
        }
    }

    /**
     * Strip a relay-supplied string to something safe to bake into a Minecraft component, and clamp
     * it to {@code maxLen}. Pure — unit-tested without Minecraft.
     *
     * <p>AIN applies NO validation on the programmatic path (its 256-char cap covers only the JSON
     * config path), so this is the only guard. Removes the section sign, Minecraft's legacy
     * formatting escape: {@code Component.literal} does not interpret it, but several downstream
     * renderers and chat paths do. Removes every other control character too — a newline would
     * split item lore onto extra lines and put a literal control character in an item name.</p>
     */
    public static String sanitize(String raw, int maxLen) {
        if (raw == null) return "";
        StringBuilder stripped = new StringBuilder(raw.length());
        raw.codePoints().forEach(cp -> {
            if (cp == '§' || cp < 0x20 || cp == 0x7f) return;
            stripped.appendCodePoint(cp);
        });
        // Trim BEFORE clamping so leading whitespace can't eat the length budget, and again after
        // in case the clamp landed mid-space.
        String trimmed = stripped.toString().trim();
        if (trimmed.length() > maxLen) trimmed = trimmed.substring(0, maxLen);
        return trimmed.trim();
    }

    /** Tests only: how many segment overrides are currently installed. */
    static int installedCount() {
        return installed.size();
    }
}

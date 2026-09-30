package games.brennan.dungeontrain.tunnel;

import games.brennan.dungeontrain.editor.TrackVariantGroupStore;
import games.brennan.dungeontrain.template.GateContext;
import games.brennan.dungeontrain.template.SeededDraw;
import games.brennan.dungeontrain.template.TemplateGroup;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Which template group a tunnel is built from — rolled once per tunnel, then handed to every
 * section and entrance stamp between its two ends (see {@link TunnelRunGroups}).
 *
 * <p>A group can only win where its own spawn gate allows (see {@link TunnelGroupStore.Registry#gateOf})
 * and where it could actually build a whole tunnel: it needs at least one
 * section <b>and</b> one entrance template the {@link GateContext} allows. So a Nether-only group
 * is never rolled in the Overworld, and a group missing its entrance is never rolled at all. The
 * implicit {@link TemplateGroup#UNGROUPED} pool competes on the same terms at
 * {@link TunnelGroupStore.Registry#ungroupedWeight()}.</p>
 *
 * <p>The draw is a pure function of {@code (worldSeed, key)} — the key being the tunnel's entrance X
 * (or a coarse fallback band) — so the same tunnel rolls the same group on every server.</p>
 */
public final class TunnelGroupRoll {

    /** Decorrelates this lottery from every other {@link SeededDraw} user. */
    private static final long SALT = 0x54554E4E454C4752L; // "TUNNELGR"

    private TunnelGroupRoll() {}

    /** One group a tunnel could roll, at its roll weight. */
    public record Candidate(TemplateGroup group, int weight) {}

    /**
     * Roll the group for the tunnel keyed by {@code key}. Returns {@code null} when there is nothing
     * to choose between — no candidate can build a tunnel here, or every candidate weighs 0 — which
     * callers treat as "no group filter", i.e. exactly the pre-groups pick.
     */
    public static TemplateGroup roll(long worldSeed, long key, GateContext gateCtx) {
        return pick(SeededDraw.hash(worldSeed ^ SALT, key), candidates(gateCtx, TunnelGroupStore.current()));
    }

    /**
     * Roll among only the groups a given template belongs to ({@code memberOf}; empty = the ungrouped
     * pool) — for a test tunnel built around that template. Prefers groups that can build a whole
     * tunnel here, weighted as {@link #roll}; if none can, the first membership still wins so the
     * tested template's set is used as far as it goes.
     */
    public static TemplateGroup rollAmong(long worldSeed, long key, GateContext gateCtx, List<String> memberOf) {
        if (memberOf == null || memberOf.isEmpty()) return TemplateGroup.UNGROUPED;
        List<Candidate> eligible = new ArrayList<>();
        for (Candidate c : candidates(gateCtx, TunnelGroupStore.current())) {
            if (!c.group().isUngrouped() && memberOf.contains(c.group().id())) eligible.add(c);
        }
        TemplateGroup picked = pick(SeededDraw.hash(worldSeed ^ SALT, key), eligible);
        return picked != null ? picked : TemplateGroup.of(memberOf.get(0));
    }

    /** Every group able to build a tunnel under {@code gateCtx}, weighted from {@code registry}. */
    static List<Candidate> candidates(GateContext gateCtx, TunnelGroupStore.Registry registry) {
        List<String> sections = TrackVariantGroupStore.topLevelNames(TrackKind.TUNNEL_SECTION);
        List<String> portals = TrackVariantGroupStore.topLevelNames(TrackKind.TUNNEL_PORTAL);
        TreeSet<String> ids = new TreeSet<>(registry.groups().keySet());
        for (String n : sections) ids.addAll(TrackVariantWeights.groupsFor(TrackKind.TUNNEL_SECTION, n));
        for (String n : portals) ids.addAll(TrackVariantWeights.groupsFor(TrackKind.TUNNEL_PORTAL, n));

        List<Candidate> out = new ArrayList<>();
        if (canBuild(TemplateGroup.UNGROUPED, sections, portals, gateCtx)) {
            out.add(new Candidate(TemplateGroup.UNGROUPED, registry.ungroupedWeight()));
        }
        for (String id : ids) {
            // A group carries its own spawn gate (inline or its Stage's), as a template does: where
            // it does not allow the spot, the group is not rolled at all.
            if (gateCtx != null && !gateCtx.allows(registry.gateOf(id))) continue;
            TemplateGroup g = TemplateGroup.of(id);
            if (canBuild(g, sections, portals, gateCtx)) out.add(new Candidate(g, registry.weightOf(id)));
        }
        return out;
    }

    private static boolean canBuild(TemplateGroup g, List<String> sections, List<String> portals, GateContext ctx) {
        return anyAllowed(TrackKind.TUNNEL_SECTION, g, sections, ctx)
            && anyAllowed(TrackKind.TUNNEL_PORTAL, g, portals, ctx);
    }

    private static boolean anyAllowed(TrackKind kind, TemplateGroup g, List<String> names, GateContext ctx) {
        for (String n : names) {
            if (!g.matches(TrackVariantWeights.groupsFor(kind, n))) continue;
            if (ctx == null || ctx.allows(TrackVariantWeights.gateFor(kind, n))) return true;
        }
        return false;
    }

    /**
     * Weighted pick over {@code candidates} using {@code hash} (non-negative). {@code null} for an
     * empty list or an all-zero total. Candidate order is fixed by {@link #candidates}, so the same
     * hash always lands on the same group.
     */
    static TemplateGroup pick(long hash, List<Candidate> candidates) {
        long total = 0;
        for (Candidate c : candidates) total += Math.max(0, c.weight());
        if (total <= 0) return null;
        long r = Math.floorMod(hash, total);
        for (Candidate c : candidates) {
            r -= Math.max(0, c.weight());
            if (r < 0) return c.group();
        }
        return candidates.get(candidates.size() - 1).group();
    }
}

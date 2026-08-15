package games.brennan.dungeontrain.track.variant;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.template.TemplateWeightCodec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;

/**
 * Group-of-variants record for a {@link TrackKind} parent name — the track-side twin of
 * {@link games.brennan.dungeontrain.train.CarriageContentsGroup}. When {@link TrackVariantRegistry}
 * picks a name that has a group sidecar, it redirects to one of this group's members via a weighted
 * draw taken from the same seeded stream, so the answer stays a pure function of
 * {@code (worldSeed, tileIndex, kind)}.
 *
 * <p>Storage: {@code <kind.subdir>/<parent-name>.group.json}, a sidecar alongside the parent's
 * {@code .nbt} — see {@link games.brennan.dungeontrain.editor.TrackVariantGroupStore}. Today only
 * {@link TrackKind#DIMENSIONAL_CARRIAGE} uses it: one named dimensional carriage stands for several room designs.</p>
 *
 * <p>JSON schema — deliberately identical to the contents group sidecar, so the two can be read by
 * eye against each other and {@link TemplateWeightCodec} serves both:</p>
 * <pre>
 * {
 *   "schemaVersion": 3,
 *   "selfWeight": 1,
 *   "variants": [
 *     {"id": "library_tall", "weight": 5},
 *     {"id": "library_flooded", "weight": 2, "minLevel": 4, "phases": ["NETHER"]},
 *     {"id": "library_dark", "weight": 2, "stages": ["late"]}
 *   ]
 * }
 * </pre>
 *
 * <p>{@code selfWeight} is how often the parent's <b>own</b> template is drawn against its members.
 * A parent is a real, editable variant in its own right, not just a folder.</p>
 *
 * <p>Member ids are names of the <b>same kind</b> as the parent — a dimensional carriage group's members are
 * dimensional carriages. Nesting is single-hop: a member that is itself a parent is treated as a leaf (and
 * logged), matching the contents rule.</p>
 */
public record TrackVariantGroup(int selfWeight, List<Member> members) {

    public static final String SCHEMA_KEY = "schemaVersion";

    /** Matches the contents sidecar's schema so the two formats stay legible against each other. */
    public static final int SCHEMA_VERSION = 3;

    /** Minimum allowed member weight. 0 excludes the member from the draw. */
    public static final int MIN_WEIGHT = 0;
    /** Maximum allowed member weight. Mirrors {@link TrackVariantWeights#MAX}. */
    public static final int MAX_WEIGHT = 100;
    /** Default member weight when unspecified or invalid. */
    public static final int DEFAULT_WEIGHT = 1;
    /** Default {@link #selfWeight()} for sidecars missing the field. */
    public static final int DEFAULT_SELF_WEIGHT = 1;

    /** Shared zero-member sentinel — "no group" should use {@code Optional.empty()} at the store level. */
    public static final TrackVariantGroup EMPTY = new TrackVariantGroup(Collections.emptyList());

    /**
     * One weighted entry, with an optional spawn {@link TemplateGate gate} and a set of Stage links.
     * Like a contents group member, a track sub-variant may link to <b>more than one</b> Stage: with
     * links present the member is eligible wherever <b>any</b> linked Stage's gate allows the context
     * (a union); otherwise the inline {@code gate} applies. A bare {@code (id, weight)} member is
     * eligible everywhere.
     */
    public record Member(String id, int weight, TemplateGate gate, List<String> stageIds) {
        public Member {
            if (id == null) throw new IllegalArgumentException("Member id must not be null");
            String norm = id.toLowerCase(Locale.ROOT);
            if (!TrackVariantRegistry.NAME_PATTERN.matcher(norm).matches()) {
                throw new IllegalArgumentException(
                    "Invalid group member id '" + id + "' — must match " + TrackVariantRegistry.NAME_PATTERN.pattern());
            }
            id = norm;
            weight = clampWeight(weight);
            if (gate == null) gate = TemplateGate.DEFAULT;
            stageIds = normalizeStages(stageIds);
        }

        /** Back-compat: a member with the default (eligible-everywhere) gate and no Stage link. */
        public Member(String id, int weight) {
            this(id, weight, TemplateGate.DEFAULT, Collections.emptyList());
        }

        /** True when this member is linked to at least one Stage (the union gate applies). */
        public boolean isStageLinked() { return !stageIds.isEmpty(); }

        public Member withWeight(int newWeight) { return new Member(id, newWeight, gate, stageIds); }
        public Member withGate(TemplateGate newGate) { return new Member(id, weight, newGate, stageIds); }
        public Member withStages(List<String> newStageIds) { return new Member(id, weight, gate, newStageIds); }

        /**
         * New member with {@code stageId} toggled — added (appended) when absent, removed when
         * present. Blank / null id is a no-op. Order of the remaining links is preserved. Mirrors
         * {@code CarriageContentsGroup.Member.withStageToggled}: a sub-variant's links are a union,
         * so the editor edits them one at a time rather than replacing the set.
         */
        public Member withStageToggled(String stageId) {
            if (stageId == null) return this;
            String s = stageId.trim().toLowerCase(Locale.ROOT);
            if (s.isEmpty()) return this;
            List<String> next = new ArrayList<>(stageIds);
            if (!next.remove(s)) next.add(s);
            return new Member(id, weight, gate, next);
        }

        private static List<String> normalizeStages(List<String> in) {
            if (in == null || in.isEmpty()) return Collections.emptyList();
            java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
            for (String s : in) {
                if (s == null) continue;
                String n = s.trim().toLowerCase(Locale.ROOT);
                if (!n.isEmpty()) out.add(n);
            }
            return List.copyOf(out);
        }
    }

    public TrackVariantGroup {
        selfWeight = clampWeight(selfWeight);
        if (members == null) {
            members = Collections.emptyList();
        } else {
            // Position of FIRST occurrence is preserved; value of LAST occurrence wins — so the
            // editor's slot ordering (and therefore the plot layout) is stable across an update.
            java.util.LinkedHashMap<String, Integer> indexOf = new java.util.LinkedHashMap<>();
            List<Member> ordered = new ArrayList<>();
            for (Member m : members) {
                if (m == null) continue;
                Integer existing = indexOf.get(m.id());
                if (existing != null) {
                    ordered.set(existing, m);
                } else {
                    indexOf.put(m.id(), ordered.size());
                    ordered.add(m);
                }
            }
            members = List.copyOf(ordered);
        }
    }

    public TrackVariantGroup(List<Member> members) {
        this(DEFAULT_SELF_WEIGHT, members);
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }

    /** The member with {@code id} (case-insensitive), or empty when this group has no such member. */
    public Optional<Member> member(String id) {
        if (id == null) return Optional.empty();
        String norm = id.toLowerCase(Locale.ROOT);
        for (Member m : members) {
            if (m.id().equals(norm)) return Optional.of(m);
        }
        return Optional.empty();
    }

    /** Index of {@code id} among the members, or -1. Drives the sub-variant plot slot. */
    public int indexOf(String id) {
        if (id == null) return -1;
        String norm = id.toLowerCase(Locale.ROOT);
        for (int i = 0; i < members.size(); i++) {
            if (members.get(i).id().equals(norm)) return i;
        }
        return -1;
    }

    /** New group with {@code member} appended; an existing member with the same id is replaced in place. */
    public TrackVariantGroup withMember(Member member) {
        if (member == null) return this;
        List<Member> next = new ArrayList<>(members.size() + 1);
        boolean replaced = false;
        for (Member m : members) {
            if (m.id().equals(member.id())) {
                next.add(member);
                replaced = true;
            } else {
                next.add(m);
            }
        }
        if (!replaced) next.add(member);
        return new TrackVariantGroup(selfWeight, next);
    }

    /** New group with {@code memberId} removed (idempotent). */
    public TrackVariantGroup withoutMember(String memberId) {
        if (memberId == null) return this;
        String norm = memberId.toLowerCase(Locale.ROOT);
        if (members.stream().noneMatch(m -> m.id().equals(norm))) return this;
        List<Member> next = new ArrayList<>(members.size());
        for (Member m : members) {
            if (!m.id().equals(norm)) next.add(m);
        }
        return new TrackVariantGroup(selfWeight, next);
    }

    /** New group with {@code newSelfWeight} replacing the parent's own share of the draw. */
    public TrackVariantGroup withSelfWeight(int newSelfWeight) {
        return new TrackVariantGroup(newSelfWeight, members);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty(SCHEMA_KEY, SCHEMA_VERSION);
        o.addProperty("selfWeight", selfWeight);
        JsonArray arr = new JsonArray();
        for (Member m : members) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", m.id());
            entry.addProperty("weight", m.weight());
            // Gate + Stage fields are omitted when default, so an ungated member round-trips as the
            // bare {id, weight} pair it was written as.
            TemplateWeightCodec.writeGateFields(entry, m.gate());
            TemplateWeightCodec.writeStages(entry, m.stageIds());
            arr.add(entry);
        }
        o.add("variants", arr);
        return o;
    }

    /**
     * Tolerant reader. Missing or non-array {@code variants} → an empty group. Entries that aren't
     * objects, lack a string {@code id}, or fail name validation are skipped rather than failing the
     * load — a hand-edited sidecar costs one member, not the world.
     */
    public static TrackVariantGroup fromJson(JsonObject o) {
        if (o == null) return EMPTY;
        int selfWeight = DEFAULT_SELF_WEIGHT;
        JsonElement swEl = o.get("selfWeight");
        if (swEl != null && swEl.isJsonPrimitive() && swEl.getAsJsonPrimitive().isNumber()) {
            try {
                selfWeight = swEl.getAsInt();
            } catch (Exception ignored) {
                selfWeight = DEFAULT_SELF_WEIGHT;
            }
        }
        JsonElement el = o.get("variants");
        if (el == null || !el.isJsonArray()) return new TrackVariantGroup(selfWeight, Collections.emptyList());
        List<Member> out = new ArrayList<>();
        for (JsonElement item : el.getAsJsonArray()) {
            if (!item.isJsonObject()) continue;
            JsonObject entry = item.getAsJsonObject();
            JsonElement idEl = entry.get("id");
            if (idEl == null || !idEl.isJsonPrimitive() || !idEl.getAsJsonPrimitive().isString()) continue;
            String id = idEl.getAsString();
            if (id.isBlank()) continue;
            String norm = id.toLowerCase(Locale.ROOT);
            if (!TrackVariantRegistry.NAME_PATTERN.matcher(norm).matches()) continue;
            if (TrackKind.DEFAULT_NAME.equals(norm)) continue; // the synthetic default is never a member
            int weight = DEFAULT_WEIGHT;
            JsonElement wEl = entry.get("weight");
            if (wEl != null && wEl.isJsonPrimitive() && wEl.getAsJsonPrimitive().isNumber()) {
                try {
                    weight = wEl.getAsInt();
                } catch (Exception ignored) {
                    weight = DEFAULT_WEIGHT;
                }
            }
            out.add(new Member(norm, weight,
                TemplateWeightCodec.parseGate(entry), TemplateWeightCodec.parseStages(entry)));
        }
        return new TrackVariantGroup(selfWeight, out);
    }

    /**
     * Weighted draw over {@code selfWeight} + the members, using {@code rng}.
     *
     * <p>Returns {@code null} for "the parent itself" — either because the self entry won the draw,
     * the group is empty, or every weight is 0. The caller keeps the parent name in all three
     * cases, which is why they collapse to one answer here.</p>
     */
    public Member pick(Random rng, List<Member> eligible) {
        if (eligible == null || eligible.isEmpty()) return null;
        int total = selfWeight;
        for (Member m : eligible) total += m.weight();
        if (total <= 0) return null;
        int r = rng.nextInt(total);
        if (r < selfWeight) return null;
        int cumulative = selfWeight;
        for (Member m : eligible) {
            cumulative += m.weight();
            if (r < cumulative) return m;
        }
        return eligible.get(eligible.size() - 1);
    }

    public static int clampWeight(int value) {
        if (value < MIN_WEIGHT) return MIN_WEIGHT;
        if (value > MAX_WEIGHT) return MAX_WEIGHT;
        return value;
    }
}

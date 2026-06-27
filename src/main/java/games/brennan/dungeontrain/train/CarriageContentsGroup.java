package games.brennan.dungeontrain.train;

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
 * Group-of-variants record for a {@link CarriageContents} parent. When a
 * carriage's interior pick resolves to a parent id that has a group sidecar,
 * the registry redirects to one of this group's members via a weighted draw.
 *
 * <p>Storage: {@code config/dungeontrain/contents/<parent-id>.group.json}, a
 * sidecar alongside the parent's optional {@code .nbt} (group-only parents
 * have no {@code .nbt} — the group resolution short-circuits placement).</p>
 *
 * <p>JSON schema (v2 — each member may carry an optional spawn gate / Stage link, omitted when
 * ungated so v1 sidecars round-trip unchanged):</p>
 * <pre>
 * {
 *   "schemaVersion": 2,
 *   "selfWeight": 1,
 *   "variants": [
 *     {"id": "container_wooden", "weight": 5},
 *     {"id": "piglin", "weight": 2, "minLevel": 4, "phases": ["NETHER"]},
 *     {"id": "cagedzombie", "weight": 2, "stage": "early"}
 *   ]
 * }
 * </pre>
 *
 * <p>{@code selfWeight} controls how often the parent's own contents are
 * drawn from its group pool (alongside the explicit members in
 * {@code variants}). Defaults to {@value #DEFAULT_SELF_WEIGHT} when
 * unspecified — matches the pre-schema-v1.1 behaviour where the parent's
 * synthetic self-entry was hardcoded to 1.</p>
 *
 * <p>Field name {@code variants} (not {@code subVariants}) is intentional —
 * a group's members are themselves variants of contents; the parent is just
 * a variant that happens to delegate.</p>
 */
public record CarriageContentsGroup(int selfWeight, List<Member> members) {

    public static final String SCHEMA_KEY = "schemaVersion";
    /** v2 added per-member spawn gate + Stage link. v1 sidecars (id+weight only) read as ungated. */
    public static final int SCHEMA_VERSION = 2;

    /** Minimum allowed member weight. 0 excludes the member from the draw. */
    public static final int MIN_WEIGHT = 0;
    /** Maximum allowed member weight. Mirrors {@link CarriageContentsWeights#MAX}. */
    public static final int MAX_WEIGHT = 100;
    /** Default member weight when unspecified or invalid. */
    public static final int DEFAULT_WEIGHT = 1;
    /** Default {@link #selfWeight()} for groups missing the field on disk. */
    public static final int DEFAULT_SELF_WEIGHT = 1;

    /** Shared zero-member sentinel — "no group" should use {@code Optional.empty()} at the store level instead. */
    public static final CarriageContentsGroup EMPTY = new CarriageContentsGroup(Collections.emptyList());

    /**
     * Single weighted entry in the group, with an optional per-member spawn {@link TemplateGate gate}
     * and {@link #stageId() Stage link}. Mirrors {@code TemplateMeta} at the member level: when
     * {@code stageId != null} the linked Stage's gate is the effective gate; otherwise {@code gate}
     * (the inline Custom gate) applies. A bare {@code (id, weight)} member is eligible everywhere —
     * identical to the pre-gate behaviour.
     */
    public record Member(String id, int weight, TemplateGate gate, String stageId) {
        public Member {
            if (id == null) throw new IllegalArgumentException("Member id must not be null");
            String norm = id.toLowerCase(Locale.ROOT);
            if (!CarriageContents.NAME_PATTERN.matcher(norm).matches()) {
                throw new IllegalArgumentException(
                    "Invalid group member id '" + id + "' — must match " + CarriageContents.NAME_PATTERN.pattern());
            }
            id = norm;
            weight = clampWeight(weight);
            if (gate == null) gate = TemplateGate.DEFAULT;
            if (stageId != null) {
                String s = stageId.trim().toLowerCase(Locale.ROOT);
                stageId = s.isEmpty() ? null : s;
            }
        }

        /** Back-compat: a member with the default (eligible-everywhere) gate and no Stage link. */
        public Member(String id, int weight) {
            this(id, weight, TemplateGate.DEFAULT, null);
        }

        public Member withWeight(int newWeight) { return new Member(id, newWeight, gate, stageId); }
        public Member withGate(TemplateGate newGate) { return new Member(id, weight, newGate, stageId); }
        public Member withStage(String newStageId) { return new Member(id, weight, gate, newStageId); }
    }

    public CarriageContentsGroup {
        selfWeight = clampWeight(selfWeight);
        if (members == null) {
            members = Collections.emptyList();
        } else {
            // Position of FIRST occurrence is preserved; value of LAST occurrence wins.
            // So [a=1, b=2, a=9] dedupes to [a=9, b=2] — the slot 0 'a' is updated in place.
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

    /**
     * Backwards-compatible constructor for call sites and tests that pre-date
     * the editable {@link #selfWeight()} field. Initialises {@code selfWeight}
     * to {@link #DEFAULT_SELF_WEIGHT}, matching the pre-v1.1 hardcoded behaviour.
     */
    public CarriageContentsGroup(List<Member> members) {
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

    /**
     * Weighted draw using {@code rng}. Returns {@code null} for an empty group
     * or when every member has weight 0. Caller is responsible for falling back
     * (the registry uses {@link CarriageContents.ContentsType#DEFAULT}).
     */
    public Member pick(Random rng) {
        if (members.isEmpty()) return null;
        int total = 0;
        for (Member m : members) total += m.weight();
        if (total <= 0) return null;
        int r = rng.nextInt(total);
        int cumulative = 0;
        for (Member m : members) {
            cumulative += m.weight();
            if (r < cumulative) return m;
        }
        return members.get(members.size() - 1);
    }

    /** New group with {@code member} appended; if the same id is already present, its weight is replaced. */
    public CarriageContentsGroup withMember(Member member) {
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
        return new CarriageContentsGroup(selfWeight, next);
    }

    /** New group with {@code memberId} removed (idempotent). */
    public CarriageContentsGroup withoutMember(String memberId) {
        if (memberId == null) return this;
        String norm = memberId.toLowerCase(Locale.ROOT);
        if (members.stream().noneMatch(m -> m.id().equals(norm))) return this;
        List<Member> next = new ArrayList<>(members.size());
        for (Member m : members) {
            if (!m.id().equals(norm)) next.add(m);
        }
        return new CarriageContentsGroup(selfWeight, next);
    }

    /**
     * New group with {@code newSelfWeight} replacing the parent's self-weight
     * in the resolution pool. The new value is clamped via {@link #clampWeight}
     * in the canonical constructor.
     */
    public CarriageContentsGroup withSelfWeight(int newSelfWeight) {
        return new CarriageContentsGroup(newSelfWeight, members);
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
            // Non-default gate fields (minLevel/maxLevel/phases) + optional Stage link — omitted when
            // the member is ungated, so v1-shaped members round-trip byte-identically.
            TemplateWeightCodec.writeGateFields(entry, m.gate());
            if (m.stageId() != null) entry.addProperty(TemplateWeightCodec.K_STAGE, m.stageId());
            arr.add(entry);
        }
        o.add("variants", arr);
        return o;
    }

    /**
     * Tolerant reader. Missing or non-array {@code variants} → {@link #EMPTY}.
     * Entries that aren't objects, lack a string {@code id}, or fail name
     * validation are skipped with no error (the registry's load-time pass logs
     * structural issues separately). Missing or non-numeric {@code selfWeight}
     * defaults to {@link #DEFAULT_SELF_WEIGHT} for backward compatibility with
     * pre-v1.1 sidecars.
     */
    public static CarriageContentsGroup fromJson(JsonObject o) {
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
        if (el == null || !el.isJsonArray()) return new CarriageContentsGroup(selfWeight, Collections.emptyList());
        List<Member> out = new ArrayList<>();
        for (JsonElement item : el.getAsJsonArray()) {
            if (!item.isJsonObject()) continue;
            JsonObject entry = item.getAsJsonObject();
            JsonElement idEl = entry.get("id");
            if (idEl == null || !idEl.isJsonPrimitive() || !idEl.getAsJsonPrimitive().isString()) continue;
            String id = idEl.getAsString();
            if (id.isBlank()) continue;
            String norm = id.toLowerCase(Locale.ROOT);
            if (!CarriageContents.NAME_PATTERN.matcher(norm).matches()) continue;
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
                TemplateWeightCodec.parseGate(entry), TemplateWeightCodec.parseStage(entry)));
        }
        return new CarriageContentsGroup(selfWeight, out);
    }

    public static int clampWeight(int value) {
        if (value < MIN_WEIGHT) return MIN_WEIGHT;
        if (value > MAX_WEIGHT) return MAX_WEIGHT;
        return value;
    }
}

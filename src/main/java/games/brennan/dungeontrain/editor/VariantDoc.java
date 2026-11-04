package games.brennan.dungeontrain.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One block-variant sidecar ({@code <name>.variants.json}) as a document: {@code localPos →
 * [VariantState, ...]} candidate lists, per-cell lock ids, spans and repeating-room copy settings,
 * the per-template mirror axes, and the v9 lock-group reference resolver over all of it.
 *
 * <p>Every kind of template shares this one class — carriage, contents, part, whole, track-side,
 * chunk frame, the Train Builder's working copy and a Workbench build. What differs per kind is
 * where the file lives and how it starts out, which {@link VariantAddress} supplies and
 * {@link VariantSidecarStore} applies; a document itself only knows its {@link MirrorDefaults}
 * (what an absent {@code "mirror"} field means, and so when the writer may omit it), its
 * {@link VariantPick} (how a cell rolls once narrowed to the tier) and a log context.</p>
 *
 * <p>The cell and candidate codec stays in {@link CarriageVariantBlocks}
 * ({@link CarriageVariantBlocks#parseCellValue}, {@link CarriageVariantBlocks#appendCellJson}),
 * shared with {@link BlockVariantPrefabStore}; this class owns the envelope — the
 * {@code schemaVersion} / {@code mirror} / {@code variants} object — and the position keys.</p>
 *
 * <p><b>Views.</b> A caller that asks for the document bounded to a footprint ({@link #croppedTo})
 * gets a <em>view</em>: a detached copy without the out-of-bounds cells, whose every mutation and
 * both write paths go through to the whole document it was cut from ({@link #source}). The cache
 * always holds the whole sidecar, so no caller's footprint can prune what the rest of the session
 * sees — writing a view's own cells would delete every cell the bound removed, the failure that
 * emptied four portal rooms' sidecars on 2026-09-02. Views are memoised per footprint and the skip
 * is warned once per sidecar and footprint per session, not once per cell per read (6,400 WARN
 * lines across 92 bug reports, 2026-10-20).</p>
 */
public final class VariantDoc {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int CURRENT_SCHEMA_VERSION = CarriageVariantBlocks.CURRENT_SCHEMA_VERSION;
    public static final int MIN_STATES_PER_ENTRY = CarriageVariantBlocks.MIN_STATES_PER_ENTRY;

    /**
     * Footprints already warned about, keyed {@code <contextId>@WxHxD}. One line per sidecar and
     * footprint per session; cleared with the store's cache.
     */
    private static final Set<String> WARNED_FOOTPRINTS = new HashSet<>();

    /** How many out-of-footprint positions the one warning lists before trailing off. */
    private static final int WARN_POSITIONS_SHOWN = 8;

    /** pos → candidate variants. {@link LinkedHashMap} keeps insertion order for deterministic JSON output. */
    private final Map<BlockPos, List<VariantState>> entries;

    /**
     * pos → lock-id (≥1 = locked, 0/missing = unlocked). Cells with the same non-zero lock-id share
     * a runtime random pick at spawn — the picker hashes the lock-id instead of the position. The
     * lock-id space is local to this sidecar.
     */
    private final Map<BlockPos, Integer> lockIds;

    /**
     * How each cell rolls across a repeating room's copies — the v10 {@code "roll"} field. Only the
     * cells that override their room appear here; only a dimensional carriage room repeats, so only a
     * room's sidecar ever holds anything, but every kind stores and writes it so a cell's setting
     * never silently disappears on the way through a copy or a save-as.
     */
    private final Map<BlockPos, VariantCopyRoll> copyRolls;

    /** Which tiles of a repeating room each cell applies in — the v10 {@code "scope"} field. Non-default only. */
    private final Map<BlockPos, VariantCopyScope> copyScopes;

    /** pos → per-cell {@link VariantSpan} (door / bed / tall plant). Non-default only; see {@link #spanAt}. */
    private final Map<BlockPos, VariantSpan> spans = new LinkedHashMap<>();

    /** v9 lock-group reference resolution over {@link #entries} / {@link #lockIds}. */
    private final VariantGroupResolver groupRefs;

    /** What an absent {@code "mirror"} field means for this document, and when the writer omits it. */
    private final MirrorDefaults defaults;

    /** How a cell rolls once narrowed to the tier. */
    private final VariantPick pick;

    /** Log context: {@code <kind>:<name>}, or {@code builder} for a detached document. */
    private final String contextId;

    /**
     * Where this document is stored, when it came from {@link VariantSidecarStore}; null for a
     * detached document (a copy, a parsed fragment, the Train Builder's). What {@link #save} and
     * {@link #saveToSource} write to. A view shares its source's address.
     */
    private final @Nullable VariantAddress address;

    /**
     * Per-template mirror axes, applied live while editing and as a save-time backstop by
     * {@link EditorMirror}. Stored as the optional top-level {@code "mirror": {"x":…,"y":…,"z":…,"v":…}}
     * field; absent means {@link #defaults}.
     */
    private boolean mirrorX;
    private boolean mirrorY;
    private boolean mirrorZ;
    /** The "V" toggle: mirror the variant pools, not just structural blocks. Never a default. */
    private boolean mirrorVariants;

    /** The whole document this is a bounded view of, or null when this is the whole document. */
    private final VariantDoc source;

    /** Bounded views of this document by footprint; only a source holds any, every mutator clears it. */
    private final Map<Vec3i, VariantDoc> views = new HashMap<>();

    private VariantDoc(Map<BlockPos, List<VariantState>> entries, Map<BlockPos, Integer> lockIds,
                       Map<BlockPos, VariantCopyRoll> copyRolls, Map<BlockPos, VariantCopyScope> copyScopes,
                       MirrorDefaults defaults, VariantPick pick, String contextId,
                       @Nullable VariantAddress address,
                       boolean mirrorX, boolean mirrorY, boolean mirrorZ, boolean mirrorVariants,
                       @Nullable VariantDoc source) {
        this.entries = entries;
        this.lockIds = lockIds;
        this.copyRolls = copyRolls;
        this.copyScopes = copyScopes;
        this.groupRefs = new VariantGroupResolver(entries, lockIds);
        this.defaults = defaults == null ? MirrorDefaults.NONE : defaults;
        this.pick = pick == null ? VariantPick.PLAIN : pick;
        this.contextId = contextId == null ? "builder" : contextId;
        this.address = address;
        this.mirrorX = mirrorX;
        this.mirrorY = mirrorY;
        this.mirrorZ = mirrorZ;
        this.mirrorVariants = mirrorVariants;
        this.source = source;
    }

    // ---------- Construction ----------

    /** Bare empty document, all axes off, plain pick — for paths with no kind context. */
    public static VariantDoc empty() {
        return emptyWith(MirrorDefaults.NONE, VariantPick.PLAIN, null);
    }

    /** Empty document carrying {@code defaults} — the missing-sidecar state for a kind. */
    public static VariantDoc emptyWith(MirrorDefaults defaults, VariantPick pick, @Nullable String contextId) {
        return emptyAt(defaults, pick, contextId, null);
    }

    /** {@link #emptyWith} for an address — the missing-sidecar document the store caches. */
    public static VariantDoc emptyFor(VariantAddress address) {
        return emptyAt(address.defaultMirror(), address.pick(), address.contextId(), address);
    }

    private static VariantDoc emptyAt(MirrorDefaults defaults, VariantPick pick, @Nullable String contextId,
                                      @Nullable VariantAddress address) {
        return new VariantDoc(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(),
            defaults, pick, contextId, address, defaults.x(), defaults.y(), defaults.z(), false, null);
    }

    /**
     * Deep copy of {@code src} — every cell, lock id, span, copy setting, the mirror axes and the
     * mirror-variants flag. A genuinely distinct instance on purpose: saving puts the document into
     * the name-keyed cache, so persisting the source object under a second name would leave both
     * names aliased to one mutable sidecar. The copy is detached — it has no address until the
     * store saves it under one.
     */
    public static synchronized VariantDoc copyOf(VariantDoc src) {
        return withSpans(new VariantDoc(
            new LinkedHashMap<>(src.entries), new LinkedHashMap<>(src.lockIds),
            new LinkedHashMap<>(src.copyRolls), new LinkedHashMap<>(src.copyScopes),
            src.defaults, src.pick, src.contextId, null,
            src.mirrorX, src.mirrorY, src.mirrorZ, src.mirrorVariants, null), src.spans);
    }

    /**
     * Parse a detached document from {@link #toJsonText} output, bounded to {@code size}.
     *
     * <p>Never enters any cache: the result belongs to whoever asked for it. Cropped here on
     * purpose, unlike the cached load path: the portal-room resize memory files a slab at one size
     * and restores it after the other two axes may have shrunk, and relies on this bound to drop the
     * cells that no longer fit ({@code PortalRoomResizeSlabs#restore}).</p>
     */
    public static VariantDoc fromJsonText(@Nullable String json, MirrorDefaults defaults, @Nullable String contextId,
                                          @Nullable Vec3i size) {
        if (json == null || json.isBlank()) return emptyWith(defaults, VariantPick.PLAIN, contextId);
        return parse(new StringReader(json), defaults, VariantPick.PLAIN, contextId, "memory").croppedTo(size);
    }

    /**
     * Parse the whole sidecar. Deliberately keeps every cell, however far outside any footprint:
     * bounding is {@link #croppedTo}'s job, applied per caller, so no one caller's footprint can
     * prune what the rest of the session sees.
     */
    public static VariantDoc parse(Reader reader, MirrorDefaults defaults, VariantPick pick,
                                   @Nullable String contextId, String origin) {
        return parse(reader, defaults, pick, contextId, null, origin);
    }

    /** {@link #parse} for a stored sidecar: the result remembers {@code address} for {@link #save}. */
    static VariantDoc parse(Reader reader, MirrorDefaults defaults, VariantPick pick,
                            @Nullable String contextId, @Nullable VariantAddress address, String origin) {
        String ctx = contextId == null ? "builder" : contextId;
        JsonElement root = JsonParser.parseReader(reader);
        if (!root.isJsonObject()) {
            LOGGER.warn("[DungeonTrain] Variant sidecar {} ({}) is not a JSON object — ignoring.", ctx, origin);
            return emptyAt(defaults, pick, ctx, address);
        }
        JsonObject obj = root.getAsJsonObject();
        if (obj.has("schemaVersion")) {
            int v = obj.get("schemaVersion").getAsInt();
            if (v > CURRENT_SCHEMA_VERSION) {
                LOGGER.warn("[DungeonTrain] Variant sidecar {} ({}) schemaVersion {} (newer than {}) — best-effort parse.",
                    ctx, origin, v, CURRENT_SCHEMA_VERSION);
            }
        }
        boolean mirrorX = defaults.x();
        boolean mirrorY = defaults.y();
        boolean mirrorZ = defaults.z();
        boolean mirrorVariants = false;
        if (obj.has("mirror") && obj.get("mirror").isJsonObject()) {
            JsonObject m = obj.getAsJsonObject("mirror");
            if (m.has("x")) mirrorX = m.get("x").getAsBoolean();
            if (m.has("y")) mirrorY = m.get("y").getAsBoolean();
            if (m.has("z")) mirrorZ = m.get("z").getAsBoolean();
            if (m.has("v")) mirrorVariants = m.get("v").getAsBoolean();
        }
        Map<BlockPos, List<VariantState>> out = new LinkedHashMap<>();
        Map<BlockPos, Integer> outLocks = new LinkedHashMap<>();
        Map<BlockPos, VariantCopyRoll> outRolls = new LinkedHashMap<>();
        Map<BlockPos, VariantCopyScope> outScopes = new LinkedHashMap<>();
        Map<BlockPos, VariantSpan> outSpans = new LinkedHashMap<>();
        if (obj.has("variants") && obj.get("variants").isJsonObject()) {
            HolderLookup.RegistryLookup<Block> blocks = BuiltInRegistries.BLOCK.asLookup();
            for (Map.Entry<String, JsonElement> field : obj.getAsJsonObject("variants").entrySet()) {
                BlockPos pos = parsePos(field.getKey());
                if (pos == null) {
                    LOGGER.warn("[DungeonTrain] Variant sidecar {}: bad pos '{}', skipping.", ctx, field.getKey());
                    continue;
                }
                CarriageVariantBlocks.ParsedCell cell = CarriageVariantBlocks.parseCellValue(
                    field.getValue(), blocks, ctx, pos);
                if (cell == null) continue;
                if (cell.states().size() < MIN_STATES_PER_ENTRY) {
                    LOGGER.warn("[DungeonTrain] Variant sidecar {} pos {}: fewer than {} valid states, dropped.",
                        ctx, pos, MIN_STATES_PER_ENTRY);
                    continue;
                }
                BlockPos posI = pos.immutable();
                out.put(posI, List.copyOf(cell.states()));
                if (!cell.span().isDefault()) outSpans.put(posI, cell.span());
                if (cell.lockId() > 0) outLocks.put(posI, cell.lockId());
                if (!cell.roll().isDefault()) outRolls.put(posI, cell.roll());
                if (!cell.scope().isDefault()) outScopes.put(posI, cell.scope());
            }
            LOGGER.info("[DungeonTrain] Loaded {} variant entries for {} from {}", out.size(), ctx, origin);
        }
        return withSpans(new VariantDoc(out, outLocks, outRolls, outScopes, defaults, pick, ctx, address,
            mirrorX, mirrorY, mirrorZ, mirrorVariants, null), outSpans);
    }

    /** Copy {@code from}'s spans for the cells {@code target} holds — parse, copy and crop all end here. */
    private static VariantDoc withSpans(VariantDoc target, Map<BlockPos, VariantSpan> from) {
        for (Map.Entry<BlockPos, VariantSpan> e : from.entrySet()) {
            if (target.entries.containsKey(e.getKey())) target.spans.put(e.getKey(), e.getValue());
        }
        return target;
    }

    // ---------- Views ----------

    /**
     * This document bounded to {@code size} — {@code this} when every cell already fits (the common
     * path, and the only one that yields a saveable instance) or {@code size} is null, otherwise the
     * memoised view for that footprint.
     */
    public synchronized VariantDoc croppedTo(@Nullable Vec3i size) {
        if (size == null) return this;
        Vec3i footprint = new Vec3i(size.getX(), size.getY(), size.getZ());
        VariantDoc memoised = views.get(footprint);
        if (memoised != null) return memoised;
        List<BlockPos> outside = null;
        for (BlockPos pos : entries.keySet()) {
            if (inBounds(pos, size)) continue;
            if (outside == null) outside = new ArrayList<>();
            outside.add(pos);
        }
        if (outside == null) return this;

        Map<BlockPos, List<VariantState>> kept = new LinkedHashMap<>(entries);
        Map<BlockPos, Integer> keptLocks = new LinkedHashMap<>(lockIds);
        Map<BlockPos, VariantCopyRoll> keptRolls = new LinkedHashMap<>(copyRolls);
        Map<BlockPos, VariantCopyScope> keptScopes = new LinkedHashMap<>(copyScopes);
        for (BlockPos pos : outside) {
            kept.remove(pos);
            keptLocks.remove(pos);
            keptRolls.remove(pos);
            keptScopes.remove(pos);
        }
        warnOutsideOnce(contextId, footprint, outside);
        VariantDoc view = withSpans(new VariantDoc(kept, keptLocks, keptRolls, keptScopes, defaults, pick, contextId,
            address, mirrorX, mirrorY, mirrorZ, mirrorVariants, this), spans);
        views.put(footprint, view);
        return view;
    }

    /** One WARN per sidecar and footprint per session, listing the dropped cells. */
    private static void warnOutsideOnce(String contextId, Vec3i size, List<BlockPos> outside) {
        String key = contextId + "@" + size.getX() + "x" + size.getY() + "x" + size.getZ();
        synchronized (WARNED_FOOTPRINTS) {
            if (!WARNED_FOOTPRINTS.add(key)) return;
        }
        int shown = Math.min(outside.size(), WARN_POSITIONS_SHOWN);
        LOGGER.warn("[DungeonTrain] Variant sidecar {}: {} cell(s) outside footprint {}x{}x{}, skipping: {}{}",
            contextId, outside.size(), size.getX(), size.getY(), size.getZ(),
            outside.subList(0, shown), outside.size() > shown ? " ..." : "");
    }

    /** Forget the warned footprints of one sidecar, so a re-authored file warns afresh. */
    static void forgetWarnings(String contextId) {
        String prefix = contextId + "@";
        synchronized (WARNED_FOOTPRINTS) {
            WARNED_FOOTPRINTS.removeIf(k -> k.startsWith(prefix));
        }
    }

    /** Forget every warned footprint — with the store's cache. */
    static void clearWarnings() {
        synchronized (WARNED_FOOTPRINTS) {
            WARNED_FOOTPRINTS.clear();
        }
    }

    /** True when this instance is a bounded view of a larger document — see {@link #source}. */
    public boolean isCropped() { return source != null; }

    /** The whole document a view was cut from, or {@code this} — what every write path persists. */
    public VariantDoc whole() { return source != null ? source : this; }

    // ---------- Positions ----------

    /** Parses an {@code "x,y,z"} key back into a {@link BlockPos}, or null. */
    public static @Nullable BlockPos parsePos(String key) {
        String[] parts = key.split(",");
        if (parts.length != 3) return null;
        try {
            return new BlockPos(
                Integer.parseInt(parts[0].trim()),
                Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim())
            );
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Renders a {@link BlockPos} as the {@code "x,y,z"} JSON key. */
    public static String formatPos(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    /** True when {@code p} is strictly inside a {@code size} footprint anchored at the origin. */
    public static boolean inBounds(BlockPos p, Vec3i size) {
        return p.getX() >= 0 && p.getX() < size.getX()
            && p.getY() >= 0 && p.getY() < size.getY()
            && p.getZ() >= 0 && p.getZ() < size.getZ();
    }

    // ---------- Reads ----------

    /** Iterable snapshot of all entries, in file order. */
    public List<CarriageVariantBlocks.Entry> entries() {
        List<CarriageVariantBlocks.Entry> out = new ArrayList<>(entries.size());
        for (Map.Entry<BlockPos, List<VariantState>> e : entries.entrySet()) {
            out.add(new CarriageVariantBlocks.Entry(e.getKey(), e.getValue()));
        }
        return Collections.unmodifiableList(out);
    }

    public boolean isEmpty() { return entries.isEmpty(); }

    public int size() { return entries.size(); }

    /** Candidate variants at {@code localPos}, or {@code null} if no entry. */
    public @Nullable List<VariantState> statesAt(BlockPos localPos) {
        return entries.get(localPos);
    }

    /**
     * True when any candidate at {@code localPos} has growth or a painting option on — the cheap
     * gate the hot worldgen paths check before re-rolling a cell for {@link GrowthPass}.
     */
    public boolean growsAt(BlockPos localPos) {
        List<VariantState> states = entries.get(localPos);
        if (states == null) return false;
        for (VariantState s : states) {
            if (!s.growth().isDefault() || !s.painting().isDefault()) return true;
        }
        return false;
    }

    public String contextId() { return contextId; }

    public MirrorDefaults defaults() { return defaults; }

    public VariantPick pick() { return pick; }

    // ---------- Mirror ----------

    public boolean mirrorX() { return mirrorX; }

    public boolean mirrorY() { return mirrorY; }

    public boolean mirrorZ() { return mirrorZ; }

    /** Mirror-variants ("V") opt-in. False unless the sidecar sets {@code mirror.v=true}. */
    public boolean mirrorVariants() { return mirrorVariants; }

    /** True when the axes match this document's defaults — the absent-{@code mirror}-field state. */
    public boolean isDefaultMirror() {
        return mirrorX == defaults.x() && mirrorY == defaults.y() && mirrorZ == defaults.z() && !mirrorVariants;
    }

    /** True when there is nothing to persist: no cells and default mirror. */
    public boolean isBlank() {
        return entries.isEmpty() && isDefaultMirror();
    }

    /** Set all three mirror axes — the {@code editor mirror} command, before a save. */
    public synchronized void setMirrorAxes(boolean x, boolean y, boolean z) {
        if (source != null) source.setMirrorAxes(x, y, z);
        views.clear();
        this.mirrorX = x;
        this.mirrorY = y;
        this.mirrorZ = z;
    }

    /** Set the mirror-variants ("V") opt-in — {@code editor mirror v on|off}, before a save. */
    public synchronized void setMirrorVariants(boolean v) {
        if (source != null) source.setMirrorVariants(v);
        views.clear();
        this.mirrorVariants = v;
    }

    // ---------- Mutation ----------

    /** Replace the candidate list at {@code localPos}. Requires {@link #MIN_STATES_PER_ENTRY} or more. */
    public synchronized void put(BlockPos localPos, List<VariantState> states) {
        if (states == null || states.size() < MIN_STATES_PER_ENTRY) {
            throw new IllegalArgumentException(
                "need at least " + MIN_STATES_PER_ENTRY + " states, got "
                    + (states == null ? 0 : states.size()));
        }
        for (VariantState s : states) {
            if (s == null) throw new IllegalArgumentException("null state");
        }
        if (source != null) source.put(localPos, states);
        views.clear();
        entries.put(localPos.immutable(), List.copyOf(states));
        groupRefs.invalidate();
    }

    /** Remove the entry at {@code localPos}, with its lock id, span and copy settings. True if one was present. */
    public synchronized boolean remove(BlockPos localPos) {
        spans.remove(localPos);
        if (source != null) source.remove(localPos);
        views.clear();
        lockIds.remove(localPos);
        copyRolls.remove(localPos);
        copyScopes.remove(localPos);
        groupRefs.invalidate();
        return entries.remove(localPos) != null;
    }

    /**
     * Wipe every entry and every lock-id. Returns the number of entries that were present. Used by
     * {@code /editor clear} so a plot wipe doesn't leave orphaned variant metadata pointing at
     * now-air cells.
     */
    public synchronized int clearAll() {
        if (source != null) source.clearAll();
        views.clear();
        spans.clear();
        int n = entries.size();
        entries.clear();
        lockIds.clear();
        copyRolls.clear();
        copyScopes.clear();
        groupRefs.invalidate();
        return n;
    }

    /** The cell's multi-space {@link VariantSpan}; {@code NONE} when unset or no cell. */
    public synchronized VariantSpan spanAt(BlockPos localPos) {
        return spans.getOrDefault(localPos, VariantSpan.NONE);
    }

    /** Set the cell's multi-space span (default clears it). Throws if no cell exists at {@code localPos}. */
    public synchronized void setSpan(BlockPos localPos, VariantSpan span) {
        if (source != null) source.setSpan(localPos, span);
        views.clear();
        requireCell(localPos);
        if (span == null || span.isDefault()) spans.remove(localPos);
        else spans.put(localPos.immutable(), span);
    }

    /** Lock-id at {@code localPos}; 0 if unlocked or no entry. */
    public synchronized int lockIdAt(BlockPos localPos) {
        return lockIds.getOrDefault(localPos, 0);
    }

    /** Set the lock-id for an existing cell. Pass 0 to unlock. Throws if no cell exists at {@code localPos}. */
    public synchronized void setLockId(BlockPos localPos, int lockId) {
        if (source != null) source.setLockId(localPos, lockId);
        views.clear();
        requireCell(localPos);
        if (lockId < 0) lockId = 0;
        if (lockId == 0) lockIds.remove(localPos);
        else lockIds.put(localPos.immutable(), lockId);
        groupRefs.invalidate();
    }

    /**
     * How the cell at {@code localPos} rolls across a repeating room's copies.
     * {@link VariantCopyRoll#DEFAULT} for every cell that has not overridden it.
     */
    public synchronized VariantCopyRoll copyRollAt(BlockPos localPos) {
        return copyRolls.getOrDefault(localPos, VariantCopyRoll.DEFAULT);
    }

    /**
     * Set that override on an existing cell. Rejects a position with no cell for the same reason
     * {@link #setLockId} does: a setting on nothing has no cell to be written beside, and would be
     * silently dropped on the next load.
     */
    public synchronized void setCopyRoll(BlockPos localPos, VariantCopyRoll roll) {
        if (source != null) source.setCopyRoll(localPos, roll);
        views.clear();
        requireCell(localPos);
        if (roll == null || roll.isDefault()) copyRolls.remove(localPos);
        else copyRolls.put(localPos.immutable(), roll);
    }

    /**
     * Which tiles of a repeating room the cell at {@code localPos} applies in.
     * {@link VariantCopyScope#BOTH} for every cell that has not been given a scope.
     */
    public synchronized VariantCopyScope copyScopeAt(BlockPos localPos) {
        return copyScopes.getOrDefault(localPos, VariantCopyScope.BOTH);
    }

    /** Set it. Refuses a position with no cell, like {@link #setLockId} and {@link #setCopyRoll}. */
    public synchronized void setCopyScope(BlockPos localPos, VariantCopyScope scope) {
        if (source != null) source.setCopyScope(localPos, scope);
        views.clear();
        requireCell(localPos);
        if (scope == null || scope.isDefault()) copyScopes.remove(localPos);
        else copyScopes.put(localPos.immutable(), scope);
    }

    private void requireCell(BlockPos localPos) {
        if (!entries.containsKey(localPos)) {
            throw new IllegalArgumentException("no cell at " + localPos + " — call put first");
        }
    }

    // ---------- Lock groups ----------

    /** Positions in this document that share the given lock-id. Empty for {@code lockId == 0}. */
    public synchronized Set<BlockPos> positionsWithLockId(int lockId) {
        if (lockId <= 0) return Set.of();
        Set<BlockPos> out = new LinkedHashSet<>();
        for (Map.Entry<BlockPos, Integer> e : lockIds.entrySet()) {
            if (e.getValue() == lockId) out.add(e.getKey());
        }
        return out;
    }

    /** Snapshot of every {@code (localPos, lockId)} pair with {@code lockId > 0}. Defensive copy. */
    public synchronized Map<BlockPos, Integer> allLockIds() {
        return new LinkedHashMap<>(lockIds);
    }

    /** Smallest positive integer not currently used as a lock-id, counting groups kept alive only by a reference. */
    public synchronized int nextFreeLockId() {
        Set<Integer> used = groupRefs.lockIdsInUse();
        int n = 1;
        while (used.contains(n)) n++;
        return n;
    }

    /** This document's lock groups, for callers that follow references themselves (e.g. the editor preview). */
    public VariantGroupResolver groupRefs() {
        return groupRefs;
    }

    // ---------- Resolution ----------

    /**
     * Deterministic pick at {@code localPos} — same {@code (worldSeed, tileIndex, localPos|lockId)}
     * → same state across reloads; locked cells share a single roll across the group, and v9
     * lock-group references are filtered for liveness and followed. {@code null} if no entry.
     */
    public @Nullable VariantState resolve(BlockPos localPos, long worldSeed, int tileIndex) {
        List<VariantState> states = entries.get(localPos);
        if (states == null || states.isEmpty()) return null;
        return pick.pick(groupRefs, localPos, lockIdAt(localPos), states, worldSeed, tileIndex);
    }

    /**
     * Difficulty-aware pick. Mob entries whose {@link VariantState#difficulty} band excludes
     * {@code tier} are dropped from the pool <b>before</b> the weighted roll, so the cell re-rolls
     * among the in-range candidates; block entries are always eligible. Returns {@code null} when
     * the cell has no candidate, or when every candidate is an out-of-band mob — the placement pass
     * then clears the cell to air and the mob pass spawns nothing.
     *
     * <p>With the default band on every entry the eligible list is the full list, so the pick is
     * bit-identical to {@link #resolve(BlockPos, long, int)}. The block pass and the deferred mob
     * pass call this with the same arguments, so they always agree.</p>
     */
    public @Nullable VariantState resolve(BlockPos localPos, long worldSeed, int tileIndex, int tier) {
        List<VariantState> states = entries.get(localPos);
        if (states == null || states.isEmpty()) return null;
        List<VariantState> eligible = inBand(states, tier);
        if (eligible.isEmpty()) return null;
        return pick.pick(groupRefs, localPos, lockIdAt(localPos), eligible, worldSeed, tileIndex);
    }

    /**
     * {@code states} without the mob entries whose difficulty band excludes {@code tier}; the list
     * itself when nothing is out of band. Also the condition scope's prefilter, so a neighbour's
     * condition judges the roll this cell really gets.
     */
    public static List<VariantState> inBand(List<VariantState> states, int tier) {
        boolean anyGated = false;
        for (VariantState s : states) {
            if (s.isMob() && !s.difficulty().eligible(tier)) { anyGated = true; break; }
        }
        if (!anyGated) return states;
        List<VariantState> eligible = new ArrayList<>(states.size());
        for (VariantState s : states) {
            if (!s.isMob() || s.difficulty().eligible(tier)) eligible.add(s);
        }
        return eligible;
    }

    // ---------- Persistence ----------

    /** The address this document is stored at, or null for a detached document. */
    public @Nullable VariantAddress address() { return address; }

    private VariantAddress requireAddress() {
        if (address == null) {
            throw new IllegalStateException("variant document " + contextId + " is detached — save it through VariantSidecarStore with an address");
        }
        return address;
    }

    /** Persist the whole document to its address's user tier — {@link VariantSidecarStore#save}. */
    public void save() throws java.io.IOException {
        VariantSidecarStore.save(requireAddress(), this);
    }

    /** Dev-mode write-through to the source tree — {@link VariantSidecarStore#saveToSource}. */
    public void saveToSource() throws java.io.IOException {
        VariantSidecarStore.saveToSource(requireAddress(), this);
    }

    // ---------- Serialisation ----------

    /** This document as the JSON text a save writes. Public alias of {@link #toJsonText} for detached carries. */
    public synchronized String asJsonText() {
        return toJsonText();
    }

    /**
     * Serialised form of this document exactly as a save writes it — hand-written so the mixed
     * array form (bare strings + objects) stays diff-clean against existing files. The mirror field
     * is emitted only when non-default, and each cell takes the object form only when it carries a
     * lock id, span or copy setting, so every sidecar authored before those fields re-saves
     * byte-identical.
     */
    public synchronized String toJsonText() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\n  \"schemaVersion\": ").append(CURRENT_SCHEMA_VERSION).append(",\n");
        if (!isDefaultMirror()) {
            sb.append("  \"mirror\": { \"x\": ").append(mirrorX)
              .append(", \"y\": ").append(mirrorY)
              .append(", \"z\": ").append(mirrorZ)
              .append(", \"v\": ").append(mirrorVariants).append(" },\n");
        }
        sb.append("  \"variants\": {");
        boolean first = true;
        for (Map.Entry<BlockPos, List<VariantState>> e : entries.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            int lockId = lockIds.getOrDefault(e.getKey(), 0);
            sb.append("\n    \"").append(formatPos(e.getKey())).append("\": ");
            CarriageVariantBlocks.appendCellJson(sb, groupRefs.refreshed(e.getValue(), lockId), lockId,
                copyRollAt(e.getKey()), copyScopeAt(e.getKey()), spanAt(e.getKey()));
        }
        sb.append("\n  }\n}\n");
        return sb.toString();
    }
}

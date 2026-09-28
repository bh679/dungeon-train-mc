package games.brennan.dungeontrain.template;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;

/**
 * A named, reusable preset of a {@link TemplateGate} — the diff-level band + worldgen dimension set
 * that defines a <b>stage of the game</b> (e.g. "the Nether stretch", "endgame"). Templates
 * (carriages / contents / tracks / parts) <b>link live</b> to a Stage by its {@link #id}: their
 * effective spawn gate is this Stage's {@link #gate}, so editing the Stage retunes every linked
 * template at once. See {@code games.brennan.dungeontrain.editor.StageStore} for the global store
 * and {@code StageStore.effectiveGate} for the resolution a linked template's {@code gateFor} uses.
 *
 * <p>The on-disk shape reuses {@link TemplateWeightCodec} so a Stage's gate JSON is byte-identical
 * to an inline template gate (and {@link TemplateGate#ALL} round-trips as {@code "all"}):</p>
 * <pre>{ "name": "deep_nether", "minLevel": 20, "maxLevel": "all", "phases": ["NETHER"],
 *   "palette": { "solid": [...], "stairs": [...], "slabs": [...], "button": "...",
 *                "pressurePlate": "...", "wood": "spruce" } }</pre>
 * The optional {@code palette} is the baked {@link StagePalette} (absent until baked).
 *
 * @param id   lowercased {@code ^[a-z0-9_]{1,32}$} token — the store key and the wire identifier.
 * @param name display label (v1: derived from {@code id}; a future free-text name can diverge).
 * @param gate the preset gate.
 * @param palette the baked stage-placeholder palette ({@code "palette"} object), or {@code null}
 *                when this stage has not been baked yet — see {@code editor.StagePaletteBaker}.
 * @param builder who authored the stage, as a template's credit reads ({@code "builder": {uuid,
 *                name}} on disk, the same codec); null when nobody is credited.
 */
public record Stage(String id, String name, TemplateGate gate, StagePalette palette, BuilderCredit builder) {

    public static final String K_NAME = "name";
    public static final String K_PALETTE = "palette";

    public Stage {
        id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        if (name == null || name.isBlank()) name = id;
        if (gate == null) gate = TemplateGate.DEFAULT;
    }

    /** Unbaked, uncredited — the pre-palette shape every existing caller and test uses. */
    public Stage(String id, String name, TemplateGate gate) {
        this(id, name, gate, null, null);
    }

    /** Baked but uncredited — the shape the palette baker builds. */
    public Stage(String id, String name, TemplateGate gate, StagePalette palette) {
        this(id, name, gate, palette, null);
    }

    /** Copy with a new gate, keeping the rest. */
    public Stage withGate(TemplateGate newGate) {
        return new Stage(id, name, newGate, palette, builder);
    }

    /** Copy with a new display name, keeping the rest. */
    public Stage withName(String newName) {
        return new Stage(id, newName, gate, palette, builder);
    }

    /** Copy with a new baked palette, keeping the rest. */
    public Stage withPalette(StagePalette newPalette) {
        return new Stage(id, name, gate, newPalette, builder);
    }

    /** Copy with a new credit (null clears it), keeping the rest. */
    public Stage withBuilder(BuilderCredit newBuilder) {
        return new Stage(id, name, gate, palette, newBuilder);
    }

    /** Serialise this Stage's value (everything except the id, which is the map key). */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty(K_NAME, name);
        TemplateWeightCodec.writeGateFields(o, gate);
        if (palette != null) o.add(K_PALETTE, palette.toJson());
        TemplateWeightCodec.writeBuilder(o, builder);
        return o;
    }

    /**
     * Parse a Stage from its store key + value. A bare/legacy value (non-object) is tolerated as a
     * default-gate Stage named after its id, so a hand-edited {@code "<id>": {}} or stray entry never
     * throws. Returns {@code null} only for a null id.
     */
    public static Stage fromJson(String id, JsonElement value) {
        if (id == null || id.isBlank()) return null;
        String key = id.trim().toLowerCase(Locale.ROOT);
        if (value == null || !value.isJsonObject()) {
            return new Stage(key, key, TemplateGate.DEFAULT);
        }
        JsonObject o = value.getAsJsonObject();
        String name = key;
        JsonElement ne = o.get(K_NAME);
        if (ne != null && ne.isJsonPrimitive() && ne.getAsJsonPrimitive().isString()
            && !ne.getAsString().isBlank()) {
            name = ne.getAsString();
        }
        return new Stage(key, name, TemplateWeightCodec.parseGate(o),
            StagePalette.fromJson(o.get(K_PALETTE)), TemplateWeightCodec.parseBuilder(o));
    }
}

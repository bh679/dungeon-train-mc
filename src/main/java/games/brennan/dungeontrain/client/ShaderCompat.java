package games.brennan.dungeontrain.client;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One place that answers "what is this shader pack, and what may Dungeon Train draw under it?"
 *
 * <p>The atmosphere systems — the band skyboxes, Skybox Blocks, the dimensional-carriage fog, its
 * sky and lighting, and the carriage transition — all reach the screen through hooks a shader pack
 * is free to honour or discard: {@code LevelRenderer#renderSky}, {@code ViewportEvent}, and the
 * vanilla lightmap. Which of those a pack actually respects is a property of the pack, not of the
 * mod, so the decision belongs in a table rather than scattered across the render classes.</p>
 *
 * <p>This sits on top of {@link GraphicsCapabilities}, which already owns the reflective Iris probe
 * — whether a pack is loaded and what it calls itself. What is added here is <em>identity</em> (a
 * stable {@link Pack} id, so a result recorded against {@code ComplementaryReimagined_r5.6.1} still
 * applies to {@code r5.6.2}) and <em>policy</em> ({@link #allows}).</p>
 *
 * <h2>The policy table is deliberately thin right now</h2>
 * <p>Only one verdict here is measured: {@link Feature#SKYBOX_BLOCKS} is off under every pack,
 * which is the behaviour {@code SkyboxPunchRenderer} already shipped and whose reasoning is written
 * up in that class. Every other feature is left exactly as it renders today, because what the
 * mainstream packs do with a {@code position_color} sky dome or a shrunken fog plane has not been
 * measured yet. The point of the table existing before those measurements is that filling it in
 * afterwards is a one-file change instead of a hunt through the render path.</p>
 *
 * <p>Client-only, and safe to call from the render thread every frame: the reflective probe behind
 * {@link GraphicsCapabilities#shaderPackName()} is cheap once resolved, and the name→{@link Pack}
 * resolution is memoised on the name string.</p>
 */
public final class ShaderCompat {

    /** The Dungeon Train systems whose visibility a shader pack can decide. */
    public enum Feature {
        /** The End / Nether / upside-down skies overlaid on the band, drawn at {@code renderSky} TAIL. */
        BAND_SKYBOX,
        /** {@code skybox_block}'s depth punch and stencil sky — see {@code SkyboxPunchRenderer}. */
        SKYBOX_BLOCKS,
        /** The dimensional carriage's fog planes — see {@code PortalRoomFogEvents}. */
        CARRIAGE_FOG,
        /** A dimensional carriage's own sky and the lightmap lift toward it. */
        CARRIAGE_SKY_LIGHTING,
        /** The lightmap hold across a portal corridor — see {@code LightTexturePortalCrossingMixin}. */
        CARRIAGE_TRANSITION
    }

    /**
     * A shader pack family. Stable across a pack's own version bumps, which is the whole point —
     * a compatibility result is a property of the family, not of the zip it was measured on.
     */
    public enum Pack {
        NONE("none"),
        COMPLEMENTARY_REIMAGINED("complementary_reimagined"),
        COMPLEMENTARY_UNBOUND("complementary_unbound"),
        SPOOKLEMENTARY("spooklementary"),
        BSL("bsl"),
        BLISS("bliss"),
        SOLAS("solas"),
        MAKEUP_ULTRA_FAST("makeup_ultra_fast"),
        FOOTAGE("footage"),
        INSANITY("insanity"),
        HYSTERIA("hysteria"),
        SILDURS("sildurs"),
        /** A pack that is loaded but not one this table knows. Its raw name is still reported. */
        UNKNOWN("unknown");

        private final String id;

        Pack(String id) {
            this.id = id;
        }

        /** Stable snake_case id, used in the compatibility matrix and in logs. */
        public String id() {
            return id;
        }
    }

    /**
     * Name fragments mapped to families, tested <b>in order</b>. Order is load-bearing twice over:
     * {@code spooklementary} contains {@code complementary} and so has to be tested before it, and
     * the two Complementary editions have to be tested before any shorter Complementary fragment
     * would swallow them.
     *
     * <p>Matched against the pack name with everything but letters and digits stripped, so
     * {@code "ComplementaryReimagined_r5.6.1 + Clrwl_1.0.3.zip"} and
     * {@code "Complementary Reimagined r5.6.2"} both land on the same family.</p>
     */
    private static final List<Map.Entry<String, Pack>> NAME_TABLE = List.of(
        Map.entry("spooklementary", Pack.SPOOKLEMENTARY),
        Map.entry("complementaryreimagined", Pack.COMPLEMENTARY_REIMAGINED),
        Map.entry("complementaryunbound", Pack.COMPLEMENTARY_UNBOUND),
        Map.entry("makeupultrafast", Pack.MAKEUP_ULTRA_FAST),
        Map.entry("makeup", Pack.MAKEUP_ULTRA_FAST),
        Map.entry("hysteria", Pack.HYSTERIA),
        Map.entry("insanity", Pack.INSANITY),
        Map.entry("footage", Pack.FOOTAGE),
        Map.entry("sildur", Pack.SILDURS),
        Map.entry("bliss", Pack.BLISS),
        Map.entry("solas", Pack.SOLAS),
        Map.entry("bsl", Pack.BSL)
    );

    /** Memo of the last resolution, so the table is walked on a pack change rather than per frame. */
    private static volatile String memoName = null;
    private static volatile Pack memoPack = Pack.NONE;

    private ShaderCompat() {}

    /** Whether any Iris/Oculus shader pack is currently rendering. */
    public static boolean active() {
        return GraphicsCapabilities.shaderPackActive();
    }

    /** The raw pack name Iris reports, or {@code ""} when none is active. */
    public static String packName() {
        return GraphicsCapabilities.shaderPackName();
    }

    /**
     * The active pack's family. {@link Pack#NONE} when no pack is rendering, {@link Pack#UNKNOWN}
     * when one is but its name matches nothing in {@link #NAME_TABLE}.
     */
    public static Pack pack() {
        if (!active()) return Pack.NONE;
        String name = packName();
        if (name == null || name.isEmpty()) return Pack.UNKNOWN;
        String memo = memoName;
        if (name.equals(memo)) return memoPack;
        Pack resolved = resolve(name);
        memoName = name;
        memoPack = resolved;
        return resolved;
    }

    /**
     * Whether {@code feature} may draw right now. Always {@code true} with no pack loaded — this
     * gate exists to answer for shader packs, and must never change the vanilla path.
     */
    public static boolean allows(Feature feature) {
        if (!active()) return true;
        return switch (feature) {
            // Measured, and the reason is written up on SkyboxPunchRenderer: the effect is a
            // colour-masked depth write, which reaches Iris' composite with cleared albedo and
            // normal and resolves black rather than sky.
            case SKYBOX_BLOCKS -> false;
            // Not yet measured. Left rendering exactly as it does today rather than pre-emptively
            // disabled — an effect a pack partly honours is worth more than a blank one.
            case BAND_SKYBOX, CARRIAGE_FOG, CARRIAGE_SKY_LIGHTING, CARRIAGE_TRANSITION -> true;
        };
    }

    /**
     * A short human-readable account of {@link #allows}' verdict, for the diagnostics panel and
     * logs. Not player-facing copy.
     */
    public static String reason(Feature feature) {
        if (!active()) return "no pack";
        if (feature == Feature.SKYBOX_BLOCKS) return "off: gbuffer punch unshaded";
        return "on: unmeasured";
    }

    /** Strip a pack name to letters and digits, then take the first table fragment it contains. */
    private static Pack resolve(String rawName) {
        StringBuilder sb = new StringBuilder(rawName.length());
        for (int i = 0; i < rawName.length(); i++) {
            char c = Character.toLowerCase(rawName.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
        }
        String normalised = sb.toString();
        for (Map.Entry<String, Pack> entry : NAME_TABLE) {
            if (normalised.contains(entry.getKey())) return entry.getValue();
        }
        return Pack.UNKNOWN;
    }

    /** {@code "bsl (BSL_v10.1.zip)"} — the family and the exact build, for a matrix cell's header. */
    public static String describe() {
        Pack p = pack();
        if (p == Pack.NONE) return "none";
        String name = packName();
        return name == null || name.isEmpty()
            ? p.id()
            : p.id() + " (" + name + ")";
    }

    /** Lowercase the pack id for a log/file-name token. Never empty. */
    public static String token() {
        return pack().id().toLowerCase(Locale.ROOT);
    }
}

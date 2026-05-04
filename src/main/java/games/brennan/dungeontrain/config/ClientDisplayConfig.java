package games.brennan.dungeontrain.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Client-scoped Forge config for purely cosmetic per-player display
 * preferences. Persists at {@code <minecraft>/config/dungeontrain-client.toml}
 * and is registered alongside {@link DungeonTrainConfig} from
 * {@link games.brennan.dungeontrain.DungeonTrain}.
 *
 * <p>Held separately from {@code DungeonTrainConfig} because that config is
 * {@code ModConfig.Type.SERVER} (per-world, server-loaded) — display scale is
 * a client-only visual choice that should follow the player across worlds.</p>
 *
 * <p>Three independent stored values:
 *   <ul>
 *     <li>{@code allScale} — master multiplier applied on top of every other
 *         display channel. Lets the player nudge everything together without
 *         touching the per-channel sliders.</li>
 *     <li>{@code worldspaceChannel} — base scale for world-space (3D) UI:
 *         X menu, editor menus, and debug labels.</li>
 *     <li>{@code hudChannel} — base scale for 2D HUD overlays (top-left
 *         version line, top-centre editor status bar).</li>
 *   </ul>
 *   Renderers consume the *effective* scales {@link #getWorldspaceScale()}
 *   and {@link #getHudScale()} which already fold {@code allScale} in.
 *   The Options menu reads/writes the raw stored values via the
 *   {@code Channel}/{@code AllScale} accessors so its three sliders behave
 *   independently — adjusting HUD does not move the All slider.</p>
 */
public final class ClientDisplayConfig {

    public static final double MIN_SCALE = 0.2;
    public static final double MAX_SCALE = 2.0;
    /** Master multiplier default — {@code 1.0} means "leave per-channel values untouched". */
    public static final double DEFAULT_ALL_SCALE = 1.0;
    /** Worldspace channel default — {@code 0.7} ships a slightly compact in-world UI; the slider lets players tune from there. */
    public static final double DEFAULT_WORLDSPACE_CHANNEL = 0.7;
    /** HUD channel default — {@code 0.4} ships a compact HUD; the slider lets players bump up if 0.4 reads too tight at their GUI scale. */
    public static final double DEFAULT_HUD_CHANNEL = 0.4;
    /** Step applied per click of the menu's {@code [-]} / {@code [+]} buttons. */
    public static final double STEP = 0.10;

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.DoubleValue ALL_SCALE;
    public static final ModConfigSpec.DoubleValue WORLDSPACE_CHANNEL;
    public static final ModConfigSpec.DoubleValue HUD_CHANNEL;

    static {
        Pair<Holder, ModConfigSpec> pair = new ModConfigSpec.Builder()
                .configure(ClientDisplayConfig::build);
        SPEC = pair.getRight();
        ALL_SCALE = pair.getLeft().allScale;
        WORLDSPACE_CHANNEL = pair.getLeft().worldspaceChannel;
        HUD_CHANNEL = pair.getLeft().hudChannel;
    }

    private ClientDisplayConfig() {}

    private static Holder build(ModConfigSpec.Builder b) {
        b.push("display");
        ModConfigSpec.DoubleValue allScale = b
                .comment("Master multiplier applied on top of both per-channel scales. 1.0 = no master tweak; bump up/down to scale every display together while keeping the per-channel sliders' relative offsets.")
                .defineInRange("allScale", DEFAULT_ALL_SCALE, MIN_SCALE, MAX_SCALE);
        ModConfigSpec.DoubleValue worldspaceChannel = b
                .comment("Base scale for world-space (3D in-world) UI and labels — X menu, editor menus, debug text. Effective scale = worldspaceChannel * allScale.")
                .defineInRange("worldspaceChannel", DEFAULT_WORLDSPACE_CHANNEL, MIN_SCALE, MAX_SCALE);
        ModConfigSpec.DoubleValue hudChannel = b
                .comment("Base scale for 2D HUD overlays (top-left version line, editor status bar). Effective scale = hudChannel * allScale.")
                .defineInRange("hudChannel", DEFAULT_HUD_CHANNEL, MIN_SCALE, MAX_SCALE);
        b.pop();
        return new Holder(allScale, worldspaceChannel, hudChannel);
    }

    /**
     * Client config loads early in the client lifecycle but isn't guaranteed
     * to be ready before the first frame draws (e.g. title-screen overlays
     * created during mod construction). Callers must guard reads through
     * the getters below; direct {@code SPEC.isLoaded()} use is fine for
     * write paths that should silently no-op pre-load.
     */
    public static boolean isLoaded() {
        return SPEC.isLoaded();
    }

    // ----- Effective getters: used by HUD overlays and world-space renderers.
    // Both fold the master multiplier in so callers don't have to. -----

    /** Effective scale for world-space UI: {@code worldspaceChannel * allScale}. */
    public static double getWorldspaceScale() {
        return getWorldspaceChannel() * getAllScale();
    }

    /** Effective scale for 2D HUD overlays: {@code hudChannel * allScale}. */
    public static double getHudScale() {
        return getHudChannel() * getAllScale();
    }

    // ----- Raw channel accessors: used by the Options menu so each slider
    // shows / mutates its own stored value without cross-channel coupling. -----

    public static double getAllScale() {
        return isLoaded() ? snapToTenth(ALL_SCALE.get()) : DEFAULT_ALL_SCALE;
    }

    public static double getWorldspaceChannel() {
        return isLoaded() ? snapToTenth(WORLDSPACE_CHANNEL.get()) : DEFAULT_WORLDSPACE_CHANNEL;
    }

    public static double getHudChannel() {
        return isLoaded() ? snapToTenth(HUD_CHANNEL.get()) : DEFAULT_HUD_CHANNEL;
    }

    public static void setAllScale(double value) {
        if (!isLoaded()) return;
        double snapped = snapToTenth(clamp(value));
        ALL_SCALE.set(snapped);
        ALL_SCALE.save();
    }

    public static void setWorldspaceChannel(double value) {
        if (!isLoaded()) return;
        double snapped = snapToTenth(clamp(value));
        WORLDSPACE_CHANNEL.set(snapped);
        WORLDSPACE_CHANNEL.save();
    }

    public static void setHudChannel(double value) {
        if (!isLoaded()) return;
        double snapped = snapToTenth(clamp(value));
        HUD_CHANNEL.set(snapped);
        HUD_CHANNEL.save();
    }

    private static double clamp(double value) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
    }

    /**
     * Round to the nearest tenth so values are always multiples of {@link #STEP}
     * (0.1) — no 0.05/0.15/0.25/… lurking from float drift after repeated steps
     * or from a stale {@code dungeontrain-client.toml} written under an older
     * config schema. Read-side snapping ensures the menu and renderers never
     * see the in-between state; write-side snapping cleans up the file.
     */
    private static double snapToTenth(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private record Holder(
            ModConfigSpec.DoubleValue allScale,
            ModConfigSpec.DoubleValue worldspaceChannel,
            ModConfigSpec.DoubleValue hudChannel
    ) {}
}

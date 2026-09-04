package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Server → client: update the editor status HUD bar with the player's current
 * category + model, the session's dev-mode flag, the variant's
 * random-selection weight, and the part-position auto-open menu flag.
 * Empty strings for both category and model clear the HUD (player is
 * outside every editor plot).
 *
 * <p>Sent only when the player's
 * (category, model, devmode, weight, partMenuEnabled, excludedContents)
 * tuple changes — no per-tick spam. See
 * {@link games.brennan.dungeontrain.editor.VariantOverlayRenderer} for the
 * detection loop.</p>
 *
 * <p>{@code model} is the friendly path string the HUD bar renders
 * ({@code track / track2}, {@code pillar / bottom / stone}). {@code modelId}
 * is the bare command-token form ({@code track}, {@code pillar_bottom}) the
 * client menu uses to dispatch {@code /dt editor ...} subcommands.
 * {@code modelName} is the bare variant name segment ({@code track2},
 * {@code stone}, {@code default}) — track-side commands take both
 * {@code <kind> <name>} so the menu needs the name independent of the path
 * string. For carriages and contents {@code modelName} equals {@code modelId}
 * (the variant id IS the name). The menu MUST use {@code modelId}/{@code modelName}
 * when constructing commands or the parser rejects the slashes/spaces in the
 * path string.</p>
 *
 * <p>{@code weight} is the variant's pick weight (0..100) when the model is in
 * a weighted category (carriages, tracks, pillars, tunnels, contents);
 * {@link #NO_WEIGHT} ({@value #NO_WEIGHT}) for any model where weight is not
 * meaningful (parts, architecture) or for the empty clear packet.</p>
 *
 * <p>{@code partMenuEnabled} mirrors the per-player auto-open flag from
 * {@link games.brennan.dungeontrain.editor.PartPositionMenuController}; the
 * editor menu's "Part Variant Menu" toggle reads this for state. Defaults
 * to {@code true} for the empty clear packet so a stale HUD never shows
 * "menu disabled" out-of-context.</p>
 *
 * <p>{@code excludedContents} is the set of content ids the active carriage
 * variant has explicitly disallowed (sourced from
 * {@link games.brennan.dungeontrain.editor.CarriageVariantContentsAllowStore}).
 * Empty for non-carriage statuses and for carriages with no exclusions. The
 * client's Contents drilldown reads this to render the per-content red/green
 * toggles.</p>
 */
public record EditorStatusPacket(String category, String model, String modelId, String modelName, boolean devmode,
                                 int weight, int minLevel, int maxLevel, int phaseMask,
                                 boolean partMenuEnabled, boolean mirrorX, boolean mirrorY, boolean mirrorZ,
                                 boolean mirrorVariants, Set<String> excludedContents, String stageId,
                                 int roomLength, int roomWidth, int roomHeight, String roomMode,
                                 int flipMask)
    implements CustomPacketPayload {

    /** Sentinel for "weight is not applicable to this model". */
    public static final int NO_WEIGHT = -1;

    /**
     * Sentinel for "this model has no authored size" — every category except portal rooms, whose
     * plots are fixed by their kind rather than chosen per variant.
     */
    public static final int NO_SIZE = -1;

    /** Sentinel for "this model has no mode" — everything but a portal room. */
    public static final String NO_MODE = "";

    /**
     * Wire length cap on {@link #roomMode}.
     *
     * <p>Sized for the tag rather than guessed at. {@code PortalRoomSettings} packs five settings
     * into one {@code /}-separated string, and its longest form is now the Copies segment carrying a
     * block id — {@code endless_open/single:<id>/tile/random:64:10/mix:99:99:99:999:999}, which at
     * {@code PortalRoomCopies.BLOCK_ID_MAX} runs to about a hundred and thirty characters. The cap
     * was 32 when the tag topped out at thirty-one, which left it one setting away from
     * {@code writeUtf} throwing on a perfectly ordinary room; 64 stopped fitting the moment Books
     * grew its author range, and 96 stopped fitting the moment Single let an author name a block.
     * This has headroom for the next segment; {@link EditorPlotLabelsPacket} carries the same tag
     * and must keep the same cap.</p>
     *
     * <p>{@code PortalRoomSettingsTest.longestTagFitsThePacket} sweeps every settings combination
     * against this number, so a future setting fails a test rather than a live {@code writeUtf}.</p>
     */
    public static final int MODE_TAG_MAX = 160;

    /** {@code maxLevel} sentinel mirroring {@code TemplateGate.ALL} — "no upper level bound". */
    public static final int MAX_LEVEL_ALL = -1;

    /** {@code phaseMask} value with all phases set; tracks {@link TrainPhase#ALL_MASK} so it grows with the enum. */
    public static final int ALL_PHASES_MASK = TrainPhase.ALL_MASK;

    /**
     * {@code flipMask} bits — which axes the focused contents template may be randomly flipped
     * along, plus the portal-room scope flag. A bitmask rather than four booleans because it is one
     * varint on the wire and one comparison in the change detector.
     *
     * <p>Meaningful only for CONTENTS models; {@link #NO_FLIP} for every other category and for the
     * clear packet. Note {@link #NO_FLIP} is not the same as "nothing enabled" — a contents template
     * defaults to X on ({@link games.brennan.dungeontrain.template.FlipOptions#DEFAULT}).</p>
     */
    public static final int FLIP_X = 1;
    public static final int FLIP_Y = 2;
    public static final int FLIP_Z = 4;
    public static final int FLIP_ROOMS = 8;

    /** Sentinel for "flip options are not applicable to this model". */
    public static final int NO_FLIP = 0;

    /** Pack a {@link games.brennan.dungeontrain.template.FlipOptions} into {@link #flipMask}. */
    public static int flipMaskOf(games.brennan.dungeontrain.template.FlipOptions flip) {
        if (flip == null) return NO_FLIP;
        return (flip.x() ? FLIP_X : 0) | (flip.y() ? FLIP_Y : 0)
            | (flip.z() ? FLIP_Z : 0) | (flip.rooms() ? FLIP_ROOMS : 0);
    }

    public EditorStatusPacket {
        excludedContents = (excludedContents == null || excludedContents.isEmpty())
            ? Collections.emptySet()
            : Set.copyOf(excludedContents);
        if (stageId == null) stageId = "";
        if (roomMode == null) roomMode = NO_MODE;
    }

    /** Back-compat constructor for Custom (unlinked) statuses — leaves {@code stageId} empty. */
    public EditorStatusPacket(String category, String model, String modelId, String modelName, boolean devmode,
                              int weight, int minLevel, int maxLevel, int phaseMask,
                              boolean partMenuEnabled, boolean mirrorX, boolean mirrorY, boolean mirrorZ,
                              boolean mirrorVariants, Set<String> excludedContents) {
        this(category, model, modelId, modelName, devmode, weight, minLevel, maxLevel, phaseMask,
            partMenuEnabled, mirrorX, mirrorY, mirrorZ, mirrorVariants, excludedContents, "");
    }

    /** Back-compat constructor for models with no authored size — everything but portal rooms. */
    public EditorStatusPacket(String category, String model, String modelId, String modelName, boolean devmode,
                              int weight, int minLevel, int maxLevel, int phaseMask,
                              boolean partMenuEnabled, boolean mirrorX, boolean mirrorY, boolean mirrorZ,
                              boolean mirrorVariants, Set<String> excludedContents, String stageId) {
        this(category, model, modelId, modelName, devmode, weight, minLevel, maxLevel, phaseMask,
            partMenuEnabled, mirrorX, mirrorY, mirrorZ, mirrorVariants, excludedContents, stageId,
            NO_SIZE, NO_SIZE, NO_SIZE, NO_MODE);
    }

    /** Back-compat constructor from before portal rooms carried a mode. */
    public EditorStatusPacket(String category, String model, String modelId, String modelName, boolean devmode,
                              int weight, int minLevel, int maxLevel, int phaseMask,
                              boolean partMenuEnabled, boolean mirrorX, boolean mirrorY, boolean mirrorZ,
                              boolean mirrorVariants, Set<String> excludedContents, String stageId,
                              int roomLength, int roomWidth, int roomHeight) {
        this(category, model, modelId, modelName, devmode, weight, minLevel, maxLevel, phaseMask,
            partMenuEnabled, mirrorX, mirrorY, mirrorZ, mirrorVariants, excludedContents, stageId,
            roomLength, roomWidth, roomHeight, NO_MODE);
    }

    /** Back-compat constructor from before contents carried flip options. */
    public EditorStatusPacket(String category, String model, String modelId, String modelName, boolean devmode,
                              int weight, int minLevel, int maxLevel, int phaseMask,
                              boolean partMenuEnabled, boolean mirrorX, boolean mirrorY, boolean mirrorZ,
                              boolean mirrorVariants, Set<String> excludedContents, String stageId,
                              int roomLength, int roomWidth, int roomHeight, String roomMode) {
        this(category, model, modelId, modelName, devmode, weight, minLevel, maxLevel, phaseMask,
            partMenuEnabled, mirrorX, mirrorY, mirrorZ, mirrorVariants, excludedContents, stageId,
            roomLength, roomWidth, roomHeight, roomMode, NO_FLIP);
    }

    public static final Type<EditorStatusPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_status"));

    public static final StreamCodec<FriendlyByteBuf, EditorStatusPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            EditorStatusPacket::decode
        );

    public static EditorStatusPacket empty() {
        return new EditorStatusPacket("", "", "", "", false, NO_WEIGHT,
            0, MAX_LEVEL_ALL, ALL_PHASES_MASK, true, false, false, false, false, Collections.emptySet(), "");
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(category);
        buf.writeUtf(model);
        buf.writeUtf(modelId);
        buf.writeUtf(modelName);
        buf.writeBoolean(devmode);
        buf.writeVarInt(weight);
        buf.writeVarInt(minLevel);
        buf.writeVarInt(maxLevel);
        buf.writeVarInt(phaseMask);
        buf.writeBoolean(partMenuEnabled);
        buf.writeBoolean(mirrorX);
        buf.writeBoolean(mirrorY);
        buf.writeBoolean(mirrorZ);
        buf.writeBoolean(mirrorVariants);
        buf.writeVarInt(excludedContents.size());
        for (String s : excludedContents) buf.writeUtf(s);
        buf.writeUtf(stageId == null ? "" : stageId, 64);
        buf.writeVarInt(roomLength);
        buf.writeVarInt(roomWidth);
        buf.writeVarInt(roomHeight);
        buf.writeUtf(roomMode == null ? NO_MODE : roomMode, MODE_TAG_MAX);
        buf.writeVarInt(flipMask);
    }

    public static EditorStatusPacket decode(FriendlyByteBuf buf) {
        String c = buf.readUtf(64);
        String m = buf.readUtf(64);
        String id = buf.readUtf(64);
        String name = buf.readUtf(64);
        boolean d = buf.readBoolean();
        int w = buf.readVarInt();
        int minLv = buf.readVarInt();
        int maxLv = buf.readVarInt();
        int phases = buf.readVarInt();
        boolean pme = buf.readBoolean();
        boolean mx = buf.readBoolean();
        boolean my = buf.readBoolean();
        boolean mz = buf.readBoolean();
        boolean mv = buf.readBoolean();
        int n = buf.readVarInt();
        Set<String> excluded;
        if (n <= 0) {
            excluded = Collections.emptySet();
        } else {
            excluded = new LinkedHashSet<>(n);
            for (int i = 0; i < n; i++) excluded.add(buf.readUtf(64));
        }
        String stageId = buf.readUtf(64);
        int rl = buf.readVarInt();
        int rw = buf.readVarInt();
        int rh = buf.readVarInt();
        String mode = buf.readUtf(MODE_TAG_MAX);
        int flip = buf.readVarInt();
        return new EditorStatusPacket(c, m, id, name, d, w, minLv, maxLv, phases, pme, mx, my, mz, mv, excluded,
            stageId, rl, rw, rh, mode, flip);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorStatusPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> EditorStatusHudOverlay.setStatus(
            packet.category, packet.model, packet.modelId, packet.modelName,
            packet.devmode, packet.weight, packet.minLevel, packet.maxLevel, packet.phaseMask,
            packet.partMenuEnabled, packet.mirrorX, packet.mirrorY, packet.mirrorZ, packet.mirrorVariants,
            packet.excludedContents, packet.stageId,
            packet.roomLength, packet.roomWidth, packet.roomHeight, packet.roomMode, packet.flipMask));
    }
}

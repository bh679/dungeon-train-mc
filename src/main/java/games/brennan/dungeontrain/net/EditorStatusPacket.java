package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
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
                                 int weight, boolean partMenuEnabled, Set<String> excludedContents)
    implements CustomPacketPayload {

    /** Sentinel for "weight is not applicable to this model". */
    public static final int NO_WEIGHT = -1;

    public EditorStatusPacket {
        excludedContents = (excludedContents == null || excludedContents.isEmpty())
            ? Collections.emptySet()
            : Set.copyOf(excludedContents);
    }

    public static final Type<EditorStatusPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_status"));

    public static final StreamCodec<FriendlyByteBuf, EditorStatusPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            EditorStatusPacket::decode
        );

    public static EditorStatusPacket empty() {
        return new EditorStatusPacket("", "", "", "", false, NO_WEIGHT, true, Collections.emptySet());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(category);
        buf.writeUtf(model);
        buf.writeUtf(modelId);
        buf.writeUtf(modelName);
        buf.writeBoolean(devmode);
        buf.writeVarInt(weight);
        buf.writeBoolean(partMenuEnabled);
        buf.writeVarInt(excludedContents.size());
        for (String s : excludedContents) buf.writeUtf(s);
    }

    public static EditorStatusPacket decode(FriendlyByteBuf buf) {
        String c = buf.readUtf(64);
        String m = buf.readUtf(64);
        String id = buf.readUtf(64);
        String name = buf.readUtf(64);
        boolean d = buf.readBoolean();
        int w = buf.readVarInt();
        boolean pme = buf.readBoolean();
        int n = buf.readVarInt();
        Set<String> excluded;
        if (n <= 0) {
            excluded = Collections.emptySet();
        } else {
            excluded = new LinkedHashSet<>(n);
            for (int i = 0; i < n; i++) excluded.add(buf.readUtf(64));
        }
        return new EditorStatusPacket(c, m, id, name, d, w, pme, excluded);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorStatusPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> EditorStatusHudOverlay.setStatus(
            packet.category, packet.model, packet.modelId, packet.modelName,
            packet.devmode, packet.weight, packet.partMenuEnabled, packet.excludedContents));
    }
}

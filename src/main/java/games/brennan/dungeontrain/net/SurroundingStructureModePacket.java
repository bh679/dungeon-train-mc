package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.surrounding.RenderMode;
import games.brennan.dungeontrain.editor.surrounding.SurroundingStructureCategory;
import games.brennan.dungeontrain.editor.surrounding.SurroundingStructureManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.EnumMap;

/**
 * Client → server: the resolved effective {@link RenderMode} per {@link SurroundingStructureCategory},
 * read from the client's own {@code ClientDisplayConfig} (global default + per-category overrides).
 * Sent on login and whenever the player changes the setting in Options while connected — mirrors
 * {@link ContentModeSyncPacket}'s shape and purpose.
 *
 * <p>Client configs aren't visible server-side by default, so this is how
 * {@link SurroundingStructureManager} learns each player's config-level default. It only <em>seeds</em>
 * the server's per-player state ({@link SurroundingStructureManager#applyClientDefaults}) — an
 * in-session {@code /dt editor surrounding} command takes precedence over it for the rest of that
 * session; see the manager's class doc for why command-driven overrides don't round-trip back into the
 * client TOML file.</p>
 */
public record SurroundingStructureModePacket(EnumMap<SurroundingStructureCategory, RenderMode> modes)
    implements CustomPacketPayload {

    public static final Type<SurroundingStructureModePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "surrounding_structure_mode"));

    public static final StreamCodec<FriendlyByteBuf, SurroundingStructureModePacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            SurroundingStructureModePacket::decode
        );

    private void encode(FriendlyByteBuf buf) {
        SurroundingStructureCategory[] categories = SurroundingStructureCategory.values();
        for (SurroundingStructureCategory category : categories) {
            RenderMode mode = modes.getOrDefault(category, RenderMode.GHOST);
            buf.writeEnum(mode);
        }
    }

    private static SurroundingStructureModePacket decode(FriendlyByteBuf buf) {
        EnumMap<SurroundingStructureCategory, RenderMode> modes =
            new EnumMap<>(SurroundingStructureCategory.class);
        for (SurroundingStructureCategory category : SurroundingStructureCategory.values()) {
            RenderMode mode = buf.readEnum(RenderMode.class);
            modes.put(category, mode);
        }
        return new SurroundingStructureModePacket(modes);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SurroundingStructureModePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            SurroundingStructureManager.applyClientDefaults(player, packet.modes());
        });
    }
}

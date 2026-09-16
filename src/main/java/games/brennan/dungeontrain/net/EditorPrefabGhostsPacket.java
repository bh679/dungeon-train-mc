package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.EditorPrefabGhostRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * S→C snapshot of the prefab ghosts standing in the editor: for every bound prefab anchor in the
 * resident category's plots, the blocks its prefab would stamp — rotated to the anchor, clipped to
 * the plot, nested anchors resolved — as absolute-position cells. The client draws them translucent.
 * An empty list clears the client cache.
 *
 * <p>Sent by {@code VariantOverlayRenderer.pushPrefabGhostsSnapshot} with a per-player dedup on
 * {@code PrefabAnchorIndex.generation()}, so a steady editor generates no traffic.</p>
 */
public record EditorPrefabGhostsPacket(List<Ghost> ghosts) implements CustomPacketPayload {

    /** One ghost cell. */
    public record Ghost(BlockPos pos, BlockState state) {}

    /** Wire cap — a snapshot past this is truncated server-side with a log line. */
    public static final int MAX_GHOSTS = 4096;

    public static final Type<EditorPrefabGhostsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_prefab_ghosts"));

    public static final StreamCodec<FriendlyByteBuf, EditorPrefabGhostsPacket> STREAM_CODEC =
        StreamCodec.of((buf, packet) -> packet.encode(buf), EditorPrefabGhostsPacket::decode);

    public static EditorPrefabGhostsPacket empty() {
        return new EditorPrefabGhostsPacket(Collections.emptyList());
    }

    public boolean isEmpty() {
        return ghosts.isEmpty();
    }

    public void encode(FriendlyByteBuf buf) {
        int n = Math.min(ghosts.size(), MAX_GHOSTS);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            Ghost g = ghosts.get(i);
            buf.writeBlockPos(g.pos());
            buf.writeVarInt(Block.BLOCK_STATE_REGISTRY.getId(g.state()));
        }
    }

    public static EditorPrefabGhostsPacket decode(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), MAX_GHOSTS);
        List<Ghost> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            BlockPos pos = buf.readBlockPos();
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(buf.readVarInt());
            if (state != null) out.add(new Ghost(pos, state));
        }
        return new EditorPrefabGhostsPacket(out);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorPrefabGhostsPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> EditorPrefabGhostRenderer.applySnapshot(packet));
    }
}

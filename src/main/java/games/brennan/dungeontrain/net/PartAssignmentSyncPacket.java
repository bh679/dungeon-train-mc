package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.train.CarriagePartAssignment;
import games.brennan.dungeontrain.train.CarriagePartAssignment.SideMode;
import games.brennan.dungeontrain.train.CarriagePartAssignment.WeightedName;
import games.brennan.dungeontrain.train.CarriagePartKind;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server → client: the part-position world-space menu's current state.
 *
 * <p>An "active" packet (kind != null) tells the client to render the
 * menu anchored at {@code anchorPos} with axes {@code anchorRight} /
 * {@code anchorUp}, listing the {@code entries} for the carriage variant
 * {@code variantId}'s {@code kind} slot. {@code registeredNames} carries
 * the full set of part templates registered for {@code kind} so the
 * client's Add-sub-menu can render its filtered picker without a
 * separate round-trip.</p>
 *
 * <p>An "inactive" packet (kind == null, signalled by writing -1 for the
 * kind ordinal) tells the client to close its menu — the player has
 * looked away from any part block, exited the editor plot, or toggled
 * the menu off.</p>
 */
public record PartAssignmentSyncPacket(
    String variantId,
    @Nullable CarriagePartKind kind,
    List<WeightedName> entries,
    Vec3 anchorPos,
    Vec3 anchorRight,
    Vec3 anchorUp,
    List<String> registeredNames
) {

    public static PartAssignmentSyncPacket empty() {
        return new PartAssignmentSyncPacket(
            "", null,
            Collections.emptyList(),
            Vec3.ZERO, Vec3.ZERO, Vec3.ZERO,
            Collections.emptyList()
        );
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(variantId);
        if (kind == null) {
            buf.writeByte(-1);
            return;
        }
        buf.writeByte(kind.ordinal());
        buf.writeVarInt(entries.size());
        for (WeightedName e : entries) {
            buf.writeUtf(e.name());
            buf.writeVarInt(e.weight());
            buf.writeByte(e.sideMode().ordinal());
        }
        writeVec3(buf, anchorPos);
        writeVec3(buf, anchorRight);
        writeVec3(buf, anchorUp);
        buf.writeVarInt(registeredNames.size());
        for (String n : registeredNames) buf.writeUtf(n);
    }

    public static PartAssignmentSyncPacket decode(FriendlyByteBuf buf) {
        String id = buf.readUtf(64);
        byte kindOrd = buf.readByte();
        if (kindOrd < 0) {
            return new PartAssignmentSyncPacket(
                id, null,
                Collections.emptyList(),
                Vec3.ZERO, Vec3.ZERO, Vec3.ZERO,
                Collections.emptyList()
            );
        }
        CarriagePartKind kind = CarriagePartKind.values()[kindOrd];
        int n = buf.readVarInt();
        List<WeightedName> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String name = buf.readUtf(64);
            int weight = buf.readVarInt();
            byte modeOrd = buf.readByte();
            SideMode mode = (modeOrd >= 0 && modeOrd < SideMode.values().length)
                ? SideMode.values()[modeOrd] : SideMode.BOTH;
            entries.add(new WeightedName(name, weight, mode));
        }
        Vec3 anchor = readVec3(buf);
        Vec3 right  = readVec3(buf);
        Vec3 up     = readVec3(buf);
        int m = buf.readVarInt();
        List<String> regs = new ArrayList<>(m);
        for (int i = 0; i < m; i++) regs.add(buf.readUtf(64));
        return new PartAssignmentSyncPacket(id, kind, entries, anchor, right, up, regs);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
            Dist.CLIENT, () -> () ->
                games.brennan.dungeontrain.client.menu.parts.PartPositionMenu.applySync(this)));
        ctx.setPacketHandled(true);
    }

    private static void writeVec3(FriendlyByteBuf buf, Vec3 v) {
        buf.writeDouble(v.x);
        buf.writeDouble(v.y);
        buf.writeDouble(v.z);
    }

    private static Vec3 readVec3(FriendlyByteBuf buf) {
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        return new Vec3(x, y, z);
    }
}

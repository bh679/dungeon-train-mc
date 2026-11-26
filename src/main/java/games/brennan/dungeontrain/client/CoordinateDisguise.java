package games.brennan.dungeontrain.client;

import dev.ryanhcode.sable.Sable;
import games.brennan.dungeontrain.worldgen.BandDimensionName;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The one answer to "what position, and what dimension, does a readout print here?" — shared by the
 * debug screen ({@code DebugScreenOverlayDepthMixin}) and every approved mod that prints the player's
 * position (JourneyMap, Xaero's Minimap and World Map, BetterF3, Jade, Sodium Extra), so none of them
 * can ever disagree with F3.
 *
 * <p>The shift is the dimensional carriage's ({@link ClientPortalRoomDepth}) where the position is in
 * one, else the Far Lands run's X ({@link FarLandsDebugX}), else none. Like F3, a readout takes the
 * <b>camera's</b> shift for every figure it prints, a targeted block or map cursor included, so a block
 * beside the player never reads as somewhere else.</p>
 *
 * <p>Every method answers "tell the truth" ({@link DebugScreenDepthLines.Shift#NONE}, or the real
 * dimension) when anything it needs is missing; callers wrap their hooks the same way, so a fault here
 * shows a true number rather than breaking someone else's HUD.</p>
 */
public final class CoordinateDisguise {

    private CoordinateDisguise() {}

    /** The shift for the camera, which is what F3 reads; {@code NONE} with no camera. */
    public static DebugScreenDepthLines.Shift cameraShift() {
        return shiftFor(Minecraft.getInstance().getCameraEntity());
    }

    /** The shift at {@code entity}'s position; {@code NONE} for null. */
    public static DebugScreenDepthLines.Shift shiftFor(Entity entity) {
        if (entity == null) return DebugScreenDepthLines.Shift.NONE;
        DebugScreenDepthLines.Shift shift =
            ClientPortalRoomDepth.shiftAt(entity.getX(), entity.getY(), entity.getZ());
        return shift.isZero() ? farLandsShift(entity) : shift;
    }

    /**
     * The X the legacy run around the Far Lands prints ({@link FarLandsDebugX}). Read from the client's
     * own cycle, as the clocks and compasses read theirs ({@code OtherworldBand}), so it needs no packet.
     */
    static DebugScreenDepthLines.Shift farLandsShift(Entity entity) {
        if (!entity.level().dimension().equals(Level.OVERWORLD) || !ClientVoidBand.startsWithTrain()) {
            return DebugScreenDepthLines.Shift.NONE;
        }
        long dx = FarLandsDebugX.offsetAt(WorldGenCycle.fromConfig(), entity.getBlockX());
        return dx == 0L ? DebugScreenDepthLines.Shift.NONE : new DebugScreenDepthLines.Shift(dx, 0, 0);
    }

    /**
     * Whether {@code (x, y, z)} is on a train carriage: a block in a Sable sub-level's plot. False with
     * no level, and on any fault, so a readout that cannot tell just prints what it was given.
     */
    public static boolean onCarriage(int x, int y, int z) {
        try {
            Level level = Minecraft.getInstance().level;
            return level != null && Sable.HELPER.getContaining(level, new BlockPos(x, y, z)) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * The position a readout prints for a targeted block: moved off Sable's plot when it is on a
     * carriage, by how far the plot sat from the train when it spawned ({@link ClientPlotReadoutOffset}),
     * then by the camera's shift, as F3's Targeted Block line.
     */
    public static BlockPos targetedBlock(BlockPos target) {
        if (target == null) return null;
        BlockPos pos = onCarriage(target.getX(), target.getY(), target.getZ())
            ? new BlockPos(MapReadoutText.fromPlot(target.getX(), ClientPlotReadoutOffset.dx()), target.getY(),
                MapReadoutText.fromPlot(target.getZ(), ClientPlotReadoutOffset.dz()))
            : target;
        return shifted(pos, cameraShift());
    }

    /** {@link #targetedBlock} for a plain {@link Vec3i}, as Jade hands it over. */
    public static Vec3i targetedBlock(Vec3i target) {
        if (target == null) return null;
        BlockPos moved = targetedBlock(new BlockPos(target));
        return new Vec3i(moved.getX(), moved.getY(), moved.getZ());
    }

    /** {@code pos} moved by {@code shift}, clamped to what an int holds ({@link MapReadoutText#add}). */
    public static BlockPos shifted(BlockPos pos, DebugScreenDepthLines.Shift shift) {
        if (pos == null || shift == null || shift.isZero()) return pos;
        return new BlockPos(MapReadoutText.add(pos.getX(), shift.x()),
            MapReadoutText.add(pos.getY(), shift.y()), MapReadoutText.add(pos.getZ(), shift.z()));
    }

    /** {@code pos} moved by {@code shift}, as a plain {@link Vec3i}. */
    public static Vec3i shifted(Vec3i pos, DebugScreenDepthLines.Shift shift) {
        if (pos == null || shift == null || shift.isZero()) return pos;
        return new Vec3i(MapReadoutText.add(pos.getX(), shift.x()),
            MapReadoutText.add(pos.getY(), shift.y()), MapReadoutText.add(pos.getZ(), shift.z()));
    }

    /** {@code pos} moved by {@code shift}. Doubles hold any shift, so nothing is clamped. */
    public static Vec3 shifted(Vec3 pos, DebugScreenDepthLines.Shift shift) {
        if (pos == null || shift == null || shift.isZero()) return pos;
        return pos.add(shift.x(), shift.y(), shift.z());
    }

    /**
     * The dimension id a readout prints, as F3 prints it: the band's name in the overworld, a
     * dimensional carriage as a dimension of its own. {@code null} means "print the real one", which
     * is the answer for every real dimension other than the overworld.
     */
    public static String shownDimensionId() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Entity camera = minecraft.getCameraEntity();
        if (level == null || camera == null || !level.dimension().equals(Level.OVERWORLD)) return null;

        boolean inCarriage =
            !ClientPortalRoomDepth.shiftAt(camera.getX(), camera.getY(), camera.getZ()).isZero();
        String shown = inCarriage ? BandDimensionName.DIMENSIONAL_CARRIAGE : ClientBandDimension.id();
        return shown == null || shown.isEmpty() ? null : shown;
    }

    /** {@link #shownDimensionId} as a location, or {@code real} when it is null or will not parse. */
    public static ResourceLocation shownDimension(ResourceLocation real) {
        String shown = shownDimensionId();
        if (shown == null) return real;
        ResourceLocation parsed = ResourceLocation.tryParse(shown);
        return parsed == null ? real : parsed;
    }
}

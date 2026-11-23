package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.EditorQuickTogglesPacket;
import games.brennan.dungeontrain.train.CarriageStampGuard;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The two world settings behind the X menu's quick toggles (the ▼ beside Settings): <b>Mirror</b> and
 * <b>Frame</b>. Menus, the third, is the per-player Editor Menus mode and lives client-side.
 *
 * <p><b>Mirror</b> is a pause switch over every template's own X / Y / Z / V axes. While it is Off no
 * edit is mirrored live — blocks, variants, Effortless Building previews, and the undo record of
 * the images — and the axes themselves are untouched, so On picks up exactly where it left off. The
 * explicit Rebuild mirror still runs: it is asked for by name. Read on every block edit in an
 * editor plot, so it is mirrored in {@link #mirrorOn} and costs one volatile read.</p>
 *
 * <p><b>Frame</b> is whether a Contents plot stands inside its carriage shell. Off leaves the shell
 * ring (floor, walls, ceiling) as air; the interior is never touched, and a contents save only ever
 * captures the interior, so nothing about the template changes. Every shell stamp in
 * {@link CarriageContentsEditor} and {@link WorkbenchEditor} asks {@link #frameOn}; a change applies
 * to every standing plot at once.</p>
 */
public final class EditorQuickToggles {

    /** Mirror of the running world's Mirror setting — the live-mirror seams' only read. */
    private static volatile boolean mirrorOn = true;
    /** Mirror of the running world's Frame setting. */
    private static volatile boolean frameOn = true;

    private EditorQuickToggles() {}

    /** False while the Mirror quick toggle has live mirroring paused. */
    public static boolean mirrorOn() {
        return mirrorOn;
    }

    /** False while Frame is Off: Contents plots stand without their carriage shell. */
    public static boolean frameOn() {
        return frameOn;
    }

    /** X menu → Mirror On / Off: persist, move the fast path, tell every client. */
    public static void setMirror(MinecraftServer server, boolean on) {
        DungeonTrainWorldData.get(server.overworld()).setEditorMirrorOn(on);
        mirrorOn = on;
        broadcast(server);
    }

    /** X menu / Settings → Frame On / Off: persist, restamp every standing shell, tell every client. */
    public static void setFrame(MinecraftServer server, boolean on) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(server.overworld());
        boolean changed = data.isEditorFrameOn() != on;
        data.setEditorFrameOn(on);
        frameOn = on;
        if (changed) {
            ServerLevel overworld = server.overworld();
            CarriageStampGuard.run(() -> {
                CarriageContentsEditor.applyFrame(overworld, data.dims());
                WorkbenchEditor.applyFrame(overworld);
            });
            // Off takes the floor out from under anyone standing in a plot: lift them first.
            if (!on) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (EditorWorldLayout.isEditorWorld(player.serverLevel())) EditorAutoFly.lift(player);
                }
            }
        }
        broadcast(server);
    }

    /** The packet every client's quick toggles and Settings row draw from. */
    public static EditorQuickTogglesPacket packet(MinecraftServer server) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(server.overworld());
        return new EditorQuickTogglesPacket(data.isEditorMirrorOn(), data.isEditorFrameOn());
    }

    /** Tell one player (login). */
    public static void sendTo(ServerPlayer player) {
        DungeonTrainNet.sendTo(player, packet(player.serverLevel().getServer()));
    }

    private static void broadcast(MinecraftServer server) {
        EditorQuickTogglesPacket packet = packet(server);
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            DungeonTrainNet.sendTo(online, packet);
        }
    }

    /** Re-read both flags from the world that just started. */
    static void sync(MinecraftServer server) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(server.overworld());
        mirrorOn = data.isEditorMirrorOn();
        frameOn = data.isEditorFrameOn();
    }

    /** No world running: the defaults. */
    static void reset() {
        mirrorOn = true;
        frameOn = true;
    }

    /**
     * Clear the one-block shell ring of the box at {@code origin} sized {@code size} — every cell of
     * the box that is not inside its interior (the box inset by one on every side). Call inside
     * {@link CarriageStampGuard}.
     */
    static void clearRing(ServerLevel level, BlockPos origin, Vec3i size) {
        BlockState air = Blocks.AIR.defaultBlockState();
        int lx = size.getX(), ly = size.getY(), lz = size.getZ();
        for (int dx = 0; dx < lx; dx++) {
            for (int dy = 0; dy < ly; dy++) {
                for (int dz = 0; dz < lz; dz++) {
                    if (!onRing(dx, dy, dz, lx, ly, lz)) continue;
                    BlockPos pos = origin.offset(dx, dy, dz);
                    net.minecraft.world.Clearable.tryClear(level.getBlockEntity(pos));
                    level.setBlock(pos, air, CarriageStampGuard.STAMP_FLAGS);
                }
            }
        }
    }

    /** True when the cell is on the box's outer layer. Pure, for the test. */
    static boolean onRing(int dx, int dy, int dz, int lx, int ly, int lz) {
        return dx == 0 || dy == 0 || dz == 0 || dx == lx - 1 || dy == ly - 1 || dz == lz - 1;
    }
}

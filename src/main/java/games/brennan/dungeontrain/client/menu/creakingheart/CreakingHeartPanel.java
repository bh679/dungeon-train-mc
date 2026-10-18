package games.brennan.dungeontrain.client.menu.creakingheart;

import games.brennan.dungeontrain.compat.CreakingHeartSettings;
import games.brennan.dungeontrain.compat.CreakingHeartSettingsHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Client state for one creaking heart's world-space settings panel, opened by right-clicking the
 * heart in Creative ({@code client/CreakingHeartMenuOpener}). Hangs off the clicked face the way
 * the Z (block-variant) menu hangs off its cell, with the same basis rule as
 * {@code BlockVariantMenuController.buildSyncPacket}.
 *
 * <p>No sync packet: the settings live on the heart's block entity, which the server re-sends
 * after every edit, so the panel reads the client copy each frame
 * ({@link CreakingHeartSettingsHolder#at}).</p>
 */
public final class CreakingHeartPanel {

    /** Beyond this the panel closes, and a face this far from the eye (a plot-space heart) floats instead. */
    static final double CLOSE_DISTANCE = 20.0;
    /** How far in front of the eye a floating panel sits. */
    private static final double FLOAT_DISTANCE = 2.0;

    public enum Popup { NONE, STAGE }

    private static boolean active;
    private static BlockPos heartPos = BlockPos.ZERO;
    private static Vec3 anchor = Vec3.ZERO;
    private static Vec3 right = new Vec3(1, 0, 0);
    private static Vec3 up = new Vec3(0, 1, 0);
    private static Vec3 normal = new Vec3(0, 0, 1);
    private static Popup popup = Popup.NONE;
    private static volatile CreakingHeartPanelLayout.Hit hovered = CreakingHeartPanelLayout.Hit.NONE;

    private CreakingHeartPanel() {}

    public static void open(BlockPos pos, Direction face) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        Vec3 eye = player.getEyePosition();
        Vec3 n = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        Vec3 a = pos.getCenter().add(n.scale(0.5 + 0.02));
        Vec3 u;
        if (eye.distanceTo(a) > CLOSE_DISTANCE) {
            // A heart on a moving carriage reports its plot-space position: float in front instead.
            Vec3 look = player.getLookAngle();
            a = eye.add(look.scale(FLOAT_DISTANCE));
            n = look.scale(-1.0).normalize();
            Vec3 worldUp = new Vec3(0, 1, 0);
            u = worldUp.subtract(n.scale(worldUp.dot(n)));
            if (u.lengthSqr() < 1.0e-4) u = new Vec3(0, 0, 1);
        } else if (face.getAxis().isHorizontal()) {
            u = new Vec3(0, 1, 0);
        } else {
            // A top or bottom face lies flat: the panel's "up" points away from the player.
            Vec3 look = player.getLookAngle();
            u = new Vec3(look.x, 0, look.z);
            if (u.lengthSqr() < 1.0e-4) u = new Vec3(0, 0, 1);
        }
        heartPos = pos.immutable();
        anchor = a;
        normal = n.normalize();
        up = u.normalize();
        right = up.cross(normal).normalize();
        popup = Popup.NONE;
        hovered = CreakingHeartPanelLayout.Hit.NONE;
        active = true;
    }

    public static void close() {
        active = false;
        popup = Popup.NONE;
        hovered = CreakingHeartPanelLayout.Hit.NONE;
    }

    public static boolean isActive() { return active; }
    public static BlockPos heartPos() { return heartPos; }
    public static Vec3 anchor() { return anchor; }
    public static Vec3 right() { return right; }
    public static Vec3 up() { return up; }
    public static Vec3 normal() { return normal; }
    public static Popup popup() { return popup; }
    public static void setPopup(Popup p) { popup = p; }

    public static CreakingHeartPanelLayout.Hit hovered() { return hovered; }
    static void setHovered(CreakingHeartPanelLayout.Hit hit) { hovered = hit; }

    /** True while the panel is open and the crosshair is on it: other world-space menus stand down. */
    public static boolean claimsPointer() {
        return active && hovered.kind() != CreakingHeartPanelLayout.Kind.NONE;
    }

    /** The heart's current settings, from the client's copy of its block entity. */
    static CreakingHeartSettings settings() {
        var level = Minecraft.getInstance().level;
        return level == null ? CreakingHeartSettings.DEFAULT : CreakingHeartSettingsHolder.at(level, heartPos);
    }

    /** The command prefix every edit runs under. */
    static String commandPrefix() {
        return "dungeontrain editor creakingheart " + heartPos.getX() + " " + heartPos.getY() + " " + heartPos.getZ();
    }
}

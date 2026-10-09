package games.brennan.dungeontrain.client.replay;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ClientPortalRoomDepth;
import games.brennan.dungeontrain.client.portal.ClientPortalSwap;
import games.brennan.dungeontrain.compat.ReplayModRecordingProbe;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * During Replay Mod (ReForgedPlay) playback, carries the free camera along when the recorded
 * player steps into or out of a dimensional carriage.
 *
 * <p>A dimensional carriage's interior is a hundred-odd blocks below the train, in the same level
 * ({@code event.PortalCarriageEvents.swapPlayers}: a relative teleport, not a dimension change).
 * On playback the recorded player's entity makes that jump in one tick and the camera — Replay
 * Mod's {@code CameraEntity}, which is {@code mc.player} while in free-camera view — stays on the
 * train filming an empty carriage. This watches the player being filmed and, on a jump that
 * {@link ReplayFollowRule} (or the portal-depth region packet, when the replay carried it) calls
 * a swap, moves the camera by the same offset. Rotation is left alone, so the shot keeps its
 * framing; {@link ClientPortalSwap#arm()} makes the destination's chunk sections rebuild on the
 * arrival frame exactly as a live swap does.</p>
 *
 * <p><b>Which player.</b> Singleplayer recordings carry no self id, so the subject is the
 * non-camera player nearest the camera, re-picked every tick until a follow happens and then held
 * for {@link #STICKY_TICKS} so a crowd on a server cannot swap the subject mid-move.</p>
 *
 * <p>Off switch: {@link ClientDisplayConfig#isReplayFollowIntoCarriages()} (Options → Dungeon
 * Train…, or the G hotkey in {@link ReplayFollowHotkeyClient}). Replay Mod itself is reached only
 * by name — {@code CameraEntity.setCameraPosition(double,double,double)} through a method handle
 * resolved once; if it is missing the feature logs once and stays off, nothing else is touched.
 * When Replay Mod is spectating the player instead of the free camera, there is nothing to do.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ReplayCarriageFollow {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String CAMERA_CLASS = "com.replaymod.replay.camera.CameraEntity";

    /** How long a followed subject stays the subject, in client ticks (5 s). */
    static final int STICKY_TICKS = 100;

    private static MethodHandle setCameraPosition;
    private static boolean handleFailed;

    private static int subjectId = -1;
    private static Vec3 lastPos;
    private static long stickyUntilTick;
    private static long tick;

    private ReplayCarriageFollow() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        tick++;
        if (!ReplayModRecordingProbe.isPresent() || handleFailed) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer camera = mc.player;
        if (camera == null || mc.level == null
            || !ReplayModRecordingProbe.isReplaying()
            || !ClientDisplayConfig.isReplayFollowIntoCarriages()
            || !CAMERA_CLASS.equals(camera.getClass().getName())) {
            reset();
            return;
        }

        Player subject = pickSubject(mc, camera);
        if (subject == null) {
            reset();
            return;
        }
        Vec3 now = subject.position();
        if (subject.getId() != subjectId) {
            subjectId = subject.getId();
            lastPos = now;
            return;
        }
        Vec3 prev = lastPos;
        lastPos = now;
        if (prev == null) return;

        boolean jump = ReplayFollowRule.isCarriageJump(prev, now)
            || (ClientPortalRoomDepth.isInsideStructure(prev.x, prev.y, prev.z)
                != ClientPortalRoomDepth.isInsideStructure(now.x, now.y, now.z)
                && prev.distanceToSqr(now) >= 16.0);
        if (!jump) return;

        Vec3 delta = now.subtract(prev);
        if (moveCamera(camera, delta)) {
            ClientPortalSwap.arm();
            stickyUntilTick = tick + STICKY_TICKS;
            LOGGER.info("[DungeonTrain] Replay camera followed the player through a dimensional carriage (dy={})",
                String.format("%.1f", delta.y));
        }
    }

    private static Player pickSubject(Minecraft mc, LocalPlayer camera) {
        if (tick < stickyUntilTick && subjectId >= 0) {
            var held = mc.level.getEntity(subjectId);
            if (held instanceof Player p && p != camera) return p;
        }
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player p : mc.level.players()) {
            if (p == camera) continue;
            double d = p.distanceToSqr(camera);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    private static boolean moveCamera(LocalPlayer camera, Vec3 delta) {
        try {
            MethodHandle h = setCameraPosition;
            if (h == null) {
                h = MethodHandles.publicLookup().findVirtual(camera.getClass(), "setCameraPosition",
                    MethodType.methodType(void.class, double.class, double.class, double.class));
                setCameraPosition = h;
            }
            h.invoke(camera, camera.getX() + delta.x, camera.getY() + delta.y, camera.getZ() + delta.z);
            return true;
        } catch (Throwable t) {
            handleFailed = true;
            LOGGER.warn("[DungeonTrain] Replay Mod's camera could not be moved — carriage follow is off for this session ({})",
                t.toString());
            return false;
        }
    }

    private static void reset() {
        subjectId = -1;
        lastPos = null;
        stickyUntilTick = 0;
    }
}

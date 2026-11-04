package games.brennan.dungeontrain.client.homeroom;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.NarrativeDeathScreen;
import games.brennan.dungeontrain.client.live.LiveFeedSource;
import games.brennan.dungeontrain.client.live.LiveStatusPoller;
import games.brennan.dungeontrain.net.DeathStatsPacket;
import games.brennan.dungeontrain.net.HomeRoomReportPacket;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import games.brennan.dungeontrain.portal.home.LastDeathReports;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Home TVs met on the train (or in the editor's test): each replays its owner's last death as the
 * recap pages, or carries their Live Feed while they are streaming. One entry per TV, from the
 * {@link HomeRoomReportPacket} that arrives as the player walks in; dropped once they are far away.
 *
 * <p>The crosshair works the pages exactly as on the death visit's TV ({@link TvScreenBinding}):
 * hover, click and wheel land on the page's own buttons; sneak-click lifts it onto the window and
 * Esc puts it back.</p>
 */
public final class HomeTvShowcase {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Blocks from the TV past which its entry is let go. */
    private static final double KEEP_RANGE = 80.0;

    /** One TV showing. */
    public record Entry(TvScreenBinding binding, NarrativeDeathScreen recap, String ownerName) {}

    private static final List<Entry> ENTRIES = new ArrayList<>();

    private HomeTvShowcase() {}

    public static void onReport(HomeRoomReportPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        BoundingBox box = BoundingBox.fromCorners(packet.screenMin(), packet.screenMax());
        dropAt(box);
        DeathStatsPacket stats = LastDeathReports.decode(packet.report(), mc.level.registryAccess());
        if (stats == null) {
            LOGGER.info("[DungeonTrain] home TV at {}: {}'s report did not decode — snow", box, packet.ownerName());
            return;
        }
        NarrativeDeathScreen recap = NarrativeDeathScreen.foreignReport(stats, packet.ownerName(), packet.deathMessage());
        ScreenToTexture texture = new ScreenToTexture(0x000000);
        texture.attach(recap);
        ENTRIES.add(new Entry(new TvScreenBinding(texture, box, packet.facing()), recap, packet.ownerName()));
        LOGGER.info("[DungeonTrain] home TV at {} replays {}'s last death", box, packet.ownerName());
    }

    @Nullable
    public static Entry at(BlockPos pos) {
        for (Entry e : ENTRIES) if (e.binding().covers(pos)) return e;
        return null;
    }

    /** The owner's stream, when they are the one on the Live Eye right now; else null. */
    @Nullable
    public static LiveFeedSource liveFor(String ownerName) {
        LiveFeedClient.Status status = LiveStatusPoller.current();
        if (status == null || ownerName.isEmpty()) return null;
        if (status.live() && ownerName.equalsIgnoreCase(status.streamer())) return LiveFeedSource.MAIN;
        LiveFeedClient.Status second = status.second();
        if (second != null && second.live() && ownerName.equalsIgnoreCase(second.streamer())) return LiveFeedSource.SECOND;
        return null;
    }

    public static void onFrameStart(float partialTick) {
        if (ENTRIES.isEmpty()) return;
        for (Entry e : ENTRIES) {
            int[] m = e.binding().zoomed() ? new int[] {TvPointer.OFF_SCREEN, TvPointer.OFF_SCREEN} : e.binding().mouse();
            e.binding().texture().renderFrame(m[0], m[1], partialTick);
        }
    }

    public static void onClientTick() {
        if (ENTRIES.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.tickCount % 40 == 0) {
            ENTRIES.removeIf(e -> {
                BoundingBox b = e.binding().screen();
                double dx = mc.player.getX() - (b.minX() + b.maxX()) / 2.0, dz = mc.player.getZ() - (b.minZ() + b.maxZ()) / 2.0;
                boolean far = dx * dx + dz * dz > KEEP_RANGE * KEEP_RANGE;
                if (far) e.binding().texture().close();
                return far;
            });
        }
        for (Entry e : ENTRIES) if (!e.binding().zoomed()) e.binding().texture().tick();
    }

    /** A click on a showing TV: its page's buttons take it; sneaking lifts the page onto the window. */
    public static boolean onTvClicked(BlockPos pos, boolean sneaking) {
        Entry e = at(pos);
        if (e == null) return false;
        Minecraft mc = Minecraft.getInstance();
        if (sneaking) {
            mc.setScreen(e.recap());
            return true;
        }
        int[] m = e.binding().mouse();
        if (m[0] == TvPointer.OFF_SCREEN) return true;
        e.recap().mouseClicked(m[0], m[1], 0);
        e.recap().mouseReleased(m[0], m[1], 0);
        // A text field takes the keyboard, as chat does on T; Enter or Esc gives it back.
        if (e.recap().textFieldFocused()) HomeTvVisit.liftForTyping(mc, e.recap());
        return true;
    }

    public static boolean onTvScrolled(double dx, double dy) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit)) return false;
        Entry e = at(hit.getBlockPos());
        if (e == null || e.binding().zoomed()) return false;
        int[] m = e.binding().mouse();
        if (m[0] == TvPointer.OFF_SCREEN) return false;
        e.recap().mouseScrolled(m[0], m[1], dx, dy);
        return true;
    }

    /** Esc on a lifted page: back down onto its TV. {@code false} when {@code screen} is not one of ours. */
    public static boolean leaveZoom(Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        for (Entry e : ENTRIES) {
            if (e.recap() != screen || mc.screen != screen) continue;
            e.recap().blurTextField();
            mc.setScreen(null);
            e.binding().texture().attach(e.recap());
            return true;
        }
        return false;
    }

    private static void dropAt(BoundingBox box) {
        ENTRIES.removeIf(e -> {
            if (!e.binding().screen().equals(box)) return false;
            e.binding().texture().close();
            return true;
        });
    }

    /** Logging out: every texture freed. Render thread. */
    public static void reset() {
        for (Entry e : ENTRIES) e.binding().texture().close();
        ENTRIES.clear();
    }
}

package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.PortalRoomSkyPacket;
import games.brennan.dungeontrain.portal.PlayerSkyRegions;
import games.brennan.dungeontrain.portal.PortalRoomSettings;
import games.brennan.dungeontrain.portal.PortalRoomSky;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerPlayer;


/**
 * Light a portal room's editor plot with the room's own Sky, the way the room will be lit once it is
 * a dimensional carriage in the world.
 *
 * <p><b>The same packet the live path sends.</b> {@code PortalCarriageEvents.sendSkyFor} describes a
 * live room's stamped box and the client lifts its lightmap inside it; nothing about that mechanism
 * cares whether the box is a room in the world or a room on a plot. So this sends the plot's box
 * through the same packet rather than growing a second way to light a room — a room authored under
 * light it will not ship with is a room authored wrong, and two implementations of the lift would be
 * two answers to what it looks like.</p>
 *
 * <p><b>One dedup memory, shared.</b> This and {@code PortalCarriageEvents} both describe the
 * client's single lit region, so both write through
 * {@link games.brennan.dungeontrain.portal.PlayerSkyRegions}. They were once assumed never to
 * describe the same player — plots stand in the sky at {@link EditorLayout#PLOT_Y}, live rooms and
 * test sessions in the basement far below — but the moment between them is real: teleporting into a
 * {@code /dt portal test} leaves the build area, so this sender took its light back <i>after</i> the
 * test's had gone out, and the test's own map still read "already sent" and never sent again.
 * Sharing the memory is what makes the order between them stop mattering.</p>
 *
 * <p><b>Optional, and only here.</b> The packet is flagged {@code editor}, which is what lets
 * {@code ClientDisplayConfig.isEditorPlotLighting} switch the plot's lift off from the editor's
 * X-menu without that reaching a live room or a {@code /dt portal test} session. The decision is the
 * client's for the same reason the Menu Distance setting's is: it is a preference about looking at
 * the editor, held per author, with no server state to keep in step.</p>
 */
public final class EditorPlotSky {

    private EditorPlotSky() {}

    /**
     * Send, or take away, the sky of the portal-room plot {@code player} is standing in.
     *
     * <p>Call once per tick per player already known to be up at the build area — the caller's
     * {@code EDITOR_Y_MIN} gate is what keeps this off the normal-play path entirely.</p>
     */
    public static void update(ServerPlayer player, CarriageDims dims) {
        PortalRoomSkyPacket region = regionFor(player, dims);
        if (region == null) {
            clear(player);
            return;
        }
        PlayerSkyRegions.send(player, region);
    }

    /**
     * The plot region to light for {@code player}, or null when there is nothing to light — they
     * are not in a portal-room plot, the room asked for no sky, or portal-room daylight is off
     * server-wide.
     */
    private static PortalRoomSkyPacket regionFor(ServerPlayer player, CarriageDims dims) {
        String name = PortalRoomEditor.plotContaining(player.blockPosition(), dims);
        if (name == null) return null;

        // The same two gates the live sender applies, so a room that is dark in the world is dark on
        // its plot and the server-wide off switch means off everywhere.
        PortalRoomSky sky = PortalRoomSettings.of(name).sky();
        if (!sky.lights() || !DungeonTrainConfig.isPortalRoomDaylight()) return null;

        BlockPos origin = PortalRoomEditor.plotOrigin(name, dims);
        if (origin == null) return null;
        Vec3i size = PortalRoomEditor.plotSize(name, dims);

        // The room's own extent, inclusive — the plot's outline cage and the margin around it are
        // not the room, and lighting them would light the gap an author walks between plots.
        return PortalRoomSkyPacket.onPlot(
            origin.getX(), origin.getY(), origin.getZ(),
            origin.getX() + size.getX() - 1,
            origin.getY() + size.getY() - 1,
            origin.getZ() + size.getZ() - 1,
            sky.ordinal());
    }

    /**
     * Take the plot's sky back off {@code player}, once.
     *
     * <p>Wired to leaving the build area as well as to walking out of a plot. The client holds a box
     * and stops applying it the moment the camera is not inside, so a dropped message costs nothing;
     * this is what keeps a stale region from being re-sent as "unchanged" later.</p>
     */
    public static void forget(ServerPlayer player) {
        clear(player);
    }

    private static void clear(ServerPlayer player) {
        PlayerSkyRegions.clear(player);
    }

    /** Wipe every player's dedup state — the integrated server has stopped. */
    public static void clearAll() {
        PlayerSkyRegions.clearAll();
    }
}

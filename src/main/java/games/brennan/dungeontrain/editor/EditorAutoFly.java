package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Settings → <b>Auto fly</b> (default On): a player falling below the editor plots' bedrock frame
 * starts flying, so a missed step off a plot, or a Frame Off that took the floor away, never ends in
 * a long drop to the void floor.
 *
 * <p>Train Editor world only, and only for a player who may already fly (creative), so no rule a
 * player lives by changes. It fires on a fall, not on being low: a player who lands somewhere below
 * the frame (a building's basement) and walks about is left alone. Outside the editor world the
 * test is one dimension check per player per tick.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EditorAutoFly {

    /** Blocks of free fall before flying cuts in — enough to ignore stepping down a ledge. */
    static final float MIN_FALL = 2.0f;

    /**
     * How long a Frame Off keeps lifting a player, in ticks. The client drops flying for a player
     * standing on the ground, and the roof under a player standing on a plot only goes a tick
     * later, so a single "start flying" is undone before the fall begins; flying is re-asserted
     * until the floor has gone.
     */
    static final int LIFT_TICKS = 20;

    /** Players a Frame Off is keeping up, and the ticks left. Server thread only. */
    private static final java.util.Map<java.util.UUID, Integer> LIFTING = new java.util.HashMap<>();

    private EditorAutoFly() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!LIFTING.isEmpty()) liftTick(player);
        if (!EditorWorldLayout.isEditorWorld(player.serverLevel())) return;
        // Its own value while the player stands in a test copy (EditorSettingMode).
        if (!EditorModeSettingsService.current().get(EditorSettingMode.of(player), EditorModeSetting.AUTO_FLY)) return;
        if (!shouldCatch(player.getY(), player.fallDistance, frameFloorY(),
                player.getAbilities().mayfly, player.getAbilities().flying)) return;
        startFlying(player);
    }

    /**
     * True when a player at {@code y} who has fallen {@code fallDistance} blocks is below the frame
     * at {@code floorY} and can be caught. Pure, for the test.
     */
    static boolean shouldCatch(double y, float fallDistance, int floorY, boolean mayFly, boolean flying) {
        return mayFly && !flying && fallDistance >= MIN_FALL && y < floorY;
    }

    /**
     * The bottom of the plots' bedrock frame: the cage layer under {@link EditorLayout#PLOT_Y}, or
     * under {@link EditorLayout#BUILDINGS_PLOT_Y} while Buildings is the category standing.
     */
    static int frameFloorY() {
        int plotY = EditorStampedCategoryState.isActive(EditorCategory.BUILDINGS)
            ? EditorLayout.BUILDINGS_PLOT_Y : EditorLayout.PLOT_Y;
        return plotY - 1;
    }

    /** Frame Off: start {@code player} flying and keep them up until the floor under them is gone. */
    public static void lift(ServerPlayer player) {
        if (!player.getAbilities().mayfly) return;
        startFlying(player);
        LIFTING.put(player.getUUID(), LIFT_TICKS);
    }

    private static void liftTick(ServerPlayer player) {
        Integer left = LIFTING.get(player.getUUID());
        if (left == null) return;
        if (left <= 0) {
            LIFTING.remove(player.getUUID());
            return;
        }
        LIFTING.put(player.getUUID(), left - 1);
        if (!player.onGround()) startFlying(player);
    }

    /** Turn flying on for {@code player} if they may fly and aren't already; tells their client. */
    public static void startFlying(ServerPlayer player) {
        if (!player.getAbilities().mayfly || player.getAbilities().flying) return;
        player.getAbilities().flying = true;
        player.fallDistance = 0;
        player.onUpdateAbilities();
    }
}

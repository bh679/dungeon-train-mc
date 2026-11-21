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
 * the frame (a building's basement) and walks about is left alone. The test is one volatile read
 * and a comparison per player per tick.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EditorAutoFly {

    /** Blocks of free fall before flying cuts in — enough to ignore stepping down a ledge. */
    static final float MIN_FALL = 2.0f;

    private EditorAutoFly() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!EditorQuickToggles.autoFlyOn()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!EditorWorldLayout.isEditorWorld(player.serverLevel())) return;
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

    /** Turn flying on for {@code player} if they may fly and aren't already; tells their client. */
    public static void startFlying(ServerPlayer player) {
        if (!player.getAbilities().mayfly || player.getAbilities().flying) return;
        player.getAbilities().flying = true;
        player.fallDistance = 0;
        player.onUpdateAbilities();
    }
}

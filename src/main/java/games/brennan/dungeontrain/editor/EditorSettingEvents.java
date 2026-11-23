package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Explosion;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Server-side half of the editor's world settings: keeps {@link EditorModeSettingsService}'s copy
 * and {@link EditorQuickToggles}' in step with whichever world is running, and cancels a TNT
 * explosion {@link EditorTnt} mutes.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EditorSettingEvents {

    private EditorSettingEvents() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        EditorModeSettingsService.sync(event.getServer());
        EditorQuickToggles.sync(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        EditorModeSettingsService.reset();
        EditorQuickToggles.reset();
    }

    /** Start, not Detonate: cancelling here also spares the entities the blast would have hurt. */
    @SubscribeEvent
    public static void onExplosionStart(ExplosionEvent.Start event) {
        Explosion explosion = event.getExplosion();
        if (!EditorTnt.isTnt(explosion.getDirectSourceEntity())) return;
        if (EditorTnt.isMuted(event.getLevel(), BlockPos.containing(explosion.center()))) {
            event.setCanceled(true);
        }
    }
}

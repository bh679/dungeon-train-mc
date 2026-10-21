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
 * Server-side half of the editor's Redstone and TNT settings: keeps {@link EditorRedstone}'s fast
 * path in step with whichever world is running, and cancels a TNT explosion {@link EditorTnt} mutes.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EditorSettingEvents {

    private EditorSettingEvents() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        EditorRedstone.sync(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        EditorRedstone.reset();
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

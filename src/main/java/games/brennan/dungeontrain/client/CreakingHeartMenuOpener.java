package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.creakingheart.CreakingHeartPanel;
import games.brennan.dungeontrain.compat.CreakingHeartMenuGate;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Opens the {@link CreakingHeartPanel} when a Creative player right-clicks a creaking heart: a
 * world-space panel pinned to the clicked face, like the Z menu.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class CreakingHeartMenuOpener {

    private CreakingHeartMenuOpener() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide()) return;
        if (!CreakingHeartMenuGate.claim(event)) return;
        if (event.getHand() == InteractionHand.MAIN_HAND) {
            Direction face = event.getFace() == null ? Direction.UP : event.getFace();
            CreakingHeartPanel.open(event.getPos(), face);
        }
    }
}

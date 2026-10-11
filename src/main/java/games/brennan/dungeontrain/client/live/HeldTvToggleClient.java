package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.vista.TvItems;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.TvHeldTogglePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;

/**
 * Left-click with a Vista TV in hand switches the set on or off instead of swinging it.
 *
 * <p>Which hand: {@link TvItems#toggleHand} — the main hand when it holds a TV, the offhand when the
 * main hand is empty. The click is taken over (no block breaking, no attack) and sent as a
 * {@link TvHeldTogglePacket}; the server flips the stack.</p>
 *
 * <p><b>Press edge only.</b> {@link InputEvent.InteractionKeyMappingTriggered} fires on the click and
 * again every tick the button is held over a block, so a held button would flip the set twenty
 * times a second. {@link ClientTickEvent.Post} runs after the input is handled and remembers whether
 * the attack key was down; an event that arrives while it already was is a repeat and is swallowed
 * (still cancelled, so the held button does not start breaking the block either).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class HeldTvToggleClient {

    private static boolean attackHeld;

    private HeldTvToggleClient() {}

    @SubscribeEvent
    public static void onAttackInput(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.isSpectator()) return;
        InteractionHand hand = TvItems.toggleHand(player.getMainHandItem(), player.getOffhandItem());
        if (hand == null) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        if (attackHeld) return;
        attackHeld = true;
        player.swing(hand);
        DungeonTrainNet.sendToServer(new TvHeldTogglePacket(hand));
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        attackHeld = mc.options.keyAttack.isDown();
    }
}

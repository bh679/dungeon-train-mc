package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.vista.TvItems;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.TvHeldTogglePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;

/**
 * Left-click with a Vista TV in hand, aimed at nothing, switches the set on or off instead of swinging.
 *
 * <p>Which hand: {@link TvItems#toggleHand} — the main hand when it holds a TV, the offhand when the
 * main hand is empty. Aimed at a block or an entity within reach the click stays a normal attack, so
 * mining and fighting with a TV in the offhand are untouched. The toggle is applied on the client at
 * once (no wait for the round trip) and sent as a {@link TvHeldTogglePacket}; the server flips the
 * real stack and the echo matches. No swing: a remote does not punch.</p>
 *
 * <p><b>Press edge only.</b> {@link InputEvent.InteractionKeyMappingTriggered} fires on the click and
 * again every tick the button is held, so {@link ClientTickEvent.Post} remembers whether the attack
 * key was already down and repeats are swallowed.</p>
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
        if (hand == null || aimingAtSomething(mc)) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        if (attackHeld) return;
        attackHeld = true;
        ItemStack held = player.getItemInHand(hand);
        player.setItemInHand(hand, TvItems.withOn(held, !TvItems.isOn(held)));
        DungeonTrainNet.sendToServer(new TvHeldTogglePacket(hand));
    }

    /** The crosshair is on a block or an entity within reach: the click is an attack, not a toggle. */
    private static boolean aimingAtSomething(Minecraft mc) {
        HitResult hit = mc.hitResult;
        return hit != null && hit.getType() != HitResult.Type.MISS;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        attackHeld = mc.options.keyAttack.isDown();
    }
}

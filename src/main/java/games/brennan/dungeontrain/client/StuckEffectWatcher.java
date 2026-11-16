package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.effect.StuckEffectSchedule;
import games.brennan.dungeontrain.net.EffectStatusQueryPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Clears status effects stuck at 00:00. The client never removes an effect itself; the server's remove
 * packet does. An effect the server never had (a mod applying it on the client as well, for a hit only the
 * client saw) therefore sits at 00:00 for good, harmless but impossible to clear, milk included. Once an
 * effect has been at zero for {@link StuckEffectSchedule#GRACE_TICKS}, this asks the server
 * ({@link EffectStatusQueryPacket}), which re-sends the effect if it is real or removes it if not.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class StuckEffectWatcher {

    private static final StuckEffectSchedule<ResourceLocation> SCHEDULE = new StuckEffectSchedule<>();
    private static LocalPlayer watched;

    private StuckEffectWatcher() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != watched) {
            SCHEDULE.clear();
            watched = player;
        }
        if (player == null || Minecraft.getInstance().getConnection() == null) return;
        List<ResourceLocation> atZero = new ArrayList<>();
        for (MobEffectInstance effect : player.getActiveEffects()) {
            if (!effect.isInfiniteDuration() && effect.getDuration() <= 0) {
                effect.getEffect().unwrapKey().ifPresent(key -> atZero.add(key.location()));
            }
        }
        List<ResourceLocation> due = SCHEDULE.due(atZero, player.tickCount);
        if (!due.isEmpty()) PacketDistributor.sendToServer(new EffectStatusQueryPacket(due));
    }
}

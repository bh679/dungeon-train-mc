package games.brennan.dungeontrain.client.discord;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ClientBandDimension;
import games.brennan.dungeontrain.client.ClientPortalRoomDepth;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.worldgen.BandDimensionName;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Feeds {@link DiscordRichPresence} from the client thread once a second: whether the switch is on (never
 * in Kid mode), and where the player is — the same dimension name the F3 screen prints, band and
 * dimensional carriage included, reduced to {@link PresenceSnapshot.Place}. Covers the title screen and
 * logging out too, since the tick keeps running with no level.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DiscordRichPresenceClient {

    private static final int PERIOD_TICKS = 20;
    private static int ticks;

    private DiscordRichPresenceClient() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (++ticks < PERIOD_TICKS) return;
        ticks = 0;
        boolean on = ClientDisplayConfig.isDiscordRichPresence() && !ClientDisplayConfig.isKidMode();
        DiscordRichPresence.setEnabled(on);
        if (!on) return;
        PresenceSnapshot.Place place = currentPlace(Minecraft.getInstance());
        DiscordRichPresence.update(new PresenceSnapshot(place, I18n.get(place.langKey())));
    }

    private static PresenceSnapshot.Place currentPlace(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        Entity camera = minecraft.getCameraEntity();
        if (level == null || camera == null) return PresenceSnapshot.Place.MENU;
        boolean inCarriage =
                !ClientPortalRoomDepth.shiftAt(camera.getX(), camera.getY(), camera.getZ()).isZero();
        String id = PresenceSnapshot.effectiveDimensionId(level.dimension().location().toString(),
                ClientBandDimension.id(), inCarriage, BandDimensionName.DIMENSIONAL_CARRIAGE);
        return PresenceSnapshot.placeFor(id);
    }
}

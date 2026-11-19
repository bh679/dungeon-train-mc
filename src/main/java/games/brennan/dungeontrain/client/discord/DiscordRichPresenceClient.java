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
 * in Kid mode), and where the player is. That is the place the F3 screen names, band included, held back by
 * {@link BandDepthGate} until the player is deep enough in a band to earn its advancement. A dimensional
 * carriage reads as "Riding the train" without touching the gate. Covers the title screen and logging out
 * too, since the tick keeps running with no level.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DiscordRichPresenceClient {

    private static final int PERIOD_TICKS = 20;
    private static int ticks;
    private static BandDepthGate gate = BandDepthGate.EMPTY;

    private DiscordRichPresenceClient() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (++ticks < PERIOD_TICKS) return;
        ticks = 0;
        boolean on = ClientDisplayConfig.isDiscordRichPresence() && !ClientDisplayConfig.isKidMode();
        DiscordRichPresence.setEnabled(on);
        if (!on) return;
        PresenceSnapshot.Place place = currentPlace(Minecraft.getInstance());
        DiscordRichPresence.update(new PresenceSnapshot(place.imageKey(), I18n.get(place.langKey())));
    }

    private static PresenceSnapshot.Place currentPlace(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        Entity camera = minecraft.getCameraEntity();
        if (level == null || camera == null) {
            gate = BandDepthGate.EMPTY;
            return PresenceSnapshot.Place.MENU;
        }
        String realId = level.dimension().location().toString();
        boolean inOverworld = PresenceSnapshot.OVERWORLD.equals(realId);
        if (inOverworld && !ClientPortalRoomDepth.shiftAt(camera.getX(), camera.getY(), camera.getZ()).isZero()) {
            return PresenceSnapshot.placeFor(BandDimensionName.DIMENSIONAL_CARRIAGE);
        }
        String id = PresenceSnapshot.effectiveDimensionId(realId, ClientBandDimension.id(), false,
                BandDimensionName.DIMENSIONAL_CARRIAGE);
        gate = gate.next(id, camera.getX(), inOverworld);
        return gate.shownId() == null ? PresenceSnapshot.Place.OTHER : PresenceSnapshot.placeFor(gate.shownId());
    }
}

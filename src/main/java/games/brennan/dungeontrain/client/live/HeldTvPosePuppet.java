package games.brennan.dungeontrain.client.live;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.CinematicCameraController;
import games.brennan.dungeontrain.compat.vista.TvItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;

/**
 * Dev tool: photographs the switched-on hand-held TV in ten candidate poses so the pose can be
 * picked by eye. Active only with {@code -Ddungeontrain.tvPoseShots=true}
 * ({@code ./gradlew runClient -PquickWorld="<save>" -PtvPoseShots}); a no-op otherwise.
 *
 * <p>Once in a world it dismisses whatever screen is up (Space, then Escape), puts an on TV in both
 * hands straight onto the integrated server's copy of the player (no cheats needed), then for each
 * pose in {@link #CANDIDATES} sets {@link HeldTvScreen#setOverride}, waits a second and writes
 * {@code screenshots/tvpose-NN.png}. It never stops the client.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class HeldTvPosePuppet {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ACTIVE = Boolean.getBoolean("dungeontrain.tvPoseShots");
    private static final int SETTLE_TICKS = 20;
    private static final ResourceLocation TV_ITEM = ResourceLocation.fromNamespaceAndPath("vista", "television");

    /** Right-hand poses (yaw, pitch, dx, dy, dz); the left hand is the mirror. */
    static final List<HeldTvScreen.Pose> CANDIDATES = List.of(
        new HeldTvScreen.Pose(0f, 0f, 0f, 0f, 0f),  // 00 baseline: Vista's own hand pose
        new HeldTvScreen.Pose(120f, -10f, 0f, 0.7f, -0.4f),  // 01 square-on, lifted
        new HeldTvScreen.Pose(135f, -10f, 0f, 0.7f, -0.4f),  // 02 turned a little inward, lifted
        new HeldTvScreen.Pose(120f, -20f, -0.3f, 0.9f, -0.5f),  // 03 higher, toward the centre, tipped back
        new HeldTvScreen.Pose(135f, 0f, 0f, 0.5f, -0.3f),  // 04 lower, flat
        new HeldTvScreen.Pose(120f, -10f, 0f, 0.9f, -0.9f),  // 05 held further away (smaller)
        new HeldTvScreen.Pose(105f, -15f, -0.2f, 0.8f, -0.3f),  // 06 closer, slightly outward
        new HeldTvScreen.Pose(150f, -10f, 0f, 0.7f, -0.4f),  // 07 turned well inward
        new HeldTvScreen.Pose(120f, -25f, -0.5f, 1.1f, -0.5f),  // 08 high and central, phone-like
        new HeldTvScreen.Pose(120f, 10f, 0f, 0.6f, -0.5f),  // 09 tipped forward
        new HeldTvScreen.Pose(135f, -15f, 0.2f, 0.7f, -0.7f)  // 10 out to the side, further away
    );

    private static int ticks;
    private static int shot;
    private static boolean armed;
    private static boolean done;

    private HeldTvPosePuppet() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ACTIVE || done) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        ticks++;
        if (CinematicCameraController.isActive()) {
            // The spawn intro flies the camera and hides the hands; skip it and wait it out.
            if (ticks % SETTLE_TICKS == 0) CinematicCameraController.skip();
            return;
        }
        if (mc.screen != null) {
            if (ticks % SETTLE_TICKS == 0) dismiss(mc);
            return;
        }
        if (!armed) {
            arm(mc);
            armed = true;
            ticks = 0;
            return;
        }
        int settle = SETTLE_TICKS * 13; // items arrive, hands settle, the Free Play chat lines fade
        if (ticks < settle || (ticks - settle) % SETTLE_TICKS != 0) return;
        if (shot > 0) grab(mc, shot - 1);
        if (shot < CANDIDATES.size()) {
            HeldTvScreen.setOverride(CANDIDATES.get(shot));
            shot++;
            return;
        }
        HeldTvScreen.setOverride(null);
        done = true;
        LOGGER.info("[tvpose] all {} shots written to screenshots/", CANDIDATES.size());
    }

    /** Space for DT's "Press Space to Start", Escape for anything else. */
    private static void dismiss(Minecraft mc) {
        if (mc.screen == null) return;
        boolean odd = (ticks / SETTLE_TICKS) % 2 == 1;
        int key = odd ? GLFW.GLFW_KEY_ESCAPE : GLFW.GLFW_KEY_SPACE;
        mc.screen.keyPressed(key, 0, 0);
        LOGGER.info("[tvpose] dismissing {} with key {}", mc.screen.getClass().getSimpleName(), key);
    }

    /** An on TV in both hands, set on the server side of the integrated server. */
    private static void arm(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) {
            LOGGER.warn("[tvpose] no integrated server; cannot arm");
            return;
        }
        UUID id = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            Item tv = BuiltInRegistries.ITEM.get(TV_ITEM);
            if (p == null) return;
            // Open, daylit air off the train: the carriage furniture sits where the hands render and
            // occludes them (the hand pass does not clear depth in this client), and night hides the rest.
            p.serverLevel().setDayTime(6000);
            p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
            p.getAbilities().flying = true;
            p.onUpdateAbilities();
            p.teleportTo(p.getX(), 200, p.getZ() + 60);
            p.setYRot(0); p.setXRot(10);
            ItemStack on = TvItems.withOn(new ItemStack(tv), true);
            p.setItemInHand(InteractionHand.MAIN_HAND, on.copy());
            p.setItemInHand(InteractionHand.OFF_HAND, on.copy());
            LOGGER.info("[tvpose] armed both hands with an on TV");
        });
    }

    private static void grab(Minecraft mc, int index) {
        String name = String.format("tvpose-%02d.png", index);
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(),
            msg -> LOGGER.info("[tvpose] {} -> {}", name, msg.getString()));
    }
}

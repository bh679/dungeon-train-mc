package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Dev-build only: a file-driven in-game screenshot. Touch {@code <gameDir>/dev-shots/<name>.request}
 * and the next whole frame is saved to {@code screenshots/<name>.png} (the game's own framebuffer,
 * so the window may sit behind other work); the request file is then deleted. Lets a headless Gate 2
 * harness that already drives the server over RCON ({@code tp}, {@code time set}) take real in-game
 * shots without synthetic keystrokes or screen capture permissions. Never active in a shipped build.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DevShotRequest {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DIR = "dev-shots";
    private static final String SUFFIX = ".request";
    private static final int POLL_TICKS = 10;

    /** Whole frames drawn after the pause menu is dismissed and the HUD hidden before the grab. */
    private static final int SETTLE_FRAMES = 5;

    private static int ticks;
    private static Path pending;
    private static int settle;
    private static boolean restoreGui;

    private DevShotRequest() {}

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (!DungeonTrain.isDevBuild() || pending != null) return;
        if (++ticks % POLL_TICKS != 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return;
        Path dir = mc.gameDirectory.toPath().resolve(DIR);
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            pending = files.filter(p -> p.getFileName().toString().endsWith(SUFFIX)).sorted().findFirst().orElse(null);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] dev-shots: cannot list {}", dir, e);
        }
        if (pending == null) return;
        // An unfocused window sits on the pause menu: a shot of that is a shot of nothing. Clear it and
        // the HUD, then let a few frames draw so the world alone is on the buffer.
        if (mc.screen != null) mc.setScreen(null);
        restoreGui = !mc.options.hideGui;
        mc.options.hideGui = true;
        settle = SETTLE_FRAMES;
    }

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Post event) {
        Path request = pending;
        if (request == null) return;
        if (settle-- > 0) return;
        pending = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getMainRenderTarget() == null) return;
        if (restoreGui) mc.options.hideGui = false;
        String file = request.getFileName().toString();
        String name = file.substring(0, file.length() - SUFFIX.length()) + ".png";
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(),
                msg -> LOGGER.info("[DungeonTrain] dev-shot {} -> {}", name, msg.getString()));
        try {
            Files.deleteIfExists(request);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] dev-shots: cannot delete {}", request, e);
        }
    }
}

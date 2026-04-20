package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.io.InputStream;
import java.util.Properties;

/**
 * Client-only HUD overlay: draws "Dungeon Train v<version> (<branch>)" in the
 * top-left corner, below the standard HUD layer. Values are resolved at build
 * time from {@code dungeontrain_version.properties} (see build.gradle
 * processResources expansion).
 *
 * <p>Respects F1 (hideGui). F3 debug overlay draws over this, which is
 * intentional.</p>
 */
@Mod.EventBusSubscriber(
        modid = DungeonTrain.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT
)
public final class VersionHudOverlay {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PROPERTIES_PATH = "/dungeontrain_version.properties";
    private static final String UNKNOWN = "?";

    private static final String VERSION;
    private static final String BRANCH;

    static {
        String version = UNKNOWN;
        String branch = UNKNOWN;
        try (InputStream in = VersionHudOverlay.class.getResourceAsStream(PROPERTIES_PATH)) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                version = props.getProperty("version", UNKNOWN);
                branch = props.getProperty("branch", UNKNOWN);
            } else {
                LOGGER.warn("Version HUD: resource {} not found — using fallback", PROPERTIES_PATH);
            }
        } catch (Exception e) {
            LOGGER.warn("Version HUD: failed to load {} — using fallback", PROPERTIES_PATH, e);
        }
        VERSION = version;
        BRANCH = branch;
    }

    private VersionHudOverlay() {}

    @SubscribeEvent
    public static void onRegisterGuiOverlays(RegisterGuiOverlaysEvent event) {
        final String text = "Dungeon Train v" + VERSION + " (" + BRANCH + ")";

        IGuiOverlay overlay = (gui, graphics, partialTick, screenWidth, screenHeight) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.options.hideGui) {
                return;
            }
            graphics.drawString(mc.font, text, 4, 4, 0xFFFFFFFF, true);
        };

        event.registerAboveAll("version_hud", overlay);
        LOGGER.info("Version HUD registered: {}", text);
    }
}

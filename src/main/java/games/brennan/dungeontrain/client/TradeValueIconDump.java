package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL13;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dev-only: renders every registered item's inventory icon to a PNG, for the relay's item-values
 * page ({@code brennan.games/dungeontrain/items/}, which shows them next to each row).
 *
 * <p>Launch with {@code ./gradlew runClient -PtradeValueIcons}. Once the title screen is up
 * (models baked, textures stitched) every item is drawn through {@link GuiGraphics#renderItem}
 * — the same path as the inventory, so blocks come out as the familiar 3D cubes and modded items
 * use their own models — into a {@value #SIZE}px offscreen target with a transparent clear, and
 * written to {@code <game dir>/trade-values-icons/<namespace>/<path>.png}. The client then
 * quits. Copy the folder to the relay's {@code public/dungeontrain/items/icons/}.</p>
 *
 * <p>Inert unless the {@code dungeontrain.tradeValueIcons} system property is set, so it costs
 * players nothing.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class TradeValueIconDump {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String PROPERTY = "dungeontrain.tradeValueIcons";
    static final String DIR_NAME = "trade-values-icons";
    /** Pixels per icon edge: 4× the 16-unit GUI item so the page can show it at 32px crisply. */
    static final int SIZE = 64;
    /** The GUI item is 16 units wide. */
    private static final float GUI_UNITS = 16f;

    private static boolean done = false;
    private static boolean announced = false;

    private TradeValueIconDump() {}

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (done || System.getProperty(PROPERTY) == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (!announced) {
            announced = true;
            LOGGER.info("[trade-values] icon dump armed; first screen render: {} overlay={}",
                event.getScreen().getClass().getSimpleName(), mc.getOverlay());
        }
        // Any menu screen after the loading overlay is gone: models are baked by then. (A dev
        // first launch parks on the network-consent screen before the TitleScreen ever shows.)
        if (mc.getOverlay() != null || mc.level != null) return;
        done = true;
        Path dir = mc.gameDirectory.toPath().resolve(DIR_NAME);
        int n = 0;
        try {
            n = dumpAll(mc, dir);
            LOGGER.info("[trade-values] wrote {} item icons to {}", n, dir.toAbsolutePath());
        } catch (Throwable t) {
            LOGGER.error("[trade-values] icon dump failed after {} icons", n, t);
        }
        if (Boolean.getBoolean(PROPERTY + ".quit")) mc.stop();
    }

    /** Renders every item to {@code dir}; returns how many were written. Restores GL state after. */
    static int dumpAll(Minecraft mc, Path dir) throws IOException {
        Files.createDirectories(dir);
        Matrix4f savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        RenderTarget target = new TextureTarget(SIZE, SIZE, true, Minecraft.ON_OSX);
        target.setClearColor(0f, 0f, 0f, 0f);
        int written = 0;
        try {
            // The GUI's own ortho projection, scaled so the 16-unit item fills the target; the
            // modelview stack already carries the GUI translation (we run inside the GUI pass).
            Matrix4f projection = new Matrix4f().setOrtho(0f, GUI_UNITS, GUI_UNITS, 0f, 1000f, ClientHooks.getGuiFarPlane());
            RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
            RenderSystem.enableDepthTest();
            GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            for (Item item : BuiltInRegistries.ITEM) {
                if (item == Items.AIR) continue;
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                // clear() unbinds the target when it is done, so bind AFTER clearing or the item
                // lands in the window's framebuffer and the PNG comes out empty.
                target.clear(Minecraft.ON_OSX);
                target.bindWrite(true);
                try {
                    graphics.renderItem(new ItemStack(item), 0, 0);
                    graphics.flush();
                } catch (Throwable t) {
                    LOGGER.warn("[trade-values] icon render failed for {}: {}", id, t.toString());
                    continue;
                }
                Path out = dir.resolve(id.getNamespace()).resolve(id.getPath() + ".png");
                Files.createDirectories(out.getParent());
                try (NativeImage image = new NativeImage(SIZE, SIZE, false)) {
                    RenderSystem.activeTexture(GL13.GL_TEXTURE0); // the item pass may leave another unit active
                    RenderSystem.bindTexture(target.getColorTextureId());
                    image.downloadTexture(0, false); // keep alpha — the page sits icons on its own background
                    image.flipY();
                    image.writeToFile(out);
                }
                written++;
            }
        } finally {
            target.destroyBuffers();
            RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
            mc.getMainRenderTarget().bindWrite(true);
        }
        return written;
    }
}

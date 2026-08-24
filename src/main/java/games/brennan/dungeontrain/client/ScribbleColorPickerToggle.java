package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.ScribbleColorPickerToggleButton;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Hides the <a href="https://www.curseforge.com/minecraft/mc-mods/scribble">Scribble</a> mod's
 * 16-swatch colour picker on the book-writing screen.
 *
 * <p>Driven by {@link ClientDisplayConfig#isScribbleColorPickerVisible()}, which defaults to
 * {@code false}, so out of the box the swatches simply are not there. There is no in-game control:
 * see {@link #SHOW_TOGGLE_BUTTON}.</p>
 *
 * <h2>Why Dungeon Train does this at all</h2>
 * <p>Scribble is bundled in the DT modpack for its page-management, undo and save/load tools, which
 * make writing a book in-game bearable. The colour picker is the one part that changes what a book
 * <em>looks</em> like rather than how it is edited, and DT wants the written page close to vanilla
 * by default. Scribble 1.5.1 has no config option for this — the swatches are built unconditionally
 * in its {@code BookEditScreenMixin.initButtons()}. (Scribble 2.x added
 * {@code showFormattingButtons}, but it needs MC 1.21.4+ and it also hides the bold/italic column,
 * which we keep.)</p>
 *
 * <h2>Why an event, and why removal rather than hiding</h2>
 * <p>Scribble registers each swatch with {@code addDrawableChild}, so they land in the screen's
 * ordinary listener list, and its {@code ColorSwatchWidget} extends vanilla {@link AbstractWidget}.
 * NeoForge fires {@link ScreenEvent.Init.Post} after {@code init()} returns while Scribble injects
 * at {@code HEAD} of it, so by the time we run the swatches exist.</p>
 *
 * <p><b>Setting {@code visible = false} does NOT work</b>, and this was shipped wrong once — Scribble
 * re-asserts visibility itself. Its {@code invalidateControlButtons}, injected at {@code HEAD} of
 * vanilla {@code BookEditScreen.updateButtons()}, runs
 * {@code for (ColorSwatchWidget swatch : colorSwatches) swatch.visible = !signing;}. That fires on
 * page insert, page delete, page change, undo/redo and entering/leaving signing mode, each time
 * undoing our hide. It re-asserts only {@code visible} and not {@code active}, so the swatches came
 * back looking live but doing nothing.</p>
 *
 * <p>Registration is the thing Scribble never re-asserts: {@code addDrawableChild} is called from
 * {@code initButtons()} alone, which runs only from its {@code init} inject. So we unregister the
 * widgets instead — {@code updateButtons()} may then flip {@code visible} on Scribble's own retained
 * list as often as it likes and nothing renders or takes clicks. The event's {@code removeListener}
 * is vanilla {@code Screen.removeWidget}, which drops the widget from {@code renderables},
 * {@code narratables} and {@code children} alike. Scribble's {@code colorSwatches} list still holds
 * live references, so {@code invalidateControlButtons} and {@code setToggled} keep working and
 * nothing NPEs.</p>
 *
 * <p>Every path that re-creates the swatches also re-runs this handler: {@code ScreenEvent.Init.Post}
 * is fired from both {@code Screen.init()} and {@code Screen.rebuildWidgets()}, so a window resize
 * re-hides them too.</p>
 *
 * <p>A mixin would be the wrong tool here twice over: the event suffices (the same reasoning
 * recorded in {@code SoundOptionsScreenTrainVolumeMixin}), and DT's mixin config is
 * {@code required: true}, so targeting a soft-dependency mod would need an
 * {@code IMixinConfigPlugin} purely to stay inert when Scribble is absent.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ScribbleColorPickerToggle {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Whether to offer the in-book {@link ScribbleColorPickerToggleButton} that flips the picker
     * back on.
     *
     * <p>Currently {@code false} — the picker is simply hidden, with no control on the book screen.
     * The button and every code path feeding it are kept intact and working behind this flag rather
     * than deleted, so restoring the in-game toggle is a one-word change if we want it back. Until
     * then the setting is still reachable by hand in {@code config/dungeontrain-client.toml}
     * ({@code [scribble] colorPickerVisible}).</p>
     */
    private static final boolean SHOW_TOGGLE_BUTTON = false;

    /** Gap between the bottom of the swatch grid and the toggle button. */
    private static final int GAP = 3;

    /** Logged once per session — a silent no-match would otherwise be undiagnosable. */
    private static boolean loggedDetection = false;

    private ScribbleColorPickerToggle() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof BookEditScreen)) return;

        List<AbstractWidget> swatches = new ArrayList<>();
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof AbstractWidget widget && isScribbleColorSwatch(widget.getClass())) {
                swatches.add(widget);
            }
        }

        if (!loggedDetection) {
            loggedDetection = true;
            LOGGER.debug("ScribbleColorPickerToggle: found {} Scribble colour swatch(es) on the book screen.",
                    swatches.size());
        }

        // No Scribble (or it renamed the widget) — nothing to hide.
        if (swatches.isEmpty()) return;

        boolean shown = ClientDisplayConfig.isScribbleColorPickerVisible();

        if (SHOW_TOGGLE_BUTTON) {
            // Positions are read BEFORE removal — an unregistered widget keeps its coordinates, but
            // reading them first keeps the button's placement independent of the branch below.
            int buttonX = toggleX(swatches);
            int buttonY = toggleY(swatches);
            event.addListener(new ScribbleColorPickerToggleButton(buttonX, buttonY, shown,
                    nowShown -> {
                        ClientDisplayConfig.setScribbleColorPickerVisible(nowShown);
                        rebuild(event.getScreen());
                    }));
        }

        if (!shown) {
            swatches.forEach(event::removeListener);
        }
    }

    /**
     * Matches Scribble's colour swatch widget <b>by class name</b>, never by import.
     *
     * <p>Scribble is a modpack companion, not a dependency — it is absent from DT's compile and dev
     * classpaths — so {@code instanceof ColorSwatchWidget} would not compile, and naming the type
     * would classload something that may not be there. The {@code startsWith}/{@code endsWith} pair
     * covers both packages the class has lived in ({@code me.chrr.scribble.gui.ColorSwatchWidget} in
     * 1.5.x, {@code me.chrr.scribble.gui.button.ColorSwatchWidget} in 2.x) so a Scribble bump does
     * not silently stop matching.</p>
     */
    private static boolean isScribbleColorSwatch(Class<?> type) {
        String name = type.getName();
        return name.startsWith("me.chrr.scribble.") && name.endsWith(".ColorSwatchWidget");
    }

    /**
     * Re-runs the screen's {@code init()} so the new setting takes effect immediately.
     *
     * <p>Needed because removal is not reversible in place: bringing the picker back means letting
     * Scribble rebuild its widgets. {@code Screen.resize} is public and routes through
     * {@code repositionElements()} to {@code rebuildWidgets()}, which fires
     * {@link ScreenEvent.Init.Post} again. {@code BookEditScreen} holds its page text in fields, so
     * a rebuild does not lose anything the player has typed.</p>
     */
    private static void rebuild(Screen screen) {
        Minecraft minecraft = Minecraft.getInstance();
        screen.resize(minecraft,
                minecraft.getWindow().getGuiScaledWidth(),
                minecraft.getWindow().getGuiScaledHeight());
    }

    /**
     * Left edge of the swatch grid. Derived from the widgets themselves rather than hardcoded, so
     * the button stays aligned across window sizes, Scribble's {@code center_book_gui} setting, and
     * any future layout change on their side.
     */
    private static int toggleX(List<AbstractWidget> swatches) {
        return swatches.stream().mapToInt(AbstractWidget::getX).min().orElseThrow();
    }

    /** Just below the grid, so the button sits in the same place whether the picker is shown or not. */
    private static int toggleY(List<AbstractWidget> swatches) {
        int bottom = swatches.stream().mapToInt(w -> w.getY() + w.getHeight()).max().orElseThrow();
        return bottom + GAP;
    }
}

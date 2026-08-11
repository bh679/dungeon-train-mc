package games.brennan.dungeontrain.client.builder;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderMirrorFlags;
import games.brennan.dungeontrain.client.DeathScreenLayoutHandler;
import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.client.VersionInfo;
import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.links.OfficialLinks;
import games.brennan.dungeontrain.client.menu.CategoryTemplatesScreen;
import games.brennan.dungeontrain.client.menu.CommandMenuState;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import games.brennan.dungeontrain.client.menu.PauseMenuActionButton;
import games.brennan.dungeontrain.client.support.SupportScreen;
import games.brennan.dungeontrain.net.BuilderDirtyRequestPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.net.URI;
import java.util.ArrayList;

/**
 * Rebuilds the pause menu for Train Builder worlds.
 *
 * <pre>
 *   Back to Game                       (vanilla)
 *   Options…         | Open to LAN     (vanilla)
 *   Discord          | Support the Mod
 *   Mods             | Feedback        (Mods is NeoForge's; Feedback runs the mod's bug report)
 *   Save and Quit to Title             (vanilla; Shift on a dev build splits in Exit Game)
 * </pre>
 *
 * <p>Beside it sits a tools column — New | Open, Save, Reset | Clear, the mirror axes, and
 * Rename | Delete. Menu and panel are positioned as one centred block, so the screen doesn't read
 * as lopsided; below {@code 204 + 8 + 98} px of GUI width the panel is dropped and the menu stays
 * exactly where vanilla put it.</p>
 *
 * <p>Advancements and Statistics have nothing to show in a build sandbox. What replaces them is
 * the building half of the world-space X menu; its gameplay-gating half — weight, levels, phases,
 * stages — stays out, since hiding that vocabulary is the whole point of Train Builder.</p>
 *
 * <p><b>Save</b> renders green whenever {@link BuilderDirtyState} reports unsaved carriages, so
 * the menu answers "do I need to save?" before you click it.</p>
 *
 * <p>Vanilla arranges its {@code GridLayout} before {@code Init.Post} fires, so this positions
 * every widget by hand off the Back-to-Game slot rather than trying to edit the grid. If the two
 * anchor buttons can't be found — another mod has already rewritten the menu — it logs and leaves
 * the screen alone rather than half-rebuilding it, matching the existing pause handlers.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class BuilderPauseMenuHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Component BACK_KEY = Component.translatable("menu.returnToGame");
    private static final Component RETURN_TO_MENU_KEY = Component.translatable("menu.returnToMenu");
    private static final Component OPTIONS_KEY = Component.translatable("menu.options");
    private static final Component LAN_KEY = Component.translatable("menu.shareToLan");
    private static final Component ADVANCEMENTS_KEY = Component.translatable("gui.advancements");
    private static final Component STATS_KEY = Component.translatable("gui.stats");
    private static final Component MODS_KEY = Component.translatable("fml.menu.mods");
    private static final Component FEEDBACK_KEY = Component.translatable("menu.sendFeedback");
    private static final Component BUGS_KEY = Component.translatable("menu.reportBugs");

    private static final Component SAVE = Component.translatable("gui.dungeontrain.builder.save");
    private static final Component NEW = Component.translatable("gui.dungeontrain.builder.new");
    private static final Component OPEN = Component.translatable("gui.dungeontrain.builder.open");

    /** Save turns green while there's work to keep — the same signal the editor's SaveAction uses. */
    private static final float SAVE_DIRTY_R = 0.35F;
    private static final float SAVE_DIRTY_G = 1.0F;
    private static final float SAVE_DIRTY_B = 0.35F;
    private static final Component DISCORD = Component.translatable("gui.dungeontrain.discord_button");
    private static final Component SUPPORT = Component.translatable("gui.dungeontrain.support.button");
    private static final Component QUIT_GAME = Component.translatable("menu.quit");
    private static final Component FEEDBACK = Component.translatable("gui.dungeontrain.builder.feedback");
    private static final Component RESET = Component.translatable("gui.dungeontrain.builder.reset");
    private static final Component CLEAR = Component.translatable("gui.dungeontrain.builder.clear");
    private static final Component RENAME = Component.translatable("gui.dungeontrain.builder.rename");
    private static final Component DELETE = Component.translatable("gui.dungeontrain.builder.delete");
    private static final Component RESET_PROMPT = Component.translatable("gui.dungeontrain.builder.confirm.reset");
    private static final Component CLEAR_PROMPT = Component.translatable("gui.dungeontrain.builder.confirm.clear");
    private static final Component DELETE_PROMPT = Component.translatable("gui.dungeontrain.builder.confirm.delete");

    /** X, Y, Z are always shown; V (variant mirroring) only while Shift is held. */
    private static final String[] MIRROR_AXES = {"x", "y", "z", "v"};

    private BuilderPauseMenuHandler() {}

    /** True while the builder pause menu owns the screen — read by the handlers it displaces. */
    public static boolean handles(Screen screen) {
        return screen instanceof PauseScreen pause
                && pause.showsPauseMenu()
                && BuilderWorldCheck.isBuilderWorld();
    }

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!handles(event.getScreen())) {
            return;
        }
        Screen screen = event.getScreen();

        Button back = findButton(event, BACK_KEY);
        Button saveAndQuit = findButton(event, RETURN_TO_MENU_KEY);
        if (back == null || saveAndQuit == null) {
            LOGGER.warn("BuilderPauseMenu: could not locate Back to Game / Save and Quit (back={}, quit={}); leaving the menu untouched.",
                    back != null, saveAndQuit != null);
            return;
        }
        OfficialLinks.ensureFetched();

        // Advancements and Statistics have nothing to show in a build sandbox. Vanilla's Feedback
        // and Report Bugs go too — they point at Mojang, and the Feedback button below points at
        // the mod's own bug report instead. Removed rather than hidden so the rows can take their
        // space. Mods stays: it moves down into the freed row.
        removeIfPresent(event, ADVANCEMENTS_KEY, STATS_KEY, FEEDBACK_KEY, BUGS_KEY);

        int slotW = back.getWidth();
        int slotH = back.getHeight();
        int firstRowY = back.getY();
        int screenW = screen.width;

        int slotX = BuilderPauseMenuLayout.mainColumnX(screenW, slotW, back.getX());
        int halfW = BuilderPauseMenuLayout.leftHalfWidth(slotW);
        int rightW = BuilderPauseMenuLayout.rightHalfWidth(slotW);
        int rightX = BuilderPauseMenuLayout.rightHalfX(slotX, slotW);
        back.setX(slotX);

        // Ask whether anything is unsaved; the reply tints Save on arrival, a frame or two later.
        DungeonTrainNet.sendToServer(new BuilderDirtyRequestPacket());

        hideVanillaTitle(event, screen);

        // What you're working on, over the main column too — the tools panel is suppressed on a
        // narrow window, and that's exactly when you can least afford to lose the one line saying
        // whether this build has been saved. No metrics here: this is identity, not inspection.
        int mainInfoH = BuilderInfoPanel.heightFor(BuilderInfoPanel.Content.IDENTITY.maxLines());
        int mainInfoY = firstRowY - BuilderPauseMenuLayout.GAP - mainInfoH;
        if (mainInfoY >= 0) {
            event.addListener(new BuilderInfoPanel(slotX, mainInfoY, slotW, mainInfoH,
                    BuilderInfoPanel.Content.IDENTITY));
        }

        // Row 1: vanilla Options | Open to LAN, moved up into the freed space.
        int y = BuilderPauseMenuLayout.rowY(firstRowY, slotH, 1);
        moveButton(findButton(event, OPTIONS_KEY), slotX, y, halfW);
        moveButton(findButton(event, LAN_KEY), rightX, y, rightW);

        // Row 2: Discord | Support the Mod — placed here rather than by PauseMenuLinksHandler,
        // which stands down in builder worlds so the two don't claim the same slots.
        y = BuilderPauseMenuLayout.rowY(firstRowY, slotH, 2);
        event.addListener(new DarkTintedButton(slotX, y, halfW, slotH, DISCORD,
                b -> openDiscord(screen)));
        event.addListener(new DarkTintedButton(rightX, y, rightW, slotH, SUPPORT,
                b -> {
                    UiAnalytics.click(UiAnalytics.SURFACE_PAUSE_MENU, UiAnalytics.TARGET_SUPPORT);
                    Minecraft.getInstance().setScreen(new SupportScreen(screen));
                }));

        // Row 3: Mods | Feedback. Feedback runs the mod's own bug-report survey, which
        // BugLogReporter attaches gzipped log tails to — a builder reporting a problem should
        // reach the mod author, not Mojang.
        y = BuilderPauseMenuLayout.rowY(firstRowY, slotH, 3);
        moveButton(findButton(event, MODS_KEY), slotX, y, halfW);
        event.addListener(new DarkTintedButton(rightX, y, rightW, slotH, FEEDBACK,
                b -> runCommand("bug")));

        // The tools panel, to the right of the whole menu.
        if (BuilderPauseMenuLayout.fitsSidePanel(screenW, slotW)) {
            addToolsPanel(event, screen,
                    BuilderPauseMenuLayout.panelX(screenW, slotW, back.getX()),
                    firstRowY, BuilderPauseMenuLayout.panelWidth(slotW), slotH);
        }

        // Row 4: Save and Quit to Title, full width — with Exit Game revealed beside it on Shift.
        y = BuilderPauseMenuLayout.rowY(firstRowY, slotH, 4);
        saveAndQuit.setX(slotX);
        saveAndQuit.setY(y);
        saveAndQuit.setWidth(slotW);

        PauseMenuActionButton quitGame = new PauseMenuActionButton(
                rightX, y, rightW, slotH, QUIT_GAME,
                0.50F, 0.50F, 0.50F, /*visibleWhenShift*/ true,
                b -> DeathScreenLayoutHandler.quitToDesktop());
        event.addListener(quitGame);
        applyShiftState(saveAndQuit, quitGame, slotX, slotW, halfW);
    }

    /**
     * Shift is a modifier, not a screen change — no init fires when it goes down — so the reveal
     * has to be reapplied every frame. {@code AbstractWidget.render} is final, which is why this
     * lives here rather than inside the widget.
     */
    @SubscribeEvent
    public static void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        if (!handles(event.getScreen())) {
            return;
        }
        boolean shift = Screen.hasShiftDown();
        Button saveAndQuit = null;
        PauseMenuActionButton quitGame = null;
        for (GuiEventListener listener : event.getScreen().children()) {
            if (listener instanceof PauseMenuActionButton action) {
                // Every Shift-swapped widget on the screen follows the same rule: Exit Game, and
                // the two mirror rows (3 axes without Shift, 4 with).
                action.visible = shift == action.visibleWhenShift();
                if (QUIT_GAME.equals(action.getMessage())) {
                    quitGame = action;
                }
            } else if (listener instanceof Button button && RETURN_TO_MENU_KEY.equals(button.getMessage())) {
                saveAndQuit = button;
            }
        }
        if (saveAndQuit == null || quitGame == null) {
            return;
        }
        int slotW = quitGame.getX() + quitGame.getWidth() - saveAndQuit.getX();
        applyShiftState(saveAndQuit, quitGame, saveAndQuit.getX(), slotW,
                BuilderPauseMenuLayout.leftHalfWidth(slotW));
    }

    /** Full-width Save and Quit, or a half-width pair with Exit Game when Shift reveals it. */
    private static void applyShiftState(Button saveAndQuit, PauseMenuActionButton quitGame,
                                        int slotX, int slotW, int halfW) {
        boolean reveal = BuilderPauseMenuLayout.shouldRevealExitGame(
                VersionInfo.BRANCH, Screen.hasShiftDown());
        saveAndQuit.setX(slotX);
        saveAndQuit.setWidth(reveal ? halfW : slotW);
    }

    /**
     * The tools column: the building actions, then the destructive ones, then shape, then naming.
     *
     * <p>Grouped with a blank row between sections so a misclick on Clear or Delete isn't one
     * pixel away from Save. Both of those, and Reset when there's unsaved work, go through
     * vanilla's {@link net.minecraft.client.gui.screens.ConfirmScreen} first.</p>
     */
    private static void addToolsPanel(ScreenEvent.Init.Post event, Screen screen,
                                      int x, int firstRowY, int width, int height) {
        int halfW = BuilderPauseMenuLayout.leftHalfWidth(width);
        int rightW = BuilderPauseMenuLayout.rightHalfWidth(width);
        int rightX = BuilderPauseMenuLayout.rightHalfX(x, width);
        int rowStride = height + BuilderPauseMenuLayout.GAP;
        int y = firstRowY;

        // The build's measurements, above the tools that act on them. Only the measurements: the
        // name, type and stage are already stated over the main column two panels to the left, and
        // repeating them here would spend the space saying nothing new.
        int infoH = BuilderInfoPanel.heightFor(BuilderInfoPanel.Content.METRICS.maxLines());
        int infoY = firstRowY - BuilderPauseMenuLayout.GAP - infoH;
        if (infoY >= 0) {
            event.addListener(new BuilderInfoPanel(x, infoY, width, infoH,
                    BuilderInfoPanel.Content.METRICS));
        }

        // Do: make something, open something, keep it.
        event.addListener(new DarkTintedButton(x, y, halfW, height, NEW,
                b -> Minecraft.getInstance().setScreen(new BuilderNewScreen(screen))));
        event.addListener(new DarkTintedButton(rightX, y, rightW, height, OPEN,
                b -> Minecraft.getInstance().setScreen(new BuilderOpenScreen(screen))));
        y += rowStride;

        event.addListener(new BuilderSaveButton(x, y, width, height, SAVE,
                b -> save(screen)));
        y += rowStride + BuilderPauseMenuLayout.GROUP_GAP;

        // Undo — both discard work, so both ask first when there is any.
        event.addListener(new DarkTintedButton(x, y, halfW, height, RESET,
                b -> confirmIfDirty(screen, RESET_PROMPT, "dungeontrain reset")));
        event.addListener(new DarkTintedButton(rightX, y, rightW, height, CLEAR,
                b -> confirm(screen, CLEAR_PROMPT, "dungeontrain editor clear")));
        y += rowStride + BuilderPauseMenuLayout.GROUP_GAP;

        // Shape.
        addMirrorRow(event, x, y, width, height);
        y += rowStride + BuilderPauseMenuLayout.GROUP_GAP;

        // Manage.
        event.addListener(new DarkTintedButton(x, y, halfW, height, RENAME,
                b -> openMenu(new CategoryTemplatesScreen(EditorStatusHudOverlay.category()))));
        event.addListener(new DarkTintedButton(rightX, y, rightW, height, DELETE,
                b -> confirm(screen, DELETE_PROMPT,
                        "dungeontrain editor reset " + EditorStatusHudOverlay.modelId())));
    }

    /**
     * The mirror axes: X Y Z normally, X Y Z V while Shift is held.
     *
     * <p>V is the odd one out — variant mirroring is on by default in a builder world (set on the
     * carriage sidecar at stamp time), so it's a thing you turn <em>off</em> occasionally rather
     * than a thing you reach for. Keeping it behind Shift leaves three evenly-sized cells for the
     * axes people actually toggle.</p>
     *
     * <p>Both rows are built up front and swapped by visibility rather than resized in place:
     * three cells and four cells divide the panel differently, and {@code PauseMenuActionButton}
     * already carries exactly this Shift flag for the Exit Game button.</p>
     */
    private static void addMirrorRow(ScreenEvent.Init.Post event, int x, int y, int width, int height) {
        addMirrorCells(event, x, y, width, height, 3, /*visibleWhenShift*/ false);
        addMirrorCells(event, x, y, width, height, 4, /*visibleWhenShift*/ true);
    }

    /**
     * {@code count} equal cells spanning the panel, each tinted green while that axis mirrors —
     * the only state readout there's room for at this width.
     *
     * <p>These are the one row here that keeps the menu open on click; the state they read and the
     * reason are {@link BuilderMirrorButton}'s.</p>
     */
    private static void addMirrorCells(ScreenEvent.Init.Post event, int x, int y, int width, int height,
                                       int count, boolean visibleWhenShift) {
        int cellW = BuilderPauseMenuLayout.mirrorCellWidth(width, count);
        for (int i = 0; i < count; i++) {
            event.addListener(new BuilderMirrorButton(
                    BuilderPauseMenuLayout.mirrorCellX(x, width, count, i), y,
                    BuilderPauseMenuLayout.mirrorCellWidthAt(width, count, i), height,
                    MIRROR_AXES[i],
                    visibleWhenShift));
            if (cellW <= 0) {
                break;   // defensive: a panel too narrow to divide
            }
        }
    }

    /**
     * Hide the vanilla "Game Menu" heading, whose space the build read-out takes.
     *
     * <p>{@code PauseScreen.init} adds the title as a plain {@link StringWidget} at y=40 — a
     * renderable like any other — so this is a visibility flag rather than the mixin it first looked
     * like. Matched by identity against {@code screen.getTitle()} instead of by position or by being
     * the only StringWidget, so another mod adding one of its own can't be hidden by mistake.</p>
     *
     * <p>It's also the least useful text on the screen: you know you opened the pause menu, whereas
     * whether the build has been saved is what you came to find out.</p>
     */
    private static void hideVanillaTitle(ScreenEvent.Init.Post event, Screen screen) {
        for (GuiEventListener listener : event.getScreen().children()) {
            if (listener instanceof StringWidget widget
                    && screen.getTitle().equals(widget.getMessage())) {
                widget.visible = false;
                return;
            }
        }
    }

    private static void moveButton(Button button, int x, int y, int width) {
        if (button == null) {
            return;   // another mod removed it, or this vanilla row isn't present
        }
        button.setX(x);
        button.setY(y);
        button.setWidth(width);
    }

    /** Ask first, then run. Used for anything that destroys blocks. */
    private static void confirm(Screen parent, Component prompt, String command) {
        Minecraft.getInstance().setScreen(new ConfirmScreen(yes -> {
            if (yes) {
                runCommand(command);
            } else {
                Minecraft.getInstance().setScreen(parent);
            }
        }, prompt, Component.empty()));
    }

    /** Reset only destroys something when there's unsaved work — ask in that case, act otherwise. */
    private static void confirmIfDirty(Screen parent, Component prompt, String command) {
        if (BuilderDirtyState.hasUnsavedChanges()) {
            confirm(parent, prompt, command);
        } else {
            runCommand(command);
        }
    }

    /**
     * Save the builder's carriage — position-independent, unlike the editor's own save.
     *
     * <p>A build with no name has nowhere to be written, so the first save asks for one, using the
     * New screen with its other choices already filled in and locked.</p>
     */
    private static void save(Screen parent) {
        if (BuilderBoundsState.isDraft()) {
            Minecraft.getInstance().setScreen(BuilderNewScreen.saveAs(parent));
            return;
        }
        Minecraft.getInstance().setScreen(null);
        DungeonTrainNet.sendToServer(new games.brennan.dungeontrain.net.BuilderSavePacket());
    }

    private static void runCommand(String command) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(null);   // unpause first, so the integrated server can process it
        if (mc.player != null) {
            mc.player.connection.sendCommand(command);
        }
    }

    private static void openMenu(games.brennan.dungeontrain.client.menu.MenuScreen target) {
        Minecraft.getInstance().setScreen(null);
        CommandMenuState.openAt(target);
    }

    private static void openDiscord(Screen parent) {
        UiAnalytics.click(UiAnalytics.SURFACE_PAUSE_MENU, UiAnalytics.TARGET_DISCORD);
        String url = OfficialLinks.discord();
        Minecraft.getInstance().setScreen(new ConfirmLinkScreen(yes -> {
            UiAnalytics.confirm(UiAnalytics.SURFACE_PAUSE_MENU, UiAnalytics.TARGET_DISCORD, yes);
            if (yes) {
                Util.getPlatform().openUri(URI.create(url));
            }
            Minecraft.getInstance().setScreen(parent);
        }, url, true));
    }

    private static void removeIfPresent(ScreenEvent.Init.Post event, Component... keys) {
        for (Component key : keys) {
            Button button = findButton(event, key);
            if (button != null) {
                event.removeListener(button);
            }
        }
    }

    /**
     * Save, tinted green while the builder has unsaved changes.
     *
     * <p>The tint is read per frame rather than fixed at construction because the answer arrives
     * from the server after the screen is already built — and the player may save from elsewhere
     * while the menu is open.</p>
     */
    private static final class BuilderSaveButton extends DarkTintedButton {

        private static final WidgetSprites SPRITES = new WidgetSprites(
                ResourceLocation.withDefaultNamespace("widget/button"),
                ResourceLocation.withDefaultNamespace("widget/button_disabled"),
                ResourceLocation.withDefaultNamespace("widget/button_highlighted"));

        BuilderSaveButton(int x, int y, int width, int height, Component message, OnPress onPress) {
            super(x, y, width, height, message, onPress);
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            if (!BuilderDirtyState.hasUnsavedChanges()) {
                super.renderWidget(g, mouseX, mouseY, partialTick);   // plain, nothing to save
                return;
            }
            g.setColor(SAVE_DIRTY_R, SAVE_DIRTY_G, SAVE_DIRTY_B, this.alpha);
            RenderSystem.enableBlend();
            RenderSystem.enableDepthTest();
            g.blitSprite(SPRITES.get(this.active, this.isHoveredOrFocused()),
                    this.getX(), this.getY(), this.getWidth(), this.getHeight());
            g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            this.renderString(g, Minecraft.getInstance().font,
                    0xFFFFFF | Mth.ceil(this.alpha * 255.0F) << 24);
        }
    }

    private static Button findButton(ScreenEvent.Init.Post event, Component message) {
        for (GuiEventListener listener : new ArrayList<>(event.getListenersList())) {
            if (listener instanceof Button button && message.equals(button.getMessage())) {
                return button;
            }
        }
        return null;
    }
}

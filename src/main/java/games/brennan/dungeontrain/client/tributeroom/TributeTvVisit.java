package games.brennan.dungeontrain.client.tributeroom;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.DeathScreenLayoutHandler;
import games.brennan.dungeontrain.client.perks.PerksHud;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.TributeRoomVisitEndPacket;
import games.brennan.dungeontrain.net.TributeRoomVisitPacket;
import games.brennan.dungeontrain.net.TributeRoomVisitStatePacket;
import net.mehvahdjukaar.vista.common.tv.TVBlockEntity;
import net.minecraft.client.Minecraft;
import games.brennan.dungeontrain.client.NarrativeDeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.List;
import java.util.function.Supplier;

/**
 * The client side of a tribute room visit, as a state machine:
 *
 * <pre>
 * IDLE → AWAITING_ROOM (the perks menu stays on the window while the server stands the room behind it)
 *      → FADING_IN (the menu dissolves into the TV showing the same picture, FOV eases out of it)
 *      → WATCHING (first person, TV on; walking into a tube darkens the view with the distance)
 *      → ENDING (Board anew sent, or a tube's far end reached; black until the save) → IDLE
 * </pre>
 *
 * <p>The TV shows whatever {@link ScreenToTexture} is drawing: the perks menu exactly as it was left,
 * then the recap, page by page, each right-click on the TV the next one ({@link #onTvClicked}). A page
 * that needs input comes up on the window and goes back down with Esc; Board anew comes back through
 * {@link #endRun}. A server that never answers hands the player the recap instead
 * ({@link #ROOM_TIMEOUT_MS}), so nobody waits on black.</p>
 */
public final class TributeTvVisit {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final long ROOM_TIMEOUT_MS = 20_000L;
    /** The menu held after the room is in, before it dissolves into the TV. */
    static final long SETTLE_MS = 600L;
    /**
     * How far into a tube, as a fraction of its length, the view is fully black and the visit ends.
     * The far end is a wall the player's own width keeps them a few tenths of a block short of, so
     * this sits well inside what they can reach.
     */
    static final float TUBE_END_FRACTION = 0.8f;
    /** What the picture is taken to be when no texture is up: the TV's own 3:2. */
    static final float TV_ASPECT = 1.5f;

    enum Phase { IDLE, AWAITING_ROOM, FADING_IN, WATCHING, ENDING }

    /** The pause menu's ways out of a visit: to the title screen, or out of the game. */
    enum Leaving { NONE, TITLE, QUIT }

    private static Phase phase = Phase.IDLE;
    private static long phaseAtMs;
    @Nullable private static Supplier<Screen> recap;
    @Nullable private static ScreenToTexture texture;
    @Nullable private static NarrativeDeathScreen recapScreen;
    /** The perks menu the visit opened on, kept so the TV can go back to it from the recap's last page. */
    @Nullable private static Screen perksScreen;
    @Nullable private static BoundingBox screenBox;
    private static List<BoundingBox> tubes = List.of();
    /** How black the tube walk has the view, 0..1; held at 1 once a far end has ended the visit. */
    private static float tubeBlack;
    private static net.minecraft.core.Direction screenFacing = net.minecraft.core.Direction.NORTH;
    @Nullable private static Screen pendingHost;
    private static boolean pendingKeepMode;
    /** Where the pause menu's way out goes once the room is saved; NONE while Board anew owns the ending. */
    private static Leaving pendingLeave = Leaving.NONE;
    private static long readyAtMs = -1L;

    private TributeTvVisit() {}

    // ---- entry ---------------------------------------------------------------------

    /**
     * Next pressed on the perks menu while dead: the menu stays exactly as it is on the window (drawn
     * from the TV's own texture from here) while the server is asked for the room and stands it
     * behind. Nothing goes black: the first change the player sees is the menu dissolving into the TV.
     */
    public static void begin(Screen perks, Supplier<Screen> next) {
        Minecraft mc = Minecraft.getInstance();
        reset();
        recap = next;
        texture = new ScreenToTexture(games.brennan.dungeontrain.client.perks.PerksScreen.SKY_BG);
        texture.attach(perks);
        perksScreen = perks;
        setPhase(Phase.AWAITING_ROOM);
        mc.setScreen(new TributeArrivalScreen());
        DungeonTrainNet.sendToServer(new TributeRoomVisitPacket(false));
    }

    /**
     * The perks menu just opened on a death: have the server stand the room now, behind the menu, so
     * Next moves straight in. Harmless to repeat; the server stands it once per death.
     */
    public static void prepare() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null || !mc.player.isDeadOrDying()) return;
        DungeonTrainNet.sendToServer(new TributeRoomVisitPacket(true));
    }

    /** The death perks menu is still on the window while the room is stood up behind it. */
    public static boolean menuOnWindow() {
        return phase == Phase.AWAITING_ROOM;
    }

    public static boolean isActive() {
        return phase != Phase.IDLE;
    }

    /** Whether the TV at {@code pos} is this visit's TV, so it shows the picture rather than snow. */
    public static boolean showsOn(BlockPos pos) {
        return screenBox != null && texture != null && phase != Phase.IDLE && screenBox.isInside(pos);
    }

    static float pictureAspect() {
        return texture == null ? TV_ASPECT : texture.aspect();
    }

    /** The visit's texture, for the TV that shows it. Null when nothing is up. */
    @Nullable
    static net.minecraft.resources.ResourceLocation textureLocation() {
        return texture == null ? null : texture.location();
    }

    /** How opaque the menu picture is over the window now: whole until the room is in, then dissolving. */
    static float overlayAlpha(long nowMs) {
        return switch (phase) {
            case AWAITING_ROOM -> 1f;
            case FADING_IN -> 1f - Mth.clamp((nowMs - phaseAtMs) / (float) TributeArrivalScreen.FADE_IN_MS, 0f, 1f);
            default -> 0f;
        };
    }

    /** How black the view is from walking into a tube, 0..1 (1 and held once the far end ended the visit). */
    public static float tubeBlack() {
        return phase == Phase.WATCHING || phase == Phase.ENDING ? tubeBlack : 0f;
    }

    /**
     * How deep {@code x,z} is into one of the tubes, 0 at its mouth on the room to 1 at its far wall;
     * 0 outside both. The tubes lie along x, and the one the point is in is entered from the end that
     * faces the TV's room.
     */
    static float tubeDepth(List<BoundingBox> tubes, @Nullable BoundingBox screen, double x, double z) {
        if (screen == null) return 0f;
        double roomX = (screen.minX() + screen.maxX() + 1) / 2.0;
        for (BoundingBox tube : tubes) {
            if (x < tube.minX() || x >= tube.maxX() + 1 || z < tube.minZ() || z >= tube.maxZ() + 1) continue;
            double length = tube.getXSpan();
            if (length <= 1) return 1f;
            double mouth = roomX < tube.minX() ? tube.minX() : tube.maxX() + 1;
            double far = roomX < tube.minX() ? tube.maxX() + 1 : tube.minX();
            double depth = Math.abs(x - mouth) / Math.abs(far - mouth);
            return (float) Mth.clamp(depth, 0.0, 1.0);
        }
        return 0f;
    }

    // ---- server answers -----------------------------------------------------------------

    public static void onState(TributeRoomVisitStatePacket packet) {
        if (!packet.active()) {
            if (phase == Phase.AWAITING_ROOM) fallBack("the server declined the visit");
            else if (phase != Phase.ENDING) reset();
            return;
        }
        screenBox = BoundingBox.fromCorners(packet.screenMin(), packet.screenMax());
        screenFacing = packet.facing();
        tubes = packet.tubes();
        if (phase == Phase.AWAITING_ROOM) LOGGER.info("[DungeonTrain] tribute room: TV at {}, tubes {}", screenBox, tubes);
    }

    /** The server saved the room. Singleplayer owns the run's end from here; a server has placed us already. */
    public static void onSaved(boolean newWorld) {
        Screen host = pendingHost;
        boolean keepMode = pendingKeepMode;
        Leaving leaving = pendingLeave;
        Minecraft mc = Minecraft.getInstance();
        reset();
        if (leaving != Leaving.NONE) {
            DeathScreenLayoutHandler.leaveFinishedRun(leaving == Leaving.QUIT);
        } else if (newWorld) {
            DeathScreenLayoutHandler.launchWorld(host != null ? host : mc.screen, false, !keepMode);
        } else {
            mc.setScreen(null);
        }
    }

    // ---- per frame / per tick --------------------------------------------------------

    /** Before the level pass: the TV picture, then the phase that depends on time. */
    public static void onFrameStart(float partialTick) {
        if (phase == Phase.IDLE) return;
        long now = System.currentTimeMillis();
        if (texture != null && phase != Phase.ENDING) {
            int[] m = zoomed() ? new int[] {TvPointer.OFF_SCREEN, TvPointer.OFF_SCREEN} : tvMouse();
            texture.renderFrame(m[0], m[1], partialTick);
        }
        Minecraft mc = Minecraft.getInstance();
        switch (phase) {
            case AWAITING_ROOM -> {
                if (!(mc.screen instanceof TributeArrivalScreen)) mc.setScreen(new TributeArrivalScreen());
                if (roomReady(mc)) {
                    // A few frames behind the menu once the room is in, so what it dissolves into is the
                    // room lit and settled rather than its chunks arriving.
                    if (readyAtMs < 0) readyAtMs = now;
                    if (now - readyAtMs >= SETTLE_MS) {
                        setPhase(Phase.FADING_IN);
                        TributeArrivalFov.begin(now, arrivalFov(mc));
                        handBackControl(mc);
                    }
                } else if (now - phaseAtMs >= ROOM_TIMEOUT_MS) {
                    fallBack("no room after " + ROOM_TIMEOUT_MS + " ms");
                }
            }
            case FADING_IN -> {
                // The player has their hands back already ({@link #handBackControl}); the menu's
                // picture dissolves over the HUD (TributeTvClientEvents#onGuiRendered).
                if (now - phaseAtMs >= TributeArrivalScreen.FADE_IN_MS) {
                    setPhase(Phase.WATCHING);
                    PerksHud.onMenuClosed();
                }
            }
            case WATCHING -> walkTubes(mc);
            case ENDING -> {
                if (pendingLeave != Leaving.NONE && now - phaseAtMs >= ROOM_TIMEOUT_MS) {
                    LOGGER.warn("[DungeonTrain] tribute room: no save answer after {} ms — leaving anyway", ROOM_TIMEOUT_MS);
                    onSaved(false);
                }
            }
            default -> {}
        }
    }

    /**
     * The dissolve has started: the room is showing, so the player can move and look from now, not
     * once the menu's picture is gone. Keys already held count straight away, on macOS too, where
     * vanilla's mouse grab skips that re-read.
     */
    private static void handBackControl(Minecraft mc) {
        if (mc.screen instanceof TributeArrivalScreen) mc.setScreen(null);
        net.minecraft.client.KeyMapping.setAll();
    }

    /**
     * The tubes are the way out: the view darkens with every block walked into one, and at its far
     * end, fully black, the visit ends and the next world loads under the black.
     */
    private static void walkTubes(Minecraft mc) {
        if (mc.player == null || zoomed()) return;
        tubeBlack = Mth.clamp(tubeDepth(tubes, screenBox, mc.player.getX(), mc.player.getZ()) / TUBE_END_FRACTION, 0f, 1f);
        if (tubeBlack >= 1f) {
            LOGGER.info("[DungeonTrain] tribute room: walked out through a tube — boarding anew");
            TributeArrivalScreen black = new TributeArrivalScreen();
            mc.setScreen(black);
            endRun(black, false);
        }
    }

    public static void onClientTick() {
        if (texture != null && phase != Phase.IDLE && phase != Phase.ENDING && !zoomed()) texture.tick();
    }

    /**
     * The field of view the TV's picture fills from where the visitor's eyes are: the zoom out starts
     * there, so the dissolve from the window to the TV is the same picture at the same size.
     */
    private static double arrivalFov(Minecraft mc) {
        if (screenBox == null || mc.player == null) return TributeArrivalFov.FALLBACK_START_FOV;
        net.minecraft.core.Direction f = screenFacing;
        boolean alongZ = f.getAxis() == net.minecraft.core.Direction.Axis.Z;
        int across = alongZ ? screenBox.getXSpan() : screenBox.getZSpan();
        int deep = alongZ ? screenBox.getZSpan() : screenBox.getXSpan();
        // The picture's centre: the middle of the box, out to its face on the facing side.
        double cx = (screenBox.minX() + screenBox.maxX() + 1) / 2.0 + f.getStepX() * deep / 2.0;
        double cy = (screenBox.minY() + screenBox.maxY() + 1) / 2.0;
        double cz = (screenBox.minZ() + screenBox.maxZ() + 1) / 2.0 + f.getStepZ() * deep / 2.0;
        double distance = mc.player.getEyePosition().distanceTo(new net.minecraft.world.phys.Vec3(cx, cy, cz));
        return TributeArrivalFov.fillFov(across, screenBox.getYSpan(), pictureAspect(), distance);
    }

    /** The room is up when we are alive in a level that has the TV's blocks — its chunk has arrived. */
    private static boolean roomReady(Minecraft mc) {
        if (screenBox == null || mc.level == null || mc.player == null || !mc.player.isAlive()) return false;
        boolean anyTv = false, anyEntity = false;
        for (BlockPos pos : BlockPos.betweenClosed(screenBox.minX(), screenBox.minY(), screenBox.minZ(),
                screenBox.maxX(), screenBox.maxY(), screenBox.maxZ())) {
            if (mc.level.getBlockEntity(pos) instanceof TVBlockEntity) anyEntity = true;
            if (games.brennan.dungeontrain.compat.vista.TvBlocks.isTelevision(mc.level.getBlockState(pos))) anyTv = true;
        }
        if (mc.level.getGameTime() % 20 == 0 && !anyEntity) {
            BlockPos c = new BlockPos(screenBox.minX(), screenBox.minY(), screenBox.minZ());
            int solid = 0;
            for (BlockPos pos : BlockPos.betweenClosed(c.offset(-8, -3, -3), c.offset(8, 6, 14))) {
                if (!mc.level.getBlockState(pos).isAir()) solid++;
            }
            net.minecraft.world.level.chunk.LevelChunk chunk = mc.level.getChunkSource().getChunk(c.getX() >> 4, c.getZ() >> 4, false);
            LOGGER.info("[DungeonTrain] tribute room: waiting — player alive at {}, chunk={} empty={}, solid near TV={}, at TV={}, under feet={}",
                mc.player.blockPosition(), chunk == null ? "none" : chunk.getPos(), chunk != null && chunk.isEmpty(), solid,
                mc.level.getBlockState(c), mc.level.getBlockState(mc.player.blockPosition().below()));
        }
        return anyEntity;
    }

    // ---- the way out ------------------------------------------------------------------------

    /**
     * Right-clicked the TV. The first click switches it from the perks to the recap; every click
     * after that is the next page, sneaking the previous one. A page that needs the player's hands
     * comes up on the window instead ({@link #leaveZoom} takes it back down).
     */
    public static boolean onTvClicked(BlockPos pos, boolean sneaking) {
        if (phase != Phase.WATCHING || !showsOn(pos) || texture == null) return false;
        Minecraft mc = Minecraft.getInstance();
        if (!recapShowing()) {
            // The perks menu (first, or gone back to from the recap's last page): the click lands on its own buttons, and only Next Screen moves on
            // (PerksScreen.onClose → showRecap). A click off every button does nothing, as on the window.
            Screen page = texture.screen();
            if (page == null) return false;
            if (sneaking) {
                mc.setScreen(page);
                return true;
            }
            int[] m = tvMouse();
            if (m[0] == TvPointer.OFF_SCREEN) return true;
            page.mouseClicked(m[0], m[1], 0);
            page.mouseReleased(m[0], m[1], 0);
            return true;
        }
        if (sneaking) {
            // Hands on the page: typing, scrolling a strip — the window has the keyboard.
            mc.setScreen(recapScreen);
            return true;
        }
        // The click lands where the crosshair is on the picture: the page's own buttons take it.
        int[] m = tvMouse();
        if (m[0] == TvPointer.OFF_SCREEN) return true;
        // Held locally: the click can end the visit (a page's button that leaves the world, e.g.
        // Board anew), which clears recapScreen before the release would reach it.
        NarrativeDeathScreen page = recapScreen;
        page.mouseClicked(m[0], m[1], 0);
        if (recapScreen != page) return true;
        page.mouseReleased(m[0], m[1], 0);
        if (recapScreen == page && page.textFieldFocused()) liftForTyping(mc, page);
        return true;
    }

    /**
     * The button under the TV ({@code TributeTvButton}) pressed: Next Screen on whatever the TV shows —
     * the perks menu's Next Screen ({@code PerksScreen#onClose}), or the recap's next page.
     * {@code tv} is the TV block above the button; {@code false} when it is not this visit's TV.
     */
    public static boolean pressNext(BlockPos tv) {
        if (phase != Phase.WATCHING || !showsOn(tv) || texture == null) return false;
        if (recapShowing()) {
            // The last page loops back to the first screen: the perks menu, whose Next Screen
            // starts the recap over.
            if (recapScreen.tvOnLastPage()) showPerks();
            else recapScreen.tvAdvance();
            return true;
        }
        Screen page = texture.screen();
        if (page == null) return false;
        page.onClose();
        return true;
    }

    /**
     * Next Screen pressed on the perks menu while it shows on the TV: the TV switches to the recap.
     * {@code false} when {@code perks} is not the menu this visit is showing, so the menu closes as usual.
     */
    public static boolean showRecap(Screen perks) {
        if (phase != Phase.WATCHING || texture == null || texture.screen() != perks) return false;
        Minecraft mc = Minecraft.getInstance();
        if (recapScreen != null) {
            // Back on the perks menu from the recap's last page: the same recap, from its first page.
            if (mc.screen == perks) mc.setScreen(null);
            texture.attach(recapScreen);
            recapScreen.tvRestart();
            return true;
        }
        if (recap == null) return false;
        Screen next = recap.get();
        if (!(next instanceof NarrativeDeathScreen nds)) {
            mc.setScreen(next);
            return true;
        }
        recapScreen = nds;
        if (mc.screen == perks) mc.setScreen(null);
        texture.attach(nds);
        return true;
    }

    /**
     * The click landed on a text field: the page comes up on the window and the field takes the
     * keyboard, as pressing T hands it to chat; Enter or Esc gives it back ({@link #leaveZoom}).
     */
    static void liftForTyping(Minecraft mc, NarrativeDeathScreen page) {
        mc.setScreen(page);
        page.focusTextField();
    }

    /**
     * The perks menu back on the TV, from the recap's last page (the TV button, or the ✦ chip). Its
     * Next Screen starts the recap over ({@link #showRecap}). A recap lifted onto the window goes back down.
     */
    public static void showPerks() {
        if (phase != Phase.WATCHING || texture == null || perksScreen == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (recapScreen != null && mc.screen == recapScreen) {
            recapScreen.blurTextField();
            mc.setScreen(null);
        }
        texture.attach(perksScreen);
    }

    /** Whether {@code screen} is this visit's own recap with the perks menu to go back to: the ✦ chip. */
    public static boolean canShowPerks(Screen screen) {
        return games.brennan.dungeontrain.perks.PerksGate.isOnClient() && perksScreen != null
            && recapScreen != null && recapScreen == screen && phase == Phase.WATCHING;
    }

    /** The recap is what the TV shows now (not the perks menu, before it or gone back to). */
    private static boolean recapShowing() {
        return recapScreen != null && texture != null && texture.screen() == recapScreen;
    }

    /** The wheel while looking at the TV: the page scrolls, not the hotbar. {@code false} when not on the TV. */
    public static boolean onTvScrolled(double dx, double dy) {
        if (phase != Phase.WATCHING || !recapShowing() || zoomed()) return false;
        int[] m = tvMouse();
        if (m[0] == TvPointer.OFF_SCREEN) return false;
        recapScreen.mouseScrolled(m[0], m[1], dx, dy);
        return true;
    }

    /** GUI coordinates of the crosshair on the picture, or off-screen when it is not on the TV's face. */
    private static int[] tvMouse() {
        if (texture == null || screenBox == null) return new int[] {TvPointer.OFF_SCREEN, TvPointer.OFF_SCREEN};
        return new TvScreenBinding(texture, screenBox, screenFacing).mouse();
    }

    /** Whether {@code screen} is the recap this visit's TV is showing. */
    public static boolean showsPage(Screen screen) {
        return phase == Phase.WATCHING && recapScreen != null && recapScreen == screen;
    }

    /** The fullscreen chip on the TV: the page comes up on the window. {@code false} when it is not ours. */
    public static boolean lift(Screen screen) {
        if (!showsPage(screen)) return false;
        Minecraft.getInstance().setScreen(recapScreen);
        return true;
    }

    /** Esc on a zoomed page: back to the room, the page staying on the TV. {@code false} when not zoomed. */
    public static boolean leaveZoom(Screen screen) {
        if (phase != Phase.WATCHING || texture == null || screen == null || screen != texture.screen()) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != screen) return false;
        if (screen == recapScreen) recapScreen.blurTextField();
        mc.setScreen(null);
        texture.attach(screen);
        return true;
    }

    /** Whether the texture's screen is also the window's right now — then the window ticks it, not us. */
    private static boolean zoomed() {
        return texture != null && texture.screen() != null && Minecraft.getInstance().screen == texture.screen();
    }

    /**
     * Board anew from the recap while visiting: tell the server, which saves the room and answers with
     * {@link #onSaved}. {@code host} is the screen the new-world flow may return to; {@code keepMode}
     * is Shift held, as the recap reads it.
     */
    public static boolean endRun(Screen host, boolean keepMode) {
        if (phase != Phase.WATCHING) return false;
        pendingHost = host;
        pendingKeepMode = keepMode;
        tubeBlack = 1f;
        setPhase(Phase.ENDING);
        DungeonTrainNet.sendToServer(new TributeRoomVisitEndPacket());
        return true;
    }

    /**
     * The pause menu's way out of a visit (singleplayer): the run is already over, so there is nothing
     * to abandon. The server saves the room and sweeps it, leaving them off the train, and its answer
     * ({@link #onSaved}) closes the world the way the death screen would. Before the room stands
     * there is nothing to save, and they leave straight away.
     *
     * @param quitGame out of the game rather than to the title screen
     */
    public static void leave(boolean quitGame) {
        Leaving leaving = quitGame ? Leaving.QUIT : Leaving.TITLE;
        Minecraft mc = Minecraft.getInstance();
        if (phase != Phase.WATCHING) {
            if (phase == Phase.ENDING && pendingLeave != Leaving.NONE) return;
            reset();
            DeathScreenLayoutHandler.leaveFinishedRun(quitGame);
            return;
        }
        LOGGER.info("[DungeonTrain] tribute room: leaving from the pause menu ({}) — saving the room", leaving);
        TributeArrivalScreen black = new TributeArrivalScreen();
        mc.setScreen(black);
        if (endRun(black, false)) pendingLeave = leaving;
    }

    /**
     * The pause menu's Reboard: the same as walking out through a tube. The window goes black (which
     * also lets the integrated server run again), the server saves the room, and the next world loads
     * under the black ({@link #onSaved}). Before the room stands there is nothing to save, and the next
     * world loads straight away.
     */
    public static void reboard() {
        Minecraft mc = Minecraft.getInstance();
        TributeArrivalScreen black = new TributeArrivalScreen();
        mc.setScreen(black);
        if (endRun(black, false)) {
            LOGGER.info("[DungeonTrain] tribute room: reboarding from the pause menu — saving the room");
            return;
        }
        if (phase == Phase.ENDING) return;
        reset();
        DeathScreenLayoutHandler.launchWorld(black, false, true);
    }

    /** Something went wrong before the room stood: the old full-screen recap, so nobody is left on black. */
    private static void fallBack(String why) {
        LOGGER.warn("[DungeonTrain] tribute room: falling back to the recap — {}", why);
        Supplier<Screen> next = recap;
        reset();
        PerksHud.onMenuClosed();
        Minecraft mc = Minecraft.getInstance();
        if (next != null) mc.setScreen(next.get());
        else if (mc.screen instanceof TributeArrivalScreen) mc.setScreen(null);
    }

    /** Forget everything: logging out, a fallback, the run ending. Render thread. */
    public static void reset() {
        if (texture != null) {
            texture.close();
            texture = null;
        }
        phase = Phase.IDLE;
        recap = null;
        recapScreen = null;
        perksScreen = null;
        screenBox = null;
        tubes = List.of();
        tubeBlack = 0f;
        pendingHost = null;
        pendingKeepMode = false;
        pendingLeave = Leaving.NONE;
        readyAtMs = -1L;
        TributeArrivalFov.reset();
    }

    /** For the dev preview harness: where the visit stands. */
    static Phase phase() {
        return phase;
    }

    /** For the dev preview harness: the recap once it is on the TV, or null. */
    @Nullable
    static NarrativeDeathScreen recapOnTv() {
        return recapScreen;
    }

    /** For the dev preview harness: a block of the TV, or null. */
    @Nullable
    static BlockPos screenCorner() {
        return screenBox == null ? null : new BlockPos(screenBox.minX(), screenBox.minY(), screenBox.minZ());
    }

    private static void setPhase(Phase next) {
        phase = next;
        phaseAtMs = System.currentTimeMillis();
    }
}

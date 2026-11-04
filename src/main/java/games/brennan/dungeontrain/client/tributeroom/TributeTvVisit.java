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

    private static Phase phase = Phase.IDLE;
    private static long phaseAtMs;
    @Nullable private static Supplier<Screen> recap;
    @Nullable private static ScreenToTexture texture;
    @Nullable private static NarrativeDeathScreen recapScreen;
    @Nullable private static BoundingBox screenBox;
    private static List<BoundingBox> tubes = List.of();
    /** How black the tube walk has the view, 0..1; held at 1 once a far end has ended the visit. */
    private static float tubeBlack;
    private static net.minecraft.core.Direction screenFacing = net.minecraft.core.Direction.NORTH;
    @Nullable private static Screen pendingHost;
    private static boolean pendingKeepMode;
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
        setPhase(Phase.AWAITING_ROOM);
        mc.setScreen(new TributeArrivalScreen());
        DungeonTrainNet.sendToServer(new TributeRoomVisitPacket());
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
        Minecraft mc = Minecraft.getInstance();
        reset();
        if (newWorld) {
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
                        TributeArrivalFov.begin(now);
                    }
                } else if (now - phaseAtMs >= ROOM_TIMEOUT_MS) {
                    fallBack("no room after " + ROOM_TIMEOUT_MS + " ms");
                }
            }
            case FADING_IN -> {
                if (!(mc.screen instanceof TributeArrivalScreen)) mc.setScreen(new TributeArrivalScreen());
                if (now - phaseAtMs >= TributeArrivalScreen.FADE_IN_MS) {
                    setPhase(Phase.WATCHING);
                    if (mc.screen instanceof TributeArrivalScreen) mc.setScreen(null);
                    PerksHud.onMenuClosed();
                }
            }
            case WATCHING -> walkTubes(mc);
            default -> {}
        }
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
        if (recapScreen == null) {
            // Still the perks menu: the click lands on its own buttons, and only Next Screen moves on
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
        recapScreen.mouseClicked(m[0], m[1], 0);
        recapScreen.mouseReleased(m[0], m[1], 0);
        if (recapScreen.textFieldFocused()) liftForTyping(mc, recapScreen);
        return true;
    }

    /**
     * Next Screen pressed on the perks menu while it shows on the TV: the TV switches to the recap.
     * {@code false} when {@code perks} is not the menu this visit is showing, so the menu closes as usual.
     */
    public static boolean showRecap(Screen perks) {
        if (phase != Phase.WATCHING || texture == null || texture.screen() != perks || recapScreen != null) return false;
        if (recap == null) return false;
        Minecraft mc = Minecraft.getInstance();
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

    /** The wheel while looking at the TV: the page scrolls, not the hotbar. {@code false} when not on the TV. */
    public static boolean onTvScrolled(double dx, double dy) {
        if (phase != Phase.WATCHING || recapScreen == null || zoomed()) return false;
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
        screenBox = null;
        tubes = List.of();
        tubeBlack = 0f;
        pendingHost = null;
        pendingKeepMode = false;
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

package games.brennan.dungeontrain.client.homeroom;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.DeathScreenLayoutHandler;
import games.brennan.dungeontrain.client.perks.PerksHud;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.HomeRoomVisitEndPacket;
import games.brennan.dungeontrain.net.HomeRoomVisitPacket;
import games.brennan.dungeontrain.net.HomeRoomVisitStatePacket;
import net.mehvahdjukaar.vista.common.tv.TVBlockEntity;
import net.minecraft.client.Minecraft;
import games.brennan.dungeontrain.client.NarrativeDeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.function.Supplier;

/**
 * The client side of a home room visit, as a state machine:
 *
 * <pre>
 * IDLE → FADING_OUT (black over the perks menu) → AWAITING_ROOM (black; the server stands the room)
 *      → FADING_IN (black falls away, FOV eases out of the TV) → WATCHING (first person, TV on)
 *      → ENDING (Board anew sent; waiting for the save) → IDLE
 * </pre>
 *
 * <p>The TV shows whatever {@link ScreenToTexture} is drawing: the perks menu exactly as it was left,
 * then the recap, page by page, each right-click on the TV the next one ({@link #onTvClicked}). A page
 * that needs input comes up on the window and goes back down with Esc; Board anew comes back through
 * {@link #endRun}. A server that never answers hands the player the recap instead
 * ({@link #ROOM_TIMEOUT_MS}), so nobody waits on black.</p>
 */
public final class HomeTvVisit {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final long ROOM_TIMEOUT_MS = 20_000L;
    /** Black held after the room is in, before the fade-in. */
    static final long SETTLE_MS = 600L;
    /** What the picture is taken to be when no texture is up: the TV's own 3:2. */
    static final float TV_ASPECT = 1.5f;

    enum Phase { IDLE, FADING_OUT, AWAITING_ROOM, FADING_IN, WATCHING, ENDING }

    private static Phase phase = Phase.IDLE;
    private static long phaseAtMs;
    @Nullable private static Supplier<Screen> recap;
    @Nullable private static ScreenToTexture texture;
    @Nullable private static NarrativeDeathScreen recapScreen;
    @Nullable private static BoundingBox screenBox;
    private static net.minecraft.core.Direction screenFacing = net.minecraft.core.Direction.NORTH;
    @Nullable private static Screen pendingHost;
    private static boolean pendingKeepMode;
    private static long readyAtMs = -1L;

    private HomeTvVisit() {}

    // ---- entry ---------------------------------------------------------------------

    /** Next pressed on the perks menu while dead: start the fade and ask the server for the room. */
    public static void begin(Screen perks, Supplier<Screen> next) {
        Minecraft mc = Minecraft.getInstance();
        reset();
        recap = next;
        texture = new ScreenToTexture(games.brennan.dungeontrain.client.perks.PerksScreen.SKY_BG);
        texture.attach(perks);
        setPhase(Phase.FADING_OUT);
        mc.setScreen(new HomeArrivalScreen(perks));
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

    /** How black the arrival screen draws now. */
    static float blackAlpha(long nowMs) {
        return switch (phase) {
            case FADING_OUT -> Mth.clamp((nowMs - phaseAtMs) / (float) HomeArrivalScreen.FADE_OUT_MS, 0f, 1f);
            case AWAITING_ROOM -> 1f;
            case FADING_IN -> 1f - Mth.clamp((nowMs - phaseAtMs) / (float) HomeArrivalScreen.FADE_IN_MS, 0f, 1f);
            default -> 0f;
        };
    }

    // ---- server answers -----------------------------------------------------------------

    public static void onState(HomeRoomVisitStatePacket packet) {
        if (!packet.active()) {
            if (phase == Phase.AWAITING_ROOM || phase == Phase.FADING_OUT) fallBack("the server declined the visit");
            else if (phase != Phase.ENDING) reset();
            return;
        }
        screenBox = BoundingBox.fromCorners(packet.screenMin(), packet.screenMax());
        screenFacing = packet.facing();
        if (phase == Phase.AWAITING_ROOM || phase == Phase.FADING_OUT) LOGGER.info("[DungeonTrain] home room: TV at {}", screenBox);
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
            case FADING_OUT -> {
                if (now - phaseAtMs >= HomeArrivalScreen.FADE_OUT_MS) {
                    setPhase(Phase.AWAITING_ROOM);
                    mc.setScreen(new HomeArrivalScreen(null));
                    DungeonTrainNet.sendToServer(new HomeRoomVisitPacket());
                }
            }
            case AWAITING_ROOM -> {
                if (roomReady(mc)) {
                    // A few frames on black once the room is in, so the first thing seen is the room
                    // lit and settled rather than its chunks arriving.
                    if (readyAtMs < 0) readyAtMs = now;
                    if (now - readyAtMs >= SETTLE_MS) {
                        setPhase(Phase.FADING_IN);
                        HomeArrivalFov.begin(now);
                    }
                } else if (now - phaseAtMs >= ROOM_TIMEOUT_MS) {
                    fallBack("no room after " + ROOM_TIMEOUT_MS + " ms");
                }
            }
            case FADING_IN -> {
                if (!(mc.screen instanceof HomeArrivalScreen)) mc.setScreen(new HomeArrivalScreen(null));
                if (now - phaseAtMs >= HomeArrivalScreen.FADE_IN_MS) {
                    setPhase(Phase.WATCHING);
                    if (mc.screen instanceof HomeArrivalScreen) mc.setScreen(null);
                    PerksHud.onMenuClosed();
                }
            }
            default -> {}
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
            LOGGER.info("[DungeonTrain] home room: waiting — player alive at {}, chunk={} empty={}, solid near TV={}, at TV={}, under feet={}",
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
            if (recap == null) return false;
            Screen next = recap.get();
            if (!(next instanceof NarrativeDeathScreen nds)) {
                mc.setScreen(next);
                return true;
            }
            recapScreen = nds;
            texture.attach(nds);
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
        return true;
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

    /** Esc on a zoomed page: back to the room, the page staying on the TV. {@code false} when not zoomed. */
    public static boolean leaveZoom(Screen screen) {
        if (phase != Phase.WATCHING || texture == null || screen != recapScreen) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != screen) return false;
        mc.setScreen(null);
        texture.attach(recapScreen);
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
        setPhase(Phase.ENDING);
        DungeonTrainNet.sendToServer(new HomeRoomVisitEndPacket());
        return true;
    }

    /** Something went wrong before the room stood: the old full-screen recap, so nobody is left on black. */
    private static void fallBack(String why) {
        LOGGER.warn("[DungeonTrain] home room: falling back to the recap — {}", why);
        Supplier<Screen> next = recap;
        reset();
        PerksHud.onMenuClosed();
        Minecraft mc = Minecraft.getInstance();
        if (next != null) mc.setScreen(next.get());
        else if (mc.screen instanceof HomeArrivalScreen) mc.setScreen(null);
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
        pendingHost = null;
        pendingKeepMode = false;
        readyAtMs = -1L;
        HomeArrivalFov.reset();
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

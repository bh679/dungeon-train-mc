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
 * for now. Right-clicking the TV opens the recap as a screen; its Board anew comes back through
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
    @Nullable private static BoundingBox screenBox;
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
        if (texture != null && phase != Phase.ENDING) texture.renderFrame(-10_000, -10_000, partialTick);
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
        if (texture != null && phase != Phase.IDLE && phase != Phase.ENDING) texture.tick();
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

    /** Right-clicked the TV: the recap, as a screen, for now. */
    public static boolean onTvClicked(BlockPos pos) {
        if (phase != Phase.WATCHING || !showsOn(pos) || recap == null) return false;
        Minecraft.getInstance().setScreen(recap.get());
        return true;
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

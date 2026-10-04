package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.event.StartingBookEvents;
import games.brennan.playermob.entity.PlayerMobEntity;
import io.github.mortuusars.exposure.Exposure;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import io.github.mortuusars.exposure.world.entity.CameraHolder;
import io.github.mortuusars.exposure.world.item.camera.CameraItem;
import io.github.mortuusars.exposure_polaroid.world.item.InstantCameraItem;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

/**
 * A PlayerMob photographing the player who gifted it a camera. Runs once a
 * {@link PlayerMobPhotoSubject} is set on the mob (see {@link PlayerMobCameraBridge}) and the mob is
 * free; modelled on PlayerMob's own short tool goals (swap in, wind up, act, hand back).
 *
 * <ol>
 *   <li><b>SWAP</b> — bring the camera from the backpack to the main hand, stop walking.</li>
 *   <li><b>AIM</b> — face the subject and hold still long enough for the pose to reach their client,
 *       which is what renders the shot (the mob is an Exposure {@link CameraHolder} whose executing
 *       player is the subject — {@code mixin.PlayerMobCameraHolderMixin}).</li>
 *   <li><b>SHOOT</b> — {@link CameraItem#release}. Exposure plays the shutter, flashes if dark,
 *       sends the capture to the subject's client, and lands the frame on the camera stack.</li>
 *   <li><b>PRINT</b> — tick the camera until the shutter closes (nothing ticks a mob's held item),
 *       take the frame off Exposure's component into DT's pending data (as
 *       {@link DisposableCameraEvents} does for players, so Polaroid cannot print it its own way),
 *       and wait out the print.</li>
 *   <li><b>KEEP</b> — the photograph goes into the mob's backpack as its keepsake; the spent camera is
 *       thrown down and burns, as a player's does; the mob takes its weapon back up.</li>
 * </ol>
 *
 * <p>Aborts — the mob entering combat, the subject leaving, the camera gone or unable to shoot —
 * hand the camera back to the pack and clear the request. The print is deliberately never spawned
 * as a dropped item: a disposable-camera photo ignites on any drop.</p>
 */
public final class PlayerMobPhotoGoal extends Goal {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final int SWAP_TICKS = 10;
    static final int AIM_TICKS = 15;
    /** Shutter close + print, after the shot; beyond this the camera is treated as jammed. */
    static final int PRINT_TIMEOUT_TICKS = 200;
    /** The subject must be this close for the mob to bother. */
    private static final double SUBJECT_RANGE_SQR = 32.0 * 32.0;

    private enum Phase { IDLE, SWAP, AIM, SHOOT, PRINT, KEEP }

    private final PlayerMobEntity mob;
    private final PlayerMobPhotoSubject subject;
    private final CameraHolder holder;

    private Phase phase = Phase.IDLE;
    private int ticks;
    private long shotTick = -1;
    private ServerPlayer target;

    private PlayerMobPhotoGoal(PlayerMobEntity mob) {
        this.mob = mob;
        this.subject = (PlayerMobPhotoSubject) mob;
        this.holder = (CameraHolder) mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** The goal for {@code mob}, or empty when the mixin is absent (nothing to drive it). */
    public static Optional<PlayerMobPhotoGoal> create(PlayerMobEntity mob) {
        if (mob instanceof PlayerMobPhotoSubject && mob instanceof CameraHolder) {
            return Optional.of(new PlayerMobPhotoGoal(mob));
        }
        return Optional.empty();
    }

    @Override
    public boolean canUse() {
        UUID wanted = subject.dungeontrain$photoSubject();
        if (wanted == null || mob.isInCombat()) {
            return false;
        }
        ServerPlayer player = resolve(wanted);
        if (player == null || findCamera() < -1) {
            // Subject gone or no camera to shoot with: the request can never be met.
            subject.dungeontrain$setPhotoSubject(null);
            return false;
        }
        target = player;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return phase != Phase.IDLE && mob.isAlive();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        phase = Phase.SWAP;
        ticks = 0;
        shotTick = -1;
        mob.getNavigation().stop();
    }

    @Override
    public void stop() {
        ItemStack held = mob.getMainHandItem();
        if (isCamera(held)) {
            mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            if (DisposableCamera.isShot(held) || DisposableCamera.hasPendingFrame(held)) {
                // Shot but never printed (timed out / interrupted): a spent camera burns like a player's.
                StartingBookEvents.dropAndBurnFrom(mob, held);
            } else {
                ItemStack leftover = mob.getInventory().addItem(held); // unshot: back to the pack
                if (!leftover.isEmpty()) mob.setItemSlot(EquipmentSlot.MAINHAND, leftover);
            }
            if (mob.getMainHandItem().isEmpty()) mob.equipBestMeleeInHand();
        }
        subject.dungeontrain$setPhotoSubject(null);
        phase = Phase.IDLE;
        target = null;
    }

    @Override
    public void tick() {
        if (phase == Phase.IDLE) {
            return; // the tick after an abort, before canContinueToUse stops the goal
        }
        if (target == null || !target.isAlive() || target.level() != mob.level()
                || target.distanceToSqr(mob) > SUBJECT_RANGE_SQR) {
            abort("subject left");
            return;
        }
        if (phase.ordinal() < Phase.PRINT.ordinal() && mob.isInCombat()) {
            abort("combat");
            return;
        }
        mob.getNavigation().stop();
        mob.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        ticks++;
        switch (phase) {
            case SWAP -> tickSwap();
            case AIM -> { if (ticks >= AIM_TICKS) { phase = Phase.SHOOT; } }
            case SHOOT -> tickShoot();
            case PRINT -> tickPrint();
            case KEEP -> keep();
            default -> abort("idle");
        }
    }

    private void tickSwap() {
        if (!isCamera(mob.getMainHandItem())) {
            int slot = findCamera();
            if (slot < 0) {
                abort("camera gone");
                return;
            }
            if (ticks < SWAP_TICKS) {
                return;
            }
            if (!mob.equipWeapon(mob.getInventory().getItem(slot).getItem())) {
                abort("could not hold camera");
                return;
            }
        }
        if (ticks >= SWAP_TICKS) {
            phase = Phase.AIM;
            ticks = 0;
        }
    }

    private void tickShoot() {
        ItemStack camera = mob.getMainHandItem();
        if (!(camera.getItem() instanceof CameraItem item) || !item.canTakePhoto(holder, camera)) {
            abort("camera cannot shoot");
            return;
        }
        item.release(holder, camera);
        shotTick = mob.level().getGameTime();
        // The frame lands on the camera stack synchronously inside release(); take it off before the
        // camera is ever ticked, or Polaroid prints it its own way — as a dropped photograph, which
        // a disposable print burns on.
        stashFrame(camera);
        phase = Phase.PRINT;
        ticks = 0;
    }

    /** Move a frame Polaroid left on the stack into DT's pending data; returns true if one was there. */
    private boolean stashFrame(ItemStack camera) {
        Frame onStack = camera.get(Exposure.DataComponents.PHOTOGRAPH_FRAME);
        if (onStack == null) {
            return false;
        }
        if (DisposableCamera.setPendingFrame(camera, onStack, mob.registryAccess())) {
            camera.remove(Exposure.DataComponents.PHOTOGRAPH_FRAME);
            DisposableCamera.markShot(camera);
        }
        return true;
    }

    private void tickPrint() {
        ItemStack camera = mob.getMainHandItem();
        if (!(camera.getItem() instanceof CameraItem item)) {
            abort("camera gone mid-print");
            return;
        }
        stashFrame(camera);      // before the tick: Polaroid's tick prints any frame it finds
        item.tick(holder, camera);
        stashFrame(camera);
        long since = mob.level().getGameTime() - shotTick;
        boolean printed = DisposableCamera.hasPendingFrame(camera) && !item.getShutter().isOpen(camera)
                && since >= DisposableCameraEvents.VIEWFINDER_HOLD_TICKS + DisposableCameraEvents.PRINT_TICKS;
        if (printed) {
            phase = Phase.KEEP;
        } else if (since > PRINT_TIMEOUT_TICKS) {
            abort("print timed out");
        }
    }

    private void keep() {
        ItemStack camera = mob.getMainHandItem();
        Optional<Frame> frame = DisposableCamera.pendingFrame(camera, mob.registryAccess());
        DisposableCamera.clearPendingFrame(camera);
        mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        if (frame.isPresent()) {
            ItemStack photograph = new ItemStack(Exposure.Items.PHOTOGRAPH.get());
            photograph.set(Exposure.DataComponents.PHOTOGRAPH_FRAME, frame.get());
            photograph.set(Exposure.DataComponents.PHOTOGRAPH_TYPE, frame.get().type());
            ItemStack leftover = mob.getInventory().addItem(photograph);
            if (!leftover.isEmpty()) {
                mob.setItemSlot(EquipmentSlot.MAINHAND, leftover); // full pack: hold it instead
            }
            if (mob.level() instanceof ServerLevel level) {
                level.playSound(null, mob, Exposure.SoundEvents.PHOTOGRAPH_RUSTLE.get(), SoundSource.NEUTRAL,
                    0.6f, level.getRandom().nextFloat() * 0.2f + 1.0f);
            }
        } else {
            LOGGER.warn("[PlayerMobCamera] {}'s stored frame did not decode; the camera burned without a photo",
                mob.getName().getString());
        }
        StartingBookEvents.dropAndBurnFrom(mob, camera);
        if (mob.getMainHandItem().isEmpty()) {
            mob.equipBestMeleeInHand();
        }
        phase = Phase.IDLE;
    }

    private void abort(String why) {
        LOGGER.info("[PlayerMobCamera] {} gave up the photo: {}", mob.getName().getString(), why);
        phase = Phase.IDLE;
    }

    private ServerPlayer resolve(UUID id) {
        if (!(mob.level() instanceof ServerLevel level)) return null;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
        if (player == null || !player.isAlive() || player.isSpectator() || player.level() != level
                || player.distanceToSqr(mob) > SUBJECT_RANGE_SQR) {
            return null;
        }
        return player;
    }

    /** Backpack slot of a camera, {@code -1} if it is already in hand, {@code -2} if there is none. */
    private int findCamera() {
        if (isCamera(mob.getMainHandItem())) return -1;
        for (int i = 0; i < mob.getInventory().getContainerSize(); i++) {
            if (isCamera(mob.getInventory().getItem(i))) return i;
        }
        return -2;
    }

    private static boolean isCamera(ItemStack stack) {
        return stack.getItem() instanceof InstantCameraItem;
    }
}

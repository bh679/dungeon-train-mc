package games.brennan.dungeontrain.compat;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The markers behind the disposable camera — Exposure's own items need a running game to exist. */
final class DisposableCameraTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("a camera marker is read back, and the shot marker is separate from it")
    void cameraMarkers() {
        ItemStack stack = new ItemStack(Items.STICK);
        assertFalse(DisposableCamera.hasMarker(stack, DisposableCamera.NBT_CAMERA));

        DisposableCamera.mark(stack, DisposableCamera.NBT_CAMERA);
        assertTrue(DisposableCamera.hasMarker(stack, DisposableCamera.NBT_CAMERA));
        assertFalse(DisposableCamera.isShot(stack));

        DisposableCamera.markShot(stack);
        assertTrue(DisposableCamera.isShot(stack));
        assertTrue(DisposableCamera.hasMarker(stack, DisposableCamera.NBT_CAMERA));
    }

    @Test
    @DisplayName("print timer stamps read back, and are unset (-1) until written")
    void tickStamps() {
        ItemStack stack = new ItemStack(Items.STICK);
        assertEquals(-1L, DisposableCamera.tick(stack, DisposableCamera.NBT_PRINT_START));
        assertEquals(-1L, DisposableCamera.tick(ItemStack.EMPTY, DisposableCamera.NBT_SHOT_TICK));

        DisposableCamera.setTick(stack, DisposableCamera.NBT_SHOT_TICK, 1234L);
        assertEquals(1234L, DisposableCamera.tick(stack, DisposableCamera.NBT_SHOT_TICK));
        assertEquals(-1L, DisposableCamera.tick(stack, DisposableCamera.NBT_PRINT_START));
        assertFalse(DisposableCamera.hasPendingFrame(stack));
    }

    @Test
    @DisplayName("a camera that has not started printing shows no print progress")
    void noProgressBeforePrint() {
        ItemStack stack = new ItemStack(Items.STICK);
        assertEquals(0f, DisposableCamera.printProgress(stack, 5000.0));
        DisposableCamera.setTick(stack, DisposableCamera.NBT_SHOT_TICK, 4990L);
        assertEquals(0f, DisposableCamera.printProgress(stack, 5000.0));
    }

    @Test
    @DisplayName("print progress runs 0..1 over PRINT_TICKS and clamps either side")
    void printProgressClamps() {
        ItemStack stack = new ItemStack(Items.STICK);
        DisposableCamera.setTick(stack, DisposableCamera.NBT_PRINT_START, 100L);
        int ticks = DisposableCameraEvents.PRINT_TICKS;
        assertEquals(0f, DisposableCamera.printProgress(stack, 90.0));
        assertEquals(0f, DisposableCamera.printProgress(stack, 100.0));
        assertEquals(0.5f, DisposableCamera.printProgress(stack, 100.0 + ticks / 2.0), 1e-6);
        assertEquals(1f, DisposableCamera.printProgress(stack, 100.0 + ticks));
        assertEquals(1f, DisposableCamera.printProgress(stack, 100.0 + ticks * 3));
    }

    @Test
    @DisplayName("a marked stack that is not an instant camera is not a disposable camera")
    void markerAloneIsNotACamera() {
        ItemStack stack = new ItemStack(Items.STICK);
        DisposableCamera.mark(stack, DisposableCamera.NBT_CAMERA);
        assertFalse(DisposableCamera.is(stack));
        assertFalse(DisposableCamera.is(ItemStack.EMPTY));
        assertFalse(DisposableCamera.is(null));
    }

    @Test
    @DisplayName("only a flagged frame burns after viewing")
    void frameFlag() {
        CompoundTag extraData = new CompoundTag();
        assertFalse(DisposableCamera.isFlagged(extraData));
        assertFalse(DisposableCamera.isFlagged(null));

        DisposableCamera.flag(extraData);
        assertTrue(DisposableCamera.isFlagged(extraData));
    }

    @Test
    @DisplayName("ordinary items never burn after viewing")
    void ordinaryItems() {
        assertFalse(DisposableCamera.burnsAfterViewing(new ItemStack(Items.PAPER)));
        assertFalse(DisposableCamera.holdsBurnAfterViewing(new ItemStack(Items.PAPER)));
        assertFalse(DisposableCamera.holdsBurnAfterViewing(ItemStack.EMPTY));
        assertFalse(DisposableCamera.burnsAfterViewing(null));
    }
}

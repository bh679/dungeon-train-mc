package games.brennan.dungeontrain.compat;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

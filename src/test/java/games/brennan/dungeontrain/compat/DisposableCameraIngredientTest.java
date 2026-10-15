package games.brennan.dungeontrain.compat;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The photo album recipe's camera slot — markers only, as Exposure's items need a running game to exist. */
final class DisposableCameraIngredientTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("an unshot disposable camera is accepted")
    void unshotAccepted() {
        ItemStack stack = new ItemStack(Items.STICK);
        DisposableCamera.mark(stack, DisposableCamera.NBT_CAMERA);
        assertTrue(DisposableCameraIngredient.unspent(stack));
    }

    @Test
    @DisplayName("a camera that has taken its photo is refused")
    void shotRefused() {
        ItemStack stack = new ItemStack(Items.STICK);
        DisposableCamera.mark(stack, DisposableCamera.NBT_CAMERA);
        DisposableCamera.markShot(stack);
        assertFalse(DisposableCameraIngredient.unspent(stack));
    }

    @Test
    @DisplayName("a stack without the disposable-camera marker is refused")
    void unmarkedRefused() {
        assertFalse(DisposableCameraIngredient.unspent(new ItemStack(Items.STICK)));
        assertFalse(DisposableCameraIngredient.INSTANCE.test(new ItemStack(Items.STICK)));
    }
}

package games.brennan.dungeontrain.mixin.vanillabackport;

import com.blackgear.vanillabackport.common.level.blockentities.CreakingHeartBlockEntity;
import com.blackgear.vanillabackport.common.level.entities.creaking.Creaking;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Optional;

/** VanillaBackport's private heart helpers that {@code CreakingHeartExtras} reuses. */
@Mixin(value = CreakingHeartBlockEntity.class, remap = false)
public interface CreakingHeartBlockEntityInvoker {

    @Invoker("spawnProtector")
    static Creaking dungeontrain$spawnProtector(ServerLevel level, CreakingHeartBlockEntity heart) {
        throw new AssertionError();
    }

    @Invoker("getCreakingProtector")
    Optional<Creaking> dungeontrain$creakingProtector();
}

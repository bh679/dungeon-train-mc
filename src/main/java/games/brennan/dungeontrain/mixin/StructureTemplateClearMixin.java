package games.brennan.dungeontrain.mixin;

import net.minecraft.world.Clearable;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps a template stamp from crashing chunk generation on a block entity that has no level yet.
 *
 * <p>When a template block carries NBT, vanilla first clears whatever block entity already stands there.
 * During generation that block entity may belong to a structure placed moments earlier into the same
 * unfinished chunk, and a proto-chunk's block entities have no level: a jukebox's {@code clearContent}
 * then dereferences {@code level} and throws, the chunk step fails, and the world never finishes loading.
 * The Lost City's dense, overlapping buildings carry jukeboxes (car radios), so it hit reliably. A block
 * entity with no level is not in play yet and is about to be overwritten anyway, so it is left alone.</p>
 */
@Mixin(StructureTemplate.class)
public abstract class StructureTemplateClearMixin {

    @Redirect(method = "placeInWorld", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/Clearable;tryClear(Ljava/lang/Object;)V"))
    private static void dungeontrain$skipUnattached(Object object) {
        if (object instanceof BlockEntity blockEntity && blockEntity.getLevel() == null) return;
        Clearable.tryClear(object);
    }
}

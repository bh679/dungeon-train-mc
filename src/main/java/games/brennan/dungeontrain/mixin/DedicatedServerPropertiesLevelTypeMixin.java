package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import games.brennan.dungeontrain.worldgen.ServerLevelTypePolicy;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Properties;

/**
 * Keeps a dedicated server on the Dungeon Train world preset when WorldWeaver's
 * {@code force_default_world_preset} (on by default) has swapped the {@code level-type} for
 * its own default ({@code wover:normal}). See {@link ServerLevelTypePolicy} for the rules.
 *
 * <p>WorldWeaver does its swap with a {@code @ModifyArg} on the {@code levelType} argument of
 * the {@code WorldDimensionData} constructor call in this constructor. This handler targets the
 * same argument; each {@code @ModifyArg} call is inserted directly before the invoke as its mixin
 * is applied, so the higher {@code priority} (applied later) makes this one run last and see
 * WorldWeaver's result. The {@code Properties} argument still holds what server.properties
 * asked for. Server-only: registered in the {@code "server"} array.</p>
 */
@Mixin(value = DedicatedServerProperties.class, priority = 2000)
public abstract class DedicatedServerPropertiesLevelTypeMixin {

    @Unique
    private static final Logger DUNGEONTRAIN$LOGGER = LoggerFactory.getLogger("DungeonTrain/LevelType");

    @ModifyArg(
            method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/dedicated/DedicatedServerProperties$WorldDimensionData;<init>(Lcom/google/gson/JsonObject;Ljava/lang/String;)V"),
            index = 1)
    private String dungeontrain$keepDungeonTrainPreset(String applied,
                                                       @Local(argsOnly = true) Properties properties) {
        String requested = properties.getProperty("level-type");
        return ServerLevelTypePolicy.override(requested, applied).map(levelType -> {
            DUNGEONTRAIN$LOGGER.info("[DT-LevelType] server.properties level-type '{}' resolved to '{}'; using '{}'",
                    requested, applied, levelType);
            return levelType;
        }).orElse(applied);
    }
}

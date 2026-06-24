package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rewords vanilla's "Respawn point set" ({@code block.minecraft.set_spawn}) when a player sleeps
 * in a bed, so it makes clear the respawn rides with the train rather than reading like a fixed
 * overworld spawn. The respawn itself is left untouched — on the train, Sable's {@code respawn_point}
 * mixin anchors it to the moving sub-level (a working "wake aboard the train" point); off the train,
 * vanilla sets it normally. Both paths emit the message through
 * {@link ServerPlayer#sendSystemMessage(Component)}, so we intercept there.
 *
 * <p>We inject at HEAD of {@code sendSystemMessage(Component)} and, only for the
 * {@code block.minecraft.set_spawn} translatable in a train world, send one of a small flavour pool
 * instead and cancel the original. Replacing the message at the send site (rather than the
 * respawn-set site) is robust to who set the spawn — vanilla or Sable, which {@code ci.cancel()}s the
 * vanilla path before any other injector runs. The re-dispatched flavour line is a different
 * translation key, so the HEAD check skips it and it sends normally (no recursion). Gated on
 * {@code startsWithTrain} so non-train worlds keep vanilla wording; {@code /spawnpoint} is unaffected
 * (it uses {@code commands.spawnpoint.success}, not {@code set_spawn}).</p>
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerBedSpawnMixin {

    /** Vanilla respawn-set message key, sent by both the vanilla and Sable bed paths. */
    private static final String SET_SPAWN_KEY = "block.minecraft.set_spawn";

    /** Number of {@code chat.dungeontrain.bed.no_respawn.N} flavour lines in en_us.json (1-based). */
    private static final int MESSAGE_COUNT = 5;

    @Inject(method = "sendSystemMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$rewordBedSpawn(Component message, CallbackInfo ci) {
        if (!(message.getContents() instanceof TranslatableContents tc) || !SET_SPAWN_KEY.equals(tc.getKey())) {
            return;
        }
        ServerPlayer self = (ServerPlayer) (Object) this;
        MinecraftServer server = self.getServer();
        if (server == null) return;
        if (!DungeonTrainWorldData.get(server.overworld()).startsWithTrain()) return; // non-train worlds keep vanilla wording

        int n = self.serverLevel().getRandom().nextInt(MESSAGE_COUNT) + 1;
        self.sendSystemMessage(Component.translatable("chat.dungeontrain.bed.no_respawn." + n)
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));   // different key → HEAD check skips it
        ci.cancel();                                                       // suppress the vanilla "Respawn point set"
    }
}

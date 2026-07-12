package games.brennan.dungeontrain.fabric.mixin.client;

import games.brennan.dungeontrain.platform.event.DtEvents;
import games.brennan.dungeontrain.platform.event.DtMusicSelection;
import games.brennan.dungeontrain.platform.event.DtSelectMusicCallback;
import net.minecraft.client.sounds.MusicManager;
import net.minecraft.sounds.Music;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gap-filler for {@code DtEvents.SELECT_MUSIC} (NeoForge {@code SelectMusicEvent}). Fabric
 * has no music-selection event; overrides the return of {@code MusicManager.getSituationalMusic}
 * via a live {@link DtMusicSelection} carrier (void/nether biome music).
 */
@Mixin(MusicManager.class)
public abstract class MusicManagerMixin {

    @Inject(method = "getSituationalMusic", at = @At("RETURN"), cancellable = true)
    private void dungeonTrain$selectMusic(CallbackInfoReturnable<Music> cir) {
        if (DtEvents.SELECT_MUSIC.isEmpty()) {
            return;
        }
        final Music[] holder = { cir.getReturnValue() };
        DtMusicSelection sel = new DtMusicSelection() {
            @Override public Music getOriginalMusic() { return holder[0]; }
            @Override public void setMusic(Music music) { holder[0] = music; }
        };
        for (DtSelectMusicCallback cb : DtEvents.SELECT_MUSIC.listeners()) {
            cb.onSelectMusic(sel);
        }
        cir.setReturnValue(holder[0]);
    }
}

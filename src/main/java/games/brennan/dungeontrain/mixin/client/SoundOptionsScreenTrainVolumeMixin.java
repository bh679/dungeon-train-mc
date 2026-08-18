package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.sound.TrainVolumeOption;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.SoundOptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds Dungeon Train's <b>engine volume</b> slider to vanilla's Music &amp; Sounds screen, so the
 * setting sits with the volumes it belongs beside rather than only in the mod's own menus. The
 * control itself is {@link TrainVolumeOption}, shared verbatim with
 * {@code DungeonTrainClientOptionsScreen}.
 *
 * <p><b>Why a mixin, when the main Options screen only needed an event.</b>
 * {@code OptionsScreenDungeonTrainButton} adds its button through
 * {@code ScreenEvent.Init.Post}, which can reach a screen's own widgets. It cannot reach these:
 * {@link SoundOptionsScreen} builds its rows into an {@code OptionsList}, and that list's entries
 * are not the screen's listeners — the list is. Adding a row means calling {@code addSmall} on it,
 * from inside the class that owns it, at the moment the other rows are built.
 *
 * <p><b>Why this class extends {@link OptionsSubScreen}.</b> The {@code list} field is declared on
 * that superclass, not on the target, and {@code @Shadow} does not reach inherited members — it
 * searches the target class alone and fails at apply time with "@Shadow field list was not located
 * in the target class". Declaring the superclass instead lets {@code this.list} compile against the
 * real field and resolve through the hierarchy once the method is transplanted. The constructor
 * below exists only to satisfy javac; Mixin never transplants it.</p>
 *
 * <p><b>Injected at TAIL, so the row lands at the bottom of the list</b> — below Show Subtitles and
 * Directional Audio, rather than up among the category sliders where it arguably reads better.
 * That placement would mean targeting an {@code INVOKE} in the middle of {@code addOptions}, and
 * {@code dungeontrain.mixins.json} declares {@code "required": true}: a target another options mod
 * disturbs would be a startup crash rather than a missing row. On a four-line method, TAIL is the
 * injection least able to break, and the sound screen is short enough that the bottom row is in
 * plain sight.</p>
 */
@Mixin(SoundOptionsScreen.class)
public abstract class SoundOptionsScreenTrainVolumeMixin extends OptionsSubScreen {

    /** Never called — Mixin discards mixin constructors. Present so the class compiles. */
    private SoundOptionsScreenTrainVolumeMixin(Screen lastScreen, Options options, Component title) {
        super(lastScreen, options, title);
    }

    @Inject(method = "addOptions", at = @At("TAIL"))
    private void dungeontrain$addTrainEngineVolume(CallbackInfo ci) {
        if (this.list != null) {
            this.list.addSmall(TrainVolumeOption.forSoundScreen());
        }
    }
}

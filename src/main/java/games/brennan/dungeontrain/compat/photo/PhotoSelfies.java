package games.brennan.dungeontrain.compat.photo;

import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.compat.vista.TvItems;
import games.brennan.dungeontrain.registry.ModItems;
import io.github.mortuusars.exposure.util.ExtraData;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The Darkroom's selfie advancements: a selfie (Exposure's {@link Frame#SELFIE}, toggled with F5 in the
 * viewfinder) taken while wearing the Live Eye, or with a Vista TV on your head. Read off the
 * photographer's head slot as the frame lands ({@link PhotoAdvancementEvents}).
 */
final class PhotoSelfies {

    static final String SELFIE_LIVE_EYE = "selfie_live_eye";
    static final String SELFIE_TV_HEAD = "selfie_tv_head";

    private PhotoSelfies() {}

    static void trigger(ServerPlayer player, ExtraData data) {
        boolean selfie = data.getOrDefault(Frame.SELFIE, false);
        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        for (String action : actions(selfie, head.is(ModItems.LIVE_HEADPIECE.get()), TvItems.isTv(head))) {
            ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, action);
        }
    }

    /** Pure, for the tests: which selfie advancements a shot earns. */
    static List<String> actions(boolean selfie, boolean wearingLiveEye, boolean wearingTv) {
        List<String> out = new ArrayList<>(2);
        if (!selfie) return out;
        if (wearingLiveEye) out.add(SELFIE_LIVE_EYE);
        if (wearingTv) out.add(SELFIE_TV_HEAD);
        return out;
    }
}

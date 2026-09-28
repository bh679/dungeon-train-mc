package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * The client half of a relay owner proof: tell Mojang's session server that this signed-in account
 * is "joining" the relay's {@code serverId}, exactly as the vanilla client does when it joins an
 * online-mode server. The relay then asks {@code hasJoined} and learns which account did it — the
 * access token itself never leaves this client for anywhere but Mojang.
 *
 * <p>Client-only. {@code RelayOwnerProof} reaches this only after checking it is running on the
 * physical client, so a dedicated server never loads the class.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class OwnerProofJoin {

    private static final Logger LOGGER = LogUtils.getLogger();

    private OwnerProofJoin() {}

    /**
     * Join {@code serverId} as the signed-in account, if that account is {@code expected}. Blocking —
     * it is a Mojang round trip — so callers run it off the game threads. False on any failure: an
     * offline/dev account, a different account from the player asking, or Mojang refusing.
     */
    public static boolean join(UUID expected, String serverId) {
        try {
            Minecraft mc = Minecraft.getInstance();
            User user = mc == null ? null : mc.getUser();
            if (user == null || serverId == null || serverId.isEmpty()) return false;
            if (user.getType() != User.Type.MSA) return false;   // offline/legacy: nothing Mojang can vouch for
            if (!user.getProfileId().equals(expected)) return false;
            mc.getMinecraftSessionService().joinServer(user.getProfileId(), user.getAccessToken(), serverId);
            return true;
        } catch (Throwable t) {
            LOGGER.info("[DungeonTrain] Relay owner proof: session join failed — {}", t.toString());
            return false;
        }
    }
}

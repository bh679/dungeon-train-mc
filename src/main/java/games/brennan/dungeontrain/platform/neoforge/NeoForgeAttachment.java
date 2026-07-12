package games.brennan.dungeontrain.platform.neoforge;

import games.brennan.dungeontrain.platform.DtAttachment;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.attachment.AttachmentType;

import java.util.function.Supplier;

/**
 * NeoForge {@link DtAttachment} impl: a thin wrapper over a
 * {@code Supplier<AttachmentType<T>>} (as returned by
 * {@code games.brennan.dungeontrain.registry.ModDataAttachments}) that
 * delegates straight to {@code Player.getData/setData/hasData}.
 *
 * <p>The {@code AttachmentType} REGISTRATION stays in {@code ModDataAttachments}
 * (root-only, NeoForge-specific); this class only adapts the loader-neutral
 * {@link DtAttachment} read/write contract onto it, so converted game logic in
 * {@code :common} never touches {@code AttachmentType} or {@code Player.getData}
 * directly.</p>
 */
public final class NeoForgeAttachment<T> implements DtAttachment<T> {

    private final Supplier<AttachmentType<T>> type;

    public NeoForgeAttachment(Supplier<AttachmentType<T>> type) {
        this.type = type;
    }

    @Override
    public T get(Player player) {
        return player.getData(type.get());
    }

    @Override
    public void set(Player player, T value) {
        player.setData(type.get(), value);
    }

    @Override
    public boolean has(Player player) {
        return player.hasData(type.get());
    }
}

package games.brennan.dungeontrain.fabric;

import games.brennan.dungeontrain.platform.DtAttachment;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.world.entity.player.Player;

/**
 * Fabric {@link DtAttachment} impl over a fabric-data-attachment {@link AttachmentType}.
 * {@code get} uses {@code getAttachedOrCreate} to match NeoForge's attach-the-default-on-read
 * contract; {@code has}/{@code set} map straight to {@code hasAttached}/{@code setAttached}.
 */
public final class FabricAttachment<T> implements DtAttachment<T> {

    private final AttachmentType<T> type;
    private final Supplier<T> defaultSupplier;

    public FabricAttachment(AttachmentType<T> type, Supplier<T> defaultSupplier) {
        this.type = type;
        this.defaultSupplier = defaultSupplier;
    }

    @Override
    public T get(Player player) {
        return player.getAttachedOrCreate(type, defaultSupplier);
    }

    @Override
    public void set(Player player, T value) {
        player.setAttached(type, value);
    }

    @Override
    public boolean has(Player player) {
        return player.hasAttached(type);
    }
}

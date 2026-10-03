package games.brennan.dungeontrain.client.deathphotos;

import games.brennan.dungeontrain.client.snapshot.RideSnapshot;
import games.brennan.dungeontrain.client.snapshot.RideSnapshotExporter;
import games.brennan.dungeontrain.client.snapshot.ShotSavedClient;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A ride snapshot on the photo page. The texture belongs to {@code RideSnapshotGallery}, which the
 * death screen keeps frozen while it is open, so this wrapper never releases it.
 */
public record RideDeathPhoto(RideSnapshot shot) implements DeathPhoto {

    @Override
    public Status status() {
        return Status.READY;
    }

    @Override
    public ResourceLocation texture() {
        return shot.texture();
    }

    @Override
    public int width() {
        return shot.width();
    }

    @Override
    public int height() {
        return shot.height();
    }

    @Override
    public float aspect() {
        return shot.aspect();
    }

    @Override
    public Path save() throws IOException {
        Path saved = RideSnapshotExporter.save(shot);
        ShotSavedClient.markSaved(shot.photoId()); // tag this shot user-saved on the relay
        return saved;
    }

    @Override
    public void release() {
        // Owned by RideSnapshotGallery.
    }
}

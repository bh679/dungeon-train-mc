package games.brennan.dungeontrain.client.deathphotos;

import games.brennan.dungeontrain.client.snapshot.RideSnapshot;
import io.github.mortuusars.exposure.world.camera.frame.Frame;

import java.util.ArrayList;
import java.util.List;

/**
 * The photos the death screen's photo page shows: the player's own disposable-camera shots first,
 * then this run's ride snapshots, each in capture order.
 *
 * <p>Only one set is alive at a time — building a new one releases the textures the previous set's
 * camera photos created, so a long session doesn't accumulate one texture per old death.</p>
 */
public final class DeathPhotoSet {

    private static DeathPhotoSet current;

    private final List<Frame> frames;
    private final List<RideSnapshot> rides;
    private final List<DeathPhoto> photos;

    private DeathPhotoSet(List<Frame> frames, List<RideSnapshot> rides) {
        this.frames = List.copyOf(frames);
        this.rides = List.copyOf(rides);
        List<DeathPhoto> all = new ArrayList<>(frames.size() + rides.size());
        for (Frame f : frames) all.add(new CameraDeathPhoto(f));
        for (RideSnapshot s : rides) all.add(new RideDeathPhoto(s));
        this.photos = List.copyOf(all);
    }

    /**
     * The set for these inputs — the live one when they haven't changed (so developed camera
     * textures survive page swaps), otherwise a fresh set that replaces and releases the old one.
     */
    public static synchronized DeathPhotoSet of(List<Frame> frames, List<RideSnapshot> rides) {
        if (current != null && current.frames.equals(frames) && current.rides.equals(rides)) {
            return current;
        }
        if (current != null) current.release();
        current = new DeathPhotoSet(frames, rides);
        return current;
    }

    public List<DeathPhoto> photos() {
        return photos;
    }

    public boolean isEmpty() {
        return photos.isEmpty();
    }

    public int size() {
        return photos.size();
    }

    private void release() {
        for (DeathPhoto p : photos) p.release();
    }
}

package games.brennan.dungeontrain.train;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.joml.Vector3dc;

/**
 * Train state that must survive a world save/reload. Stored as a VS ship
 * attachment ({@code ship.saveAttachment(TrainPersistentData.class, data)}),
 * which VS persists into the ship DTO's {@code persistentAttachedData} JsonNode
 * via Jackson. On ship load we read it back and rebuild a
 * {@link TrainTransformProvider} so the train keeps flying after a rejoin.
 *
 * Mutable fields with a public no-arg ctor so Jackson can deserialize without
 * custom annotations.
 */
public final class TrainPersistentData {

    private double velocityX;
    private double velocityY;
    private double velocityZ;
    private int shipyardOriginX;
    private int shipyardOriginY;
    private int shipyardOriginZ;
    private int count;
    private String dimensionKey;

    public TrainPersistentData() {}

    public TrainPersistentData(Vector3dc velocity, BlockPos shipyardOrigin, int count, ResourceKey<Level> dimension) {
        this.velocityX = velocity.x();
        this.velocityY = velocity.y();
        this.velocityZ = velocity.z();
        this.shipyardOriginX = shipyardOrigin.getX();
        this.shipyardOriginY = shipyardOrigin.getY();
        this.shipyardOriginZ = shipyardOrigin.getZ();
        this.count = count;
        this.dimensionKey = dimension.location().toString();
    }

    public double getVelocityX() { return velocityX; }
    public double getVelocityY() { return velocityY; }
    public double getVelocityZ() { return velocityZ; }
    public int getShipyardOriginX() { return shipyardOriginX; }
    public int getShipyardOriginY() { return shipyardOriginY; }
    public int getShipyardOriginZ() { return shipyardOriginZ; }
    public int getCount() { return count; }
    public String getDimensionKey() { return dimensionKey; }

    public void setVelocityX(double v) { this.velocityX = v; }
    public void setVelocityY(double v) { this.velocityY = v; }
    public void setVelocityZ(double v) { this.velocityZ = v; }
    public void setShipyardOriginX(int v) { this.shipyardOriginX = v; }
    public void setShipyardOriginY(int v) { this.shipyardOriginY = v; }
    public void setShipyardOriginZ(int v) { this.shipyardOriginZ = v; }
    public void setCount(int v) { this.count = v; }
    public void setDimensionKey(String v) { this.dimensionKey = v; }
}

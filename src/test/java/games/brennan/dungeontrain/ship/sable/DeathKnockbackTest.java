package games.brennan.dungeontrain.ship.sable;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeathKnockbackTest {

    private static final double EPS = 1.0e-9;
    private static final Vec3 BODY = new Vec3(10, 64, 10);

    @Test
    void projectileThrowsAlongItsFlight() {
        Vec3 thrown = DeathKnockback.projectile(new Vec3(1.5, 0, 0), null, BODY);
        assertEquals(1.5 * DeathKnockback.PROJECTILE_SCALE, thrown.x, EPS);
        assertEquals(0, thrown.z, EPS);
        assertTrue(thrown.y > 0, "a projectile throw lifts the body");
    }

    @Test
    void fasterProjectileThrowsHarderUpToTheCap() {
        double slow = DeathKnockback.projectile(new Vec3(0, 0, 1), null, BODY).length();
        double fast = DeathKnockback.projectile(new Vec3(0, 0, 2), null, BODY).length();
        double huge = DeathKnockback.projectile(new Vec3(0, 0, 50), null, BODY).length();
        assertTrue(fast > slow);
        assertEquals(DeathKnockback.PROJECTILE_MAX, huge, EPS);
    }

    @Test
    void stoppedProjectileFallsBackToTheShootersDirection() {
        Vec3 thrown = DeathKnockback.projectile(Vec3.ZERO, new Vec3(0, 64, 10), BODY);
        assertTrue(thrown.x > 0, "thrown away from the shooter");
        assertEquals(0, thrown.z, EPS);
    }

    @Test
    void projectileWithNoDirectionThrowsNothing() {
        assertEquals(Vec3.ZERO, DeathKnockback.projectile(null, null, BODY));
        assertEquals(Vec3.ZERO, DeathKnockback.projectile(Vec3.ZERO, BODY, BODY));
    }

    @Test
    void blastThrowsAwayFromTheCentre() {
        Vec3 thrown = DeathKnockback.explosion(new Vec3(8, 64, 10), BODY, 4f);
        assertTrue(thrown.x > 0, "away from the centre");
        assertEquals(0, thrown.z, EPS);
        assertTrue(thrown.y > 0, "a blast lifts the body");
    }

    @Test
    void blastFallsOffWithDistance() {
        double near = DeathKnockback.explosion(BODY.add(-1, 0, 0), BODY, 4f).length();
        double far = DeathKnockback.explosion(BODY.add(-5, 0, 0), BODY, 4f).length();
        assertTrue(near > far, "nearer the blast throws harder");
        // Vanilla's falloff: impact = 1 - d / 2r. At d = 5, r = 4: 0.375.
        double impact = 1 - 5.0 / 8.0;
        Vec3 expected = new Vec3(DeathKnockback.BLAST_SPEED * impact, DeathKnockback.BLAST_LIFT * impact, 0);
        assertEquals(expected.length(), far, EPS);
    }

    @Test
    void biggerBlastThrowsHarderAtTheSameDistance() {
        double tnt = DeathKnockback.explosion(BODY.add(-4, 0, 0), BODY, 4f).length();
        double crystal = DeathKnockback.explosion(BODY.add(-4, 0, 0), BODY, 6f).length();
        assertTrue(crystal > tnt);
    }

    @Test
    void powerFactorIsBounded() {
        assertEquals(1.0, DeathKnockback.powerFactor(4f), EPS);
        assertEquals(DeathKnockback.MIN_POWER_FACTOR, DeathKnockback.powerFactor(0.1f), EPS);
        assertEquals(DeathKnockback.MAX_POWER_FACTOR, DeathKnockback.powerFactor(100f), EPS);
    }

    @Test
    void blastOutOfReachThrowsNothing() {
        assertEquals(Vec3.ZERO, DeathKnockback.explosion(BODY.add(-8, 0, 0), BODY, 4f));
        assertEquals(Vec3.ZERO, DeathKnockback.explosion(BODY.add(-20, 0, 0), BODY, 4f));
        assertEquals(Vec3.ZERO, DeathKnockback.explosion(BODY, BODY, 0f));
    }

    @Test
    void bodyAtTheCentreGoesStraightUp() {
        Vec3 thrown = DeathKnockback.explosion(BODY, BODY, 4f);
        assertEquals(0, thrown.x, EPS);
        assertEquals(0, thrown.z, EPS);
        assertTrue(thrown.y > 0);
    }

    @Test
    void hugeBlastIsCappedLiftIncluded() {
        Vec3 thrown = DeathKnockback.explosion(BODY.add(-0.5, 0, 0), BODY, 50f);
        assertEquals(DeathKnockback.BLAST_MAX, thrown.length(), EPS);
    }

    @Test
    void onATrainTheThrowAcrossTheTrackIsCut() {
        Vec3 thrown = DeathKnockback.keepAboard(new Vec3(4, 2, 10), new Vec3(1, 0, 0));
        assertEquals(4, thrown.x, EPS);
        assertEquals(2, thrown.y, EPS);
        assertEquals(DeathKnockback.ON_TRAIN_ACROSS_MAX, thrown.z, EPS);
    }

    @Test
    void onATrainTheThrowAlongTheTrackIsKept() {
        Vec3 along = new Vec3(-10, 3, 1);
        assertEquals(along, DeathKnockback.keepAboard(along, new Vec3(-1, 0, 0)));
    }

    @Test
    void keepAboardFollowsATurnedCarriage() {
        // Track running along Z: the X part is the one across it.
        Vec3 thrown = DeathKnockback.keepAboard(new Vec3(-8, 0, 5), new Vec3(0, 0.1, 2));
        assertEquals(-DeathKnockback.ON_TRAIN_ACROSS_MAX, thrown.x, EPS);
        assertEquals(5, thrown.z, EPS);
    }

    @Test
    void keepAboardIgnoresAnAxisWithNoHeading() {
        Vec3 thrown = new Vec3(5, 1, 5);
        assertEquals(thrown, DeathKnockback.keepAboard(thrown, new Vec3(0, 1, 0)));
    }
}

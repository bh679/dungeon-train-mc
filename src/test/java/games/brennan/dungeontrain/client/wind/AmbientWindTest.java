package games.brennan.dungeontrain.client.wind;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmbientWindTest {

    @Test
    @DisplayName("ambient allow-list: weather, smoke, snow and petals blow; combat and streaks don't")
    void vanillaAllowList() {
        assertEquals(WindParticleTypes.LIGHT, WindParticleTypes.weightFor("minecraft", "snowflake"));
        assertEquals(WindParticleTypes.SMOKE, WindParticleTypes.weightFor("minecraft", "campfire_cosy_smoke"));
        assertEquals(WindParticleTypes.SPLASH, WindParticleTypes.weightFor("minecraft", "rain"));
        assertEquals(WindParticleTypes.HEAVY, WindParticleTypes.weightFor("minecraft", "falling_water"));
        assertEquals(0.0f, WindParticleTypes.weightFor("minecraft", "crit"));
        assertEquals(0.0f, WindParticleTypes.weightFor("minecraft", "cloud"));
        assertEquals(0.0f, WindParticleTypes.weightFor("minecraft", "dripping_water"));
        assertEquals(0.0f, WindParticleTypes.weightFor("minecraft", "explosion"));
    }

    @Test
    @DisplayName("modded particles join by name, except Windy's own and hanging drips")
    void moddedKeywords() {
        assertEquals(WindParticleTypes.MODDED, WindParticleTypes.weightFor("biomesoplenty", "jacaranda_leaves"));
        assertEquals(WindParticleTypes.MODDED, WindParticleTypes.weightFor("betternether", "falling_petal"));
        assertEquals(0.0f, WindParticleTypes.weightFor("windy", "wind_leaf"));
        assertEquals(0.0f, WindParticleTypes.weightFor("somemod", "dripping_sap_leaf"));
        assertEquals(0.0f, WindParticleTypes.weightFor("somemod", "magic_sparkle"));
    }

    @Test
    @DisplayName("sheet slant rises with the push and is capped")
    void slantCapped() {
        assertEquals(0.0, AmbientWind.slantTarget(0.0, 15.0, 0.6));
        assertEquals(0.0, AmbientWind.slantTarget(-1.0, 15.0, 0.6));
        assertEquals(0.15, AmbientWind.slantTarget(0.01, 15.0, 0.6), 1e-9);
        assertEquals(0.6, AmbientWind.slantTarget(1.0, 15.0, 0.6));
    }

    @Test
    @DisplayName("under a shader pack the lean is stronger, still fits vanilla's table, and the rain is redrawn")
    void shaderRain() {
        assertEquals(0.0, AmbientWind.shaderSlantTarget(0.0));
        // A weak wind (29k blocks past the start, push ~0.0076) still leans at the floor, past the pack's own sway.
        assertEquals(AmbientWind.SHADER_RAIN_LEAN_FLOOR, AmbientWind.shaderSlantTarget(0.0076), 1e-9);
        // A strong one grows past the floor (0.025 * 15 * 2.5 = 0.9375) up to the cap.
        assertEquals(0.9375, AmbientWind.shaderSlantTarget(0.025), 1e-9);
        assertEquals(AmbientWind.SHADER_RAIN_MAX_SLANT, AmbientWind.shaderSlantTarget(1.0));
        assertTrue(AmbientWind.radiusFor(10, AmbientWind.SHADER_RAIN_MAX_SLANT) <= AmbientWind.MAX_RAIN_RADIUS);
        // Extra passes only under a pack, and only once the rain actually leans.
        assertEquals(1, AmbientWind.rainPassesFor(false, 1.0));
        assertEquals(1, AmbientWind.rainPassesFor(true, 0.0));
        assertEquals(1, AmbientWind.rainPassesFor(true, 0.04));
        assertEquals(AmbientWind.SHADER_RAIN_PASSES, AmbientWind.rainPassesFor(true, 0.3));
        // Each pass steps downwind along the lean; no lean, no step.
        assertEquals(2 * AmbientWind.SHADER_PASS_SHIFT, AmbientWind.passShift(2, 0.5, 0.0, true), 1e-9);
        assertEquals(0.0, AmbientWind.passShift(2, 0.5, 0.0, false), 1e-9);
        assertEquals(-AmbientWind.SHADER_PASS_SHIFT, AmbientWind.passShift(1, 0.0, -0.8, false), 1e-9);
        assertEquals(0.0, AmbientWind.passShift(1, 0.0, 0.0, true));
    }

    @Test
    @DisplayName("snow scroll: U offset is minus the distance blown along the quad's axis, kept to one tile")
    void snowScroll() {
        // Blown 0.25 along the quad's axis: texture shifts back 0.25 so flakes move forward.
        assertEquals(-0.25f, AmbientWind.scrollU(0.25, 0.0, 1.0, 0.0), 1e-6);
        // Wind across the quad (perpendicular to its axis) doesn't scroll it.
        assertEquals(0.0f, AmbientWind.scrollU(0.0, 5.0, 1.0, 0.0), 1e-6);
        // Far along, still within one tile.
        float far = AmbientWind.scrollU(12345.7, 0.0, 1.0, 0.0);
        assertTrue(far <= 0.0f && far > -1.0f);
        assertEquals(-0.7f, far, 1e-4);
    }

    @Test
    @DisplayName("snowflakes: none in calm or no snow, rise with the push, capped")
    void snowflakeRate() {
        assertEquals(0.0, WindSnowflakes.flakesPerTick(0.0, 1.0f));
        assertEquals(0.0, WindSnowflakes.flakesPerTick(0.02, 0.0f));
        assertEquals(1.5, WindSnowflakes.flakesPerTick(0.01, 1.0f), 1e-9);
        assertEquals(0.75, WindSnowflakes.flakesPerTick(0.01, 0.5f), 1e-9);
        assertEquals(WindSnowflakes.MAX_PER_TICK, WindSnowflakes.flakesPerTick(1.0, 1.0f));
    }

    @Test
    @DisplayName("rain radius widens with the lean, never past vanilla's table")
    void rainRadius() {
        assertEquals(10, AmbientWind.radiusFor(10, 0.0));
        assertEquals(12, AmbientWind.radiusFor(10, 0.15));
        assertEquals(AmbientWind.MAX_RAIN_RADIUS, AmbientWind.radiusFor(10, 0.6));
        assertEquals(8, AmbientWind.radiusFor(5, 0.3));
    }

    @Test
    @DisplayName("slant eases toward its target and settles to exactly zero")
    void slantEases() {
        double s = AmbientWind.ease(0.0, 0.5);
        assertTrue(s > 0.0 && s < 0.5);
        double v = 0.5;
        for (int i = 0; i < 200; i++) v = AmbientWind.ease(v, 0.0);
        assertEquals(0.0, v);
    }
}

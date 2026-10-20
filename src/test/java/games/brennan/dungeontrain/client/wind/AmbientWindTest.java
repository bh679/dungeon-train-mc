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
    @DisplayName("slant eases toward its target and settles to exactly zero")
    void slantEases() {
        double s = AmbientWind.ease(0.0, 0.5);
        assertTrue(s > 0.0 && s < 0.5);
        double v = 0.5;
        for (int i = 0; i < 200; i++) v = AmbientWind.ease(v, 0.0);
        assertEquals(0.0, v);
    }
}

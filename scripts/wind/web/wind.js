// Line-for-line port of src/main/java/games/brennan/dungeontrain/wind/{WindField,WindCurve}.java and
// WindProfile#stageAt. Keep the arithmetic identical (+ - * /, floor, log, pow, 32-bit integer hashing) so the
// tuner graphs exactly what the game does. golden.json (written by WindFieldTest) is checked on page load: a red
// badge means this file and the Java have drifted.

export const GUST_SLOT = 20;
export const MIN_GUST_SECONDS = 0.1;
export const MAX_GUSTS_PER_MIN = 60.0;
const MIN_GUST_TICKS = MIN_GUST_SECONDS * 20.0;
const GUST_ATTACK = 0.25;
const FLUTTER_PERIOD = 40.0;
const DEG = Math.PI / 180.0;

const SALT_WANDER = 0x1F3D5B79;
const SALT_WANDER2 = 0x2A4C6E80;
const SALT_FLUTTER = 0x3B5D7F91;
const SALT_FIRE = 1;
const SALT_OFFSET = 2;
const SALT_POWER = 3;
const SALT_VEER = 4;
const SALT_LENGTH = 5;
const SALT_NOISE = 7;

export const CURVE_NAMES = ["base", "flutter", "gustsPerMin", "gustStrength", "gustSeconds", "gustVeer", "wander", "wanderSeconds"];

export const CALM = Object.freeze({ strength: 0, headingRad: 0, gust01: 0 });

/** WindField.Style: how a band reshapes the wind. NORMAL changes nothing; SKY = WindZones.SKY_STYLE. */
export const NORMAL_STYLE = Object.freeze({ gustRate: 1.0, gustStrength: 1.0, flutter: 1.0, flutterSlow: 1.0, wanderSlow: 1.0, minFlutter: 0.0 });
export const SKY_STYLE = Object.freeze({ gustRate: 0.5, gustStrength: 0.5, flutter: 3.0, flutterSlow: 4.0, wanderSlow: 3.0, minFlutter: 1.5 });

export function curveAt(c, d) {
  const x = Math.max(0.0, d);
  if (c.kind === "log") return c.scale * Math.log(1.0 + x / c.ref);
  return c.max - (c.max - c.start) * Math.pow(0.5, x / c.halfLife);
}

export function stageAt(profile, d) {
  const c = profile.curves;
  if (!c) return null;
  return {
    at: d,
    base: Math.max(0.0, curveAt(c.base, d)),
    flutter: Math.max(0.0, curveAt(c.flutter, d)),
    gustsPerMin: Math.min(MAX_GUSTS_PER_MIN, Math.max(0.0, curveAt(c.gustsPerMin, d))),
    gustStrength: Math.max(0.0, curveAt(c.gustStrength, d)),
    gustTicks: Math.max(MIN_GUST_SECONDS, curveAt(c.gustSeconds, d)) * 20.0,
    gustVeer: Math.max(0.0, curveAt(c.gustVeer, d)),
    wander: Math.max(0.0, curveAt(c.wander, d)),
    wanderSeconds: Math.max(1.0, curveAt(c.wanderSeconds, d)),
  };
}

export function hash01(seed, cell, salt) {
  let h = (seed | 0) ^ Math.imul(cell | 0, 0x9E3779B1 | 0) ^ Math.imul(salt | 0, 0x85EBCA77 | 0);
  h ^= h >>> 16;
  h = Math.imul(h, 0x7FEB352D);
  h ^= h >>> 15;
  h = Math.imul(h, 0x846CA68B | 0);
  h ^= h >>> 16;
  return (h >>> 0) / 4294967296.0;
}

function smooth(f) {
  return f * f * (3.0 - 2.0 * f);
}

export function noise(seed, x) {
  const fi = Math.floor(x);
  const f = x - fi;
  const a = hash01(seed, fi, SALT_NOISE) * 2.0 - 1.0;
  const b = hash01(seed, fi + 1, SALT_NOISE) * 2.0 - 1.0;
  return a + (b - a) * smooth(f);
}

/** The wind d blocks past the Upside-down end at game tick `tick`. Pass a precomputed stage to skip curveAt. */
export function sample(profile, d, tick, seed, stage, style = NORMAL_STYLE) {
  if (!profile.enabled || !(d >= 0.0) || !profile.curves) return CALM;
  const b = stage || stageAt(profile, d);
  const s = {
    ...b,
    flutter: Math.max(style.minFlutter, b.flutter * style.flutter),
    gustsPerMin: b.gustsPerMin * style.gustRate,
    gustStrength: b.gustStrength * style.gustStrength,
    wanderSeconds: b.wanderSeconds * style.wanderSlow,
  };
  const t = tick;

  const period = Math.max(20.0, s.wanderSeconds * 20.0);
  const n = noise(seed ^ SALT_WANDER, t / period) + 0.35 * noise(seed ^ SALT_WANDER2, t / (period / 3.0));
  const headingDeg = profile.headingDeg + s.wander * (n / 1.35);

  const fl = noise(seed ^ SALT_FLUTTER, t / (FLUTTER_PERIOD * style.flutterSlow));
  const steady = Math.max(0.0, s.base * (1.0 + s.flutter * fl));

  const p = Math.min(1.0, s.gustsPerMin * GUST_SLOT / 1200.0);
  const longest = Math.max(MIN_GUST_TICKS, s.gustTicks);
  let gust = 0.0;
  let veer = 0.0;
  if (p > 0.0 && s.gustStrength > 0.0) {
    const k = Math.floor(tick / GUST_SLOT);
    const back = Math.ceil(longest / GUST_SLOT) + 1;
    for (let j = k - back; j <= k; j++) {
      if (hash01(seed, j, SALT_FIRE) >= p) continue;
      const start = j * GUST_SLOT + hash01(seed, j, SALT_OFFSET) * GUST_SLOT;
      const dur = MIN_GUST_TICKS + hash01(seed, j, SALT_LENGTH) * (longest - MIN_GUST_TICKS);
      const u = (t - start) / dur;
      if (u < 0.0 || u >= 1.0) continue;
      const env = u < GUST_ATTACK ? smooth(u / GUST_ATTACK) : smooth((1.0 - u) / (1.0 - GUST_ATTACK));
      const g = s.gustStrength * (0.6 + 0.4 * hash01(seed, j, SALT_POWER)) * env;
      if (g > gust) {
        gust = g;
        veer = (hash01(seed, j, SALT_VEER) * 2.0 - 1.0) * s.gustVeer * env;
      }
    }
  }

  const strength = steady + gust;
  if (strength <= 0.0) return CALM;
  return { strength, headingRad: (headingDeg + veer) * DEG, gust01: Math.min(1.0, gust / strength) };
}

/** Compare against golden.json rows; returns { ok, checked, worst }. */
export function checkGolden(golden) {
  let worst = 0;
  for (const r of golden.rows) {
    const w = sample(golden.profile, r.d, r.tick, golden.seed);
    worst = Math.max(worst, Math.abs(w.strength - r.strength), Math.abs(w.headingRad - r.headingRad),
      Math.abs(w.gust01 - r.gust01));
  }
  return { ok: golden.rows.length > 0 && worst < 1e-12, checked: golden.rows.length, worst };
}

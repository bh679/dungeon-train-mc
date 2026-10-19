// Sound preview: plays the vanilla sound the game uses for the wind (client/wind/WindSound.java), driven by the
// same wind model, so you can hear the breeze and the gusts at any spot before testing in a client.
// Constants mirror WindSound.java — keep them in step.
import { sample } from "./wind.js";

const FULL_PUSH = 0.03;
const BREEZE_MAX_VOLUME = 0.6;
const BREEZE_GUST_BOOST = 0.35;
const GUST_MAX_VOLUME = 1.0;
const EASE = 0.12;
/** sounds.json gives item.elytra.flying a volume of 0.6; the game multiplies it in. */
const ELYTRA_JSON_VOLUME = 0.6;
const MUFFLE = { open: 1.0, lee: 0.7, cover: 0.0, tunnel: 0.0 };

const $ = (id) => document.getElementById(id);
let ctx = null;
let buffers = null;
let running = null;

async function load(name) {
  const res = await fetch(`/api/sound/${name}`);
  if (!res.ok) throw new Error((await res.json()).error || res.status);
  return ctx.decodeAudioData(await res.arrayBuffer());
}

async function ensureBuffers() {
  if (!ctx) ctx = new AudioContext();
  if (!buffers) {
    buffers = { loop: await load("elytra_loop") };
  }
}

function layer(basePitch, offsetSeconds) {
  const src = ctx.createBufferSource();
  src.buffer = buffers.loop;
  src.loop = true;
  src.playbackRate.value = basePitch;
  const gain = ctx.createGain();
  gain.gain.value = 0.001;
  src.connect(gain).connect(ctx.destination);
  // The game's two loops start at different moments, so they never line up; offset the copy the same way.
  src.start(0, offsetSeconds);
  return { src, gain, volume: 0.001, pitch: basePitch };
}

function ease(l, volumeTarget, pitchTarget) {
  l.volume = Math.max(0.001, l.volume + (volumeTarget - l.volume) * EASE);
  l.pitch = l.pitch + (Math.max(0.5, Math.min(2.0, pitchTarget)) - l.pitch) * EASE;
  l.gain.gain.value = l.volume * ELYTRA_JSON_VOLUME;
  l.src.playbackRate.value = l.pitch;
}

/** Start the preview. `state()` returns { profile, d, seed, ride, speedPerTick }. */
export async function start(state, onFrame) {
  stop();
  try {
    await ensureBuffers();
  } catch (e) {
    onFrame({ error: `Could not load sounds: ${e.message}` });
    return;
  }
  await ctx.resume();
  const breeze = layer(0.8, 0);
  const gust = layer(0.8, buffers.loop.duration / 2);
  let tick = 20 * 60 * 30;
  let rideD = null;
  const timer = setInterval(() => {
    const s = state();
    if (rideD === null || !s.ride) rideD = s.d;
    const d = s.ride ? rideD : s.d;
    if (s.ride) rideD += s.speedPerTick;
    tick++;
    const w = sample(s.profile, d, tick, s.seed, null, s.style);
    const muffle = MUFFLE[$("muffle").value] ?? 1.0;
    const level = Math.min(1.0, w.strength / FULL_PUSH);
    const pitch = (0.55 + 0.6 * level) * (muffle < 1.0 ? 0.8 : 1.0);
    const loud = Math.sqrt(level) * muffle;
    ease(breeze, loud * (BREEZE_MAX_VOLUME + BREEZE_GUST_BOOST * w.gust01), pitch);
    ease(gust, loud * GUST_MAX_VOLUME * w.gust01, pitch);
    onFrame({ d, w, breeze: breeze.volume, gust: gust.volume, breezePitch: breeze.pitch });
  }, 50);
  running = { timer, layers: [breeze, gust] };
}

export function stop() {
  if (!running) return;
  clearInterval(running.timer);
  for (const l of running.layers) {
    try { l.src.stop(); } catch (e) { /* already stopped */ }
  }
  running = null;
}

export function playing() {
  return running !== null;
}

// Sound preview: plays the vanilla sounds the game uses for the wind (client/wind/WindSound.java), driven by the
// same wind model, so you can hear the breeze and the gusts at any spot before testing in a client.
// Constants mirror WindSound.java — keep them in step.
import { sample } from "./wind.js";

const FULL_PUSH = 0.03;
const BREEZE_MAX_VOLUME = 0.55;
const GUST_MAX_VOLUME = 0.7;
const EASE = 0.12;
const GUST_ON = 0.35;
const GUST_OFF = 0.15;
/** sounds.json gives item.elytra.flying a volume of 0.6; the game multiplies it in. */
const ELYTRA_JSON_VOLUME = 0.6;
const MUFFLE = { open: 1.0, lee: 0.7, cover: 0.35, tunnel: 0.3 };

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
    const [loop, ...whooshes] = await Promise.all(["elytra_loop", "idle_air1", "idle_air2", "idle_air3", "idle_air4"].map(load));
    buffers = { loop, whooshes };
  }
}

function layer(basePitch) {
  const src = ctx.createBufferSource();
  src.buffer = buffers.loop;
  src.loop = true;
  src.playbackRate.value = basePitch;
  const gain = ctx.createGain();
  gain.gain.value = 0.001;
  src.connect(gain).connect(ctx.destination);
  src.start();
  return { src, gain, volume: 0.001, pitch: basePitch };
}

function ease(l, volumeTarget, pitchTarget) {
  l.volume = Math.max(0.001, l.volume + (volumeTarget - l.volume) * EASE);
  l.pitch = l.pitch + (Math.max(0.5, Math.min(2.0, pitchTarget)) - l.pitch) * EASE;
  l.gain.gain.value = l.volume * ELYTRA_JSON_VOLUME;
  l.src.playbackRate.value = l.pitch;
}

function whoosh(w, volume) {
  if (volume <= 0.02) return;
  const src = ctx.createBufferSource();
  src.buffer = buffers.whooshes[Math.floor(Math.random() * buffers.whooshes.length)];
  src.playbackRate.value = 0.8 + Math.random() * 0.3;
  const gain = ctx.createGain();
  gain.gain.value = volume;
  // From upwind, heard facing along the track (+X): the source sits at -wind, +Z is to the right.
  const pan = ctx.createStereoPanner();
  pan.pan.value = Math.max(-1, Math.min(1, -Math.sin(w.headingRad)));
  src.connect(gain).connect(pan).connect(ctx.destination);
  src.start();
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
  const breeze = layer(0.8);
  const gust = layer(1.25);
  let armed = true;
  let tick = 20 * 60 * 30;
  let rideD = null;
  const timer = setInterval(() => {
    const s = state();
    if (rideD === null || !s.ride) rideD = s.d;
    const d = s.ride ? rideD : s.d;
    if (s.ride) rideD += s.speedPerTick;
    tick++;
    const w = sample(s.profile, d, tick, s.seed);
    const muffle = MUFFLE[$("muffle").value] ?? 1.0;
    const level = Math.min(1.0, w.strength / FULL_PUSH);
    const muffledPitch = muffle < 1.0 ? 0.8 : 1.0;
    ease(breeze, Math.sqrt(level) * BREEZE_MAX_VOLUME * muffle, (0.55 + 0.6 * level) * muffledPitch);
    ease(gust, w.gust01 * Math.sqrt(level) * GUST_MAX_VOLUME * muffle, (0.9 + 0.5 * w.gust01) * muffledPitch);
    let whooshed = false;
    if (armed && w.gust01 > GUST_ON) {
      armed = false;
      whooshed = true;
      whoosh(w, (0.25 + 0.5 * level) * muffle);
    } else if (w.gust01 < GUST_OFF) {
      armed = true;
    }
    onFrame({ d, w, breeze: breeze.volume, gust: gust.volume, breezePitch: breeze.pitch, whooshed });
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

package games.brennan.dungeontrain.client.live.audio;

import java.nio.ShortBuffer;

/**
 * The microphone's sample maths, kept free of OpenAL so it can be tested: bringing a capture device's
 * rate to the stream's 48 kHz ({@link Resampler}), gain, a noise gate ({@link NoiseGate}) and the mix
 * into the pump's four-channel frame ({@link #mixInterleave4}).
 *
 * <p>The mic is mono throughout; it is put into both sides of the game's stereo only when mixed.</p>
 */
public final class MicDsp {

    private MicDsp() {}

    /** {@code a + b} held inside signed 16 bits, so a loud voice over loud game sound clips instead of wrapping. */
    static short saturate(int v) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
    }

    /** Scales the first {@code n} samples by {@code gain} in place, clipping at the 16-bit range. */
    static void applyGain(short[] s, int n, float gain) {
        if (gain == 1f) return;
        for (int i = 0; i < n; i++) s[i] = saturate(Math.round(s[i] * gain));
    }

    /**
     * {@code n} frames into {@code out} as {@code mixL, mixR, gameL, gameR}: the stream's first track is
     * the game with the mic over it, the second the game alone. {@code game} is interleaved stereo,
     * {@code mic} mono; both are read from index 0.
     */
    static void mixInterleave4(ShortBuffer game, short[] mic, int n, ShortBuffer out) {
        for (int i = 0; i < n; i++) {
            short l = game.get(2 * i);
            short r = game.get(2 * i + 1);
            short m = mic[i];
            int o = 4 * i;
            out.put(o, saturate(l + m));
            out.put(o + 1, saturate(r + m));
            out.put(o + 2, l);
            out.put(o + 3, r);
        }
    }

    /**
     * Linear resampling, one call per captured block, carrying its place across calls so a stream of
     * blocks comes out as one continuous signal. Plenty for a voice; a 48 kHz device passes through.
     */
    static final class Resampler {
        private final double step;
        /** Where the next output sample sits, in input samples from {@link #prev}. */
        private double pos;
        private short prev;
        private boolean primed;

        Resampler(int inRate, int outRate) {
            this.step = inRate / (double) outRate;
        }

        boolean passThrough() {
            return step == 1.0;
        }

        /** Output samples {@code n} input samples will give at most (for sizing {@code out}). */
        int maxOut(int n) {
            return (int) Math.ceil((n + 1) / step) + 1;
        }

        /** Resamples {@code in[0..n)} into {@code out}; returns how many samples it wrote. */
        int process(short[] in, int n, short[] out) {
            if (n <= 0) return 0;
            if (passThrough()) {
                System.arraycopy(in, 0, out, 0, n);
                return n;
            }
            int written = 0;
            int start = 0;
            if (!primed) {
                prev = in[0];
                primed = true;
                start = 1;
                pos = 0;
            }
            // The signal is prev, in[start], in[start+1] … at positions 0, 1, 2 … from prev.
            int available = n - start;
            while (pos < available) {
                int i = (int) pos;
                double frac = pos - i;
                short a = i == 0 ? prev : in[start + i - 1];
                short b = in[start + i];
                out[written++] = (short) Math.round(a + (b - a) * frac);
                pos += step;
            }
            prev = in[n - 1];
            pos -= available;
            return written;
        }
    }

    /**
     * Silences the mic between words: a block whose RMS is under {@code threshold} is zeroed once the
     * gate has been shut for longer than the hold, so breathing and fan noise do not ride the stream
     * and the tail of a word is not clipped.
     */
    static final class NoiseGate {
        private final double threshold;
        private final int holdSamples;
        private int sinceLoud = Integer.MAX_VALUE / 2;

        NoiseGate(double threshold, int holdSamples) {
            this.threshold = threshold;
            this.holdSamples = holdSamples;
        }

        /** Gates {@code s[0..n)} in place; true when it let the block through. */
        boolean process(short[] s, int n) {
            if (n <= 0) return false;
            if (rms(s, n) >= threshold) {
                sinceLoud = 0;
                return true;
            }
            sinceLoud += n;
            if (sinceLoud <= holdSamples) return true;
            java.util.Arrays.fill(s, 0, n, (short) 0);
            return false;
        }
    }

    static double rms(short[] s, int n) {
        if (n <= 0) return 0;
        double sum = 0;
        for (int i = 0; i < n; i++) sum += (double) s[i] * s[i];
        return Math.sqrt(sum / n);
    }
}

package com.example.clipsong;

import java.util.Arrays;

final class AudioProcessor {
    static final class Settings {
        final int cleanup;
        final int brightness;

        Settings(int cleanup, int brightness) {
            this.cleanup = Math.max(0, Math.min(100, cleanup));
            this.brightness = Math.max(0, Math.min(100, brightness));
        }
    }

    private static final int SR = WavIO.SAMPLE_RATE;

    private AudioProcessor() {}

    static short[] enhance(short[] input, Settings settings) {
        if (input.length == 0) return input;

        double[] x = new double[input.length];
        for (int i = 0; i < input.length; i++) x[i] = input[i] / 32768.0;

        removeDcAndRumble(x);
        noiseGate(x, settings.cleanup);
        presenceAndBrightness(x, settings.brightness);
        compress(x);
        softLimit(x);
        fadeEdges(x, (int)(0.015 * SR));

        short[] out = new short[x.length];
        double peak = 1e-9;
        for (double v : x) peak = Math.max(peak, Math.abs(v));
        double gain = Math.min(3.0, 0.93 / peak);
        for (int i = 0; i < x.length; i++) out[i] = clamp(x[i] * gain * 32767.0);
        return out;
    }

    private static void removeDcAndRumble(double[] x) {
        // One-pole high-pass around 75 Hz: removes DC, handling noise and room rumble.
        double rc = 1.0 / (2.0 * Math.PI * 75.0);
        double dt = 1.0 / SR;
        double a = rc / (rc + dt);
        double prevX = x[0];
        double prevY = 0.0;
        for (int i = 0; i < x.length; i++) {
            double current = x[i];
            double y = a * (prevY + current - prevX);
            x[i] = y;
            prevX = current;
            prevY = y;
        }
    }

    private static void noiseGate(double[] x, int amount) {
        if (amount <= 0 || x.length < 256) return;

        int frame = 512;
        int frames = (x.length + frame - 1) / frame;
        double[] rms = new double[frames];
        for (int f = 0; f < frames; f++) {
            int from = f * frame;
            int to = Math.min(x.length, from + frame);
            double sum = 0.0;
            for (int i = from; i < to; i++) sum += x[i] * x[i];
            rms[f] = Math.sqrt(sum / Math.max(1, to - from));
        }
        double[] sorted = Arrays.copyOf(rms, rms.length);
        Arrays.sort(sorted);
        double noise = sorted[Math.min(sorted.length - 1, Math.max(0, (int)(sorted.length * 0.20)))];
        double threshold = Math.max(0.0035, noise * (1.4 + amount / 100.0 * 2.2));
        double floorGain = 1.0 - amount / 100.0 * 0.92;

        double env = 0.0;
        double gain = 1.0;
        double attack = Math.exp(-1.0 / (0.008 * SR));
        double release = Math.exp(-1.0 / (0.12 * SR));
        double gainSmooth = Math.exp(-1.0 / (0.018 * SR));

        for (int i = 0; i < x.length; i++) {
            double abs = Math.abs(x[i]);
            double c = abs > env ? attack : release;
            env = c * env + (1.0 - c) * abs;
            double target;
            if (env >= threshold) target = 1.0;
            else {
                double t = env / threshold;
                target = floorGain + (1.0 - floorGain) * t * t;
            }
            gain = gainSmooth * gain + (1.0 - gainSmooth) * target;
            x[i] *= gain;
        }
    }

    private static void presenceAndBrightness(double[] x, int amount) {
        double strength = amount / 100.0;

        // Presence: emphasize the 1.5–4 kHz region with a simple two-lowpass band extraction.
        double aFast = lpAlpha(3500.0);
        double aSlow = lpAlpha(1200.0);
        double lpFast = 0.0;
        double lpSlow = 0.0;

        // Air: a conservative high shelf above ~5 kHz.
        double aAir = lpAlpha(5000.0);
        double lpAir = 0.0;

        for (int i = 0; i < x.length; i++) {
            double s = x[i];
            lpFast += aFast * (s - lpFast);
            lpSlow += aSlow * (s - lpSlow);
            lpAir += aAir * (s - lpAir);

            double presence = lpFast - lpSlow;
            double air = s - lpAir;
            x[i] = s + presence * (0.55 * strength) + air * (0.22 * strength);
        }
    }

    private static double lpAlpha(double hz) {
        double rc = 1.0 / (2.0 * Math.PI * hz);
        double dt = 1.0 / SR;
        return dt / (rc + dt);
    }

    private static void compress(double[] x) {
        double env = 0.0;
        double attack = Math.exp(-1.0 / (0.010 * SR));
        double release = Math.exp(-1.0 / (0.160 * SR));
        double threshold = dbToAmp(-18.0);
        double ratio = 3.2;
        double makeup = dbToAmp(5.0);

        for (int i = 0; i < x.length; i++) {
            double a = Math.abs(x[i]);
            double c = a > env ? attack : release;
            env = c * env + (1.0 - c) * a;

            double g = 1.0;
            if (env > threshold) {
                double overDb = ampToDb(env / threshold);
                double reductionDb = overDb - overDb / ratio;
                g = dbToAmp(-reductionDb);
            }
            x[i] *= g * makeup;
        }
    }

    private static void softLimit(double[] x) {
        for (int i = 0; i < x.length; i++) {
            double v = x[i] * 1.15;
            x[i] = Math.tanh(v) / Math.tanh(1.15);
        }
    }

    private static void fadeEdges(double[] x, int n) {
        int m = Math.min(n, x.length / 2);
        for (int i = 0; i < m; i++) {
            double t = i / (double)Math.max(1, m);
            x[i] *= t;
            x[x.length - 1 - i] *= t;
        }
    }

    private static double dbToAmp(double db) {
        return Math.pow(10.0, db / 20.0);
    }

    private static double ampToDb(double amp) {
        return 20.0 * Math.log10(Math.max(1e-9, amp));
    }

    private static short clamp(double v) {
        if (v > 32767.0) return 32767;
        if (v < -32768.0) return -32768;
        return (short)Math.round(v);
    }
}

package com.example.clipsong;

import java.util.Arrays;

final class AudioProcessor {
    enum Timbre { NATURAL, BRIGHT, WARM, AIRY, DEEP, INTIMATE, STUDIO }

    static final class Settings {
        final int cleanup;
        final int brightness;
        final Timbre timbre;
        final int reverb;

        Settings(int cleanup, int brightness) {
            this(cleanup, brightness, Timbre.NATURAL, 12);
        }

        Settings(int cleanup, int brightness, Timbre timbre, int reverb) {
            this.cleanup = Math.max(0, Math.min(100, cleanup));
            this.brightness = Math.max(0, Math.min(100, brightness));
            this.timbre = timbre == null ? Timbre.NATURAL : timbre;
            this.reverb = Math.max(0, Math.min(100, reverb));
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
        deEss(x, settings.timbre == Timbre.BRIGHT || settings.timbre == Timbre.AIRY ? 0.46 : 0.34);
        timbreShape(x, settings);
        compress(x, settings.timbre);
        addSaturation(x, settings.timbre);
        addChorus(x, settings.timbre);
        if (settings.reverb > 0) addReverb(x, settings.reverb, settings.timbre);
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

    private static void deEss(double[] x, double amount) {
        double a = lpAlpha(5200.0);
        double lp = 0.0;
        double env = 0.0;
        double smooth = Math.exp(-1.0 / (0.012 * SR));
        for (int i = 0; i < x.length; i++) {
            double s = x[i];
            lp += a * (s - lp);
            double hi = s - lp;
            env = smooth * env + (1.0 - smooth) * Math.abs(hi);
            double g = 1.0;
            if (env > 0.055) g = Math.max(0.45, 1.0 - amount * (env - 0.055) * 8.0);
            x[i] = lp + hi * g;
        }
    }

    private static void timbreShape(double[] x, Settings settings) {
        int brightness = settings.brightness;
        double strength = brightness / 100.0;

        double a3500 = lpAlpha(3500.0);
        double a1200 = lpAlpha(1200.0);
        double a5000 = lpAlpha(5000.0);
        double a260 = lpAlpha(260.0);
        double lpFast = 0.0, lpSlow = 0.0, lpAir = 0.0, lpLow = 0.0;

        double presenceGain = 0.52 * strength;
        double airGain = 0.18 * strength;
        double lowGain = 0.0;

        switch (settings.timbre) {
            case BRIGHT:
                presenceGain += 0.34; airGain += 0.25; lowGain -= 0.04; break;
            case AIRY:
                presenceGain += 0.18; airGain += 0.46; lowGain -= 0.02; break;
            case WARM:
                presenceGain -= 0.12; airGain -= 0.08; lowGain += 0.20; break;
            case DEEP:
                presenceGain -= 0.18; airGain -= 0.10; lowGain += 0.34; break;
            case INTIMATE:
                presenceGain += 0.08; airGain += 0.04; lowGain += 0.08; break;
            case STUDIO:
                presenceGain += 0.22; airGain += 0.15; lowGain += 0.06; break;
            default:
                break;
        }

        for (int i = 0; i < x.length; i++) {
            double s = x[i];
            lpFast += a3500 * (s - lpFast);
            lpSlow += a1200 * (s - lpSlow);
            lpAir += a5000 * (s - lpAir);
            lpLow += a260 * (s - lpLow);
            double presence = lpFast - lpSlow;
            double air = s - lpAir;
            double low = lpLow;
            x[i] = s + presence * presenceGain + air * airGain + low * lowGain;
        }
    }

    private static void compress(double[] x, Timbre timbre) {
        double env = 0.0;
        double attackMs = timbre == Timbre.INTIMATE ? 0.018 : 0.010;
        double releaseMs = timbre == Timbre.STUDIO ? 0.120 : 0.160;
        double attack = Math.exp(-1.0 / (attackMs * SR));
        double release = Math.exp(-1.0 / (releaseMs * SR));
        double thresholdDb = timbre == Timbre.STUDIO ? -20.0 : -18.0;
        double threshold = dbToAmp(thresholdDb);
        double ratio = timbre == Timbre.STUDIO ? 4.0 : 3.2;
        double makeup = dbToAmp(timbre == Timbre.INTIMATE ? 4.0 : 5.0);

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

    private static void addSaturation(double[] x, Timbre timbre) {
        double drive;
        switch (timbre) {
            case WARM: drive = 1.35; break;
            case DEEP: drive = 1.28; break;
            case STUDIO: drive = 1.22; break;
            default: drive = 1.10; break;
        }
        double norm = Math.tanh(drive);
        for (int i = 0; i < x.length; i++) x[i] = Math.tanh(x[i] * drive) / norm;
    }

    private static void addChorus(double[] x, Timbre timbre) {
        if (timbre != Timbre.AIRY && timbre != Timbre.STUDIO) return;
        double[] src = Arrays.copyOf(x, x.length);
        int base = (int)(0.016 * SR);
        int depth = (int)(0.004 * SR);
        double rate = 0.38;
        double wet = timbre == Timbre.AIRY ? 0.13 : 0.08;
        for (int i = 0; i < x.length; i++) {
            int delay = base + (int)(depth * (0.5 + 0.5 * Math.sin(2.0 * Math.PI * rate * i / SR)));
            int j = i - delay;
            if (j >= 0) x[i] = src[i] * (1.0 - wet) + src[j] * wet;
        }
    }

    private static void addReverb(double[] x, int amount, Timbre timbre) {
        double wet = amount / 100.0 * 0.26;
        if (timbre == Timbre.INTIMATE) wet *= 0.45;
        if (timbre == Timbre.AIRY) wet *= 1.25;
        double[] src = Arrays.copyOf(x, x.length);
        int[] delays = {(int)(0.041 * SR), (int)(0.067 * SR), (int)(0.103 * SR)};
        double[] gains = {0.52, 0.34, 0.22};
        for (int i = 0; i < x.length; i++) {
            double r = 0.0;
            for (int d = 0; d < delays.length; d++) {
                int j = i - delays[d];
                if (j >= 0) r += src[j] * gains[d];
            }
            x[i] = src[i] + r * wet;
        }
    }

    private static double lpAlpha(double hz) {
        double rc = 1.0 / (2.0 * Math.PI * hz);
        double dt = 1.0 / SR;
        return dt / (rc + dt);
    }

    private static void softLimit(double[] x) {
        for (int i = 0; i < x.length; i++) {
            double v = x[i] * 1.10;
            x[i] = Math.tanh(v) / Math.tanh(1.10);
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

    private static double dbToAmp(double db) { return Math.pow(10.0, db / 20.0); }
    private static double ampToDb(double amp) { return 20.0 * Math.log10(Math.max(1e-9, amp)); }

    private static short clamp(double v) {
        if (v > 32767.0) return 32767;
        if (v < -32768.0) return -32768;
        return (short)Math.round(v);
    }
}

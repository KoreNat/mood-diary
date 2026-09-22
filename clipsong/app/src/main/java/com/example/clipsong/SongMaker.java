package com.example.clipsong;

import java.io.File;
import java.io.IOException;
import java.util.*;

final class SongMaker {
    static final class Options {
        final int cleanup;
        final int brightness;
        final boolean drums;
        final boolean bass;
        final boolean pad;
        final int bpm;

        Options(int cleanup, int brightness, boolean drums, boolean bass, boolean pad, int bpm) {
            this.cleanup = cleanup;
            this.brightness = brightness;
            this.drums = drums;
            this.bass = bass;
            this.pad = pad;
            this.bpm = Math.max(60, Math.min(180, bpm));
        }
    }

    private static final int SR = WavIO.SAMPLE_RATE;
    private static final int GAP = (int)(0.04 * SR);
    private static final int CROSS = (int)(0.07 * SR);
    private static final int MAX_CLIP_SECONDS = 25;

    private SongMaker() {}

    static File enhanceClip(File input, File output, Options options) throws IOException {
        short[] x = WavIO.readPcm16Mono(input);
        x = trimSilence(x);
        x = limitLength(x, MAX_CLIP_SECONDS * SR);
        x = AudioProcessor.enhance(x, new AudioProcessor.Settings(options.cleanup, options.brightness));
        WavIO.writePcm16Mono(output, x);
        return output;
    }

    static File makeSong(List<File> input, File output, Options options) throws IOException {
        if (input.isEmpty()) throw new IOException("Сначала запишите хотя бы один фрагмент");

        List<short[]> clips = new ArrayList<>();
        for (File f : input) {
            short[] x = WavIO.readPcm16Mono(f);
            x = trimSilence(x);
            x = limitLength(x, MAX_CLIP_SECONDS * SR);
            x = AudioProcessor.enhance(x, new AudioProcessor.Settings(options.cleanup, options.brightness));
            if (x.length > SR / 20) clips.add(x);
        }
        if (clips.isEmpty()) throw new IOException("В записях не найден слышимый звук");

        List<short[]> sequence = new ArrayList<>();
        sequence.add(clips.get(0));
        if (clips.size() > 1) sequence.add(clips.get(1));
        sequence.addAll(clips);
        for (int i = clips.size() - 1; i >= 0; i--) sequence.add(clips.get(i));

        short[] body = crossfadeSequence(sequence);
        short[] finale = layeredFinale(clips);
        short[] song = concatWithCrossfade(body, finale, (int)(0.30 * SR));

        if (options.drums || options.bass || options.pad) {
            song = mixAccompaniment(song, options);
        }

        song = fadeEdges(song, (int)(0.25 * SR));
        song = master(song);
        WavIO.writePcm16Mono(output, song);
        return output;
    }

    private static short[] trimSilence(short[] x) {
        if (x.length == 0) return x;
        int peak = 0;
        for (short s : x) peak = Math.max(peak, Math.abs((int)s));
        int threshold = Math.max(380, (int)(peak * 0.032));
        int start = 0, end = x.length - 1;
        while (start < x.length && Math.abs((int)x[start]) < threshold) start++;
        while (end > start && Math.abs((int)x[end]) < threshold) end--;
        int pad = (int)(0.06 * SR);
        start = Math.max(0, start - pad);
        end = Math.min(x.length - 1, end + pad);
        return Arrays.copyOfRange(x, start, end + 1);
    }

    private static short[] limitLength(short[] x, int max) {
        return x.length <= max ? x : Arrays.copyOf(x, max);
    }

    private static short[] crossfadeSequence(List<short[]> seq) {
        short[] out = new short[0];
        for (short[] clip : seq) {
            if (out.length == 0) {
                out = clip;
            } else {
                out = concat(out, new short[GAP]);
                out = concatWithCrossfade(out, clip, CROSS);
            }
        }
        return out;
    }

    private static short[] layeredFinale(List<short[]> clips) {
        int count = Math.min(3, clips.size());
        int len = 0;
        for (int i = 0; i < count; i++) len = Math.max(len, clips.get(i).length);
        len = Math.max(len, SR * 2);

        double[] mix = new double[len];
        for (int i = 0; i < count; i++) {
            short[] clip = clips.get(i);
            double gain = 0.62 / Math.sqrt(count);
            int offset = (int)(i * 0.14 * SR);
            for (int p = 0; p + offset < len; p++) {
                short s = clip[p % clip.length];
                mix[p + offset] += s * gain;
            }
        }
        return doublesToShorts(mix);
    }

    private static short[] mixAccompaniment(short[] voice, Options options) {
        double[] mix = new double[voice.length];
        for (int i = 0; i < voice.length; i++) mix[i] = voice[i] * 0.88;

        double beat = SR * 60.0 / options.bpm;
        int beats = (int)Math.ceil(voice.length / beat);
        Random random = new Random(260922L);

        if (options.drums) {
            for (int b = 0; b < beats; b++) {
                int at = (int)Math.round(b * beat);
                int beatInBar = b % 4;
                if (beatInBar == 0 || beatInBar == 2) addKick(mix, at, 0.25);
                if (beatInBar == 1 || beatInBar == 3) addSnare(mix, at, 0.16, random);

                int half = (int)Math.round(at + beat / 2.0);
                addHat(mix, at, 0.045, random);
                addHat(mix, half, 0.032, random);
            }
        }

        if (options.bass) {
            int[] roots = {36, 33, 29, 31}; // C2, A1, F1, G1
            for (int b = 0; b < beats; b++) {
                int at = (int)Math.round(b * beat);
                int bar = (b / 4) % roots.length;
                int midi = roots[bar] + ((b % 4 == 3) ? 7 : 0);
                addBass(mix, at, beat * 0.86, midiToHz(midi), 0.11);
            }
        }

        if (options.pad) {
            int[][] chords = {
                    {48, 52, 55}, // C
                    {45, 48, 52}, // Am
                    {41, 45, 48}, // F
                    {43, 47, 50}  // G
            };
            double barLength = beat * 4.0;
            int bars = (int)Math.ceil(voice.length / barLength);
            for (int bar = 0; bar < bars; bar++) {
                int at = (int)Math.round(bar * barLength);
                int[] chord = chords[bar % chords.length];
                for (int midi : chord) {
                    addPadNote(mix, at, barLength * 0.95, midiToHz(midi), 0.025);
                }
            }
        }

        return doublesToShorts(mix);
    }

    private static void addKick(double[] mix, int at, double level) {
        int n = Math.min((int)(0.22 * SR), mix.length - at);
        if (n <= 0) return;
        double phase = 0.0;
        for (int i = 0; i < n; i++) {
            double t = i / (double)SR;
            double freq = 105.0 * Math.exp(-t * 16.0) + 42.0;
            phase += 2.0 * Math.PI * freq / SR;
            double env = Math.exp(-t * 18.0);
            mix[at + i] += Math.sin(phase) * env * level * 32767.0;
        }
    }

    private static void addSnare(double[] mix, int at, double level, Random random) {
        int n = Math.min((int)(0.16 * SR), mix.length - at);
        if (n <= 0) return;
        for (int i = 0; i < n; i++) {
            double t = i / (double)SR;
            double env = Math.exp(-t * 24.0);
            double noise = random.nextDouble() * 2.0 - 1.0;
            double tone = Math.sin(2.0 * Math.PI * 180.0 * t);
            mix[at + i] += (noise * 0.82 + tone * 0.18) * env * level * 32767.0;
        }
    }

    private static void addHat(double[] mix, int at, double level, Random random) {
        int n = Math.min((int)(0.055 * SR), mix.length - at);
        if (n <= 0) return;
        double prev = 0.0;
        for (int i = 0; i < n; i++) {
            double t = i / (double)SR;
            double noise = random.nextDouble() * 2.0 - 1.0;
            double hp = noise - prev * 0.86;
            prev = noise;
            double env = Math.exp(-t * 75.0);
            mix[at + i] += hp * env * level * 32767.0;
        }
    }

    private static void addBass(double[] mix, int at, double lengthSamples, double hz, double level) {
        int n = Math.min((int)lengthSamples, mix.length - at);
        if (n <= 0) return;
        double phase = 0.0;
        for (int i = 0; i < n; i++) {
            double t = i / (double)SR;
            double life = i / (double)Math.max(1, n);
            double attack = Math.min(1.0, t / 0.018);
            double release = Math.min(1.0, (1.0 - life) / 0.12);
            double env = attack * Math.max(0.0, release);
            phase += 2.0 * Math.PI * hz / SR;
            double s = Math.sin(phase) + 0.22 * Math.sin(phase * 2.0);
            mix[at + i] += s * env * level * 32767.0;
        }
    }

    private static void addPadNote(double[] mix, int at, double lengthSamples, double hz, double level) {
        int n = Math.min((int)lengthSamples, mix.length - at);
        if (n <= 0) return;
        for (int i = 0; i < n; i++) {
            double t = i / (double)SR;
            double life = i / (double)Math.max(1, n);
            double attack = Math.min(1.0, t / 0.35);
            double release = Math.min(1.0, (1.0 - life) / 0.20);
            double env = Math.min(attack, Math.max(0.0, release));
            double s = Math.sin(2.0 * Math.PI * hz * t)
                    + 0.28 * Math.sin(2.0 * Math.PI * hz * 2.0 * t);
            mix[at + i] += s * env * level * 32767.0;
        }
    }

    private static double midiToHz(int midi) {
        return 440.0 * Math.pow(2.0, (midi - 69) / 12.0);
    }

    private static short[] master(short[] x) {
        double[] y = new double[x.length];
        double peak = 1.0;
        for (int i = 0; i < x.length; i++) {
            double v = Math.tanh((x[i] / 32768.0) * 1.12) / Math.tanh(1.12);
            y[i] = v;
            peak = Math.max(peak, Math.abs(v));
        }
        short[] out = new short[x.length];
        double gain = 0.95 / peak;
        for (int i = 0; i < out.length; i++) out[i] = clamp(y[i] * gain * 32767.0);
        return out;
    }

    private static short[] doublesToShorts(double[] x) {
        double peak = 1.0;
        for (double v : x) peak = Math.max(peak, Math.abs(v));
        double gain = peak > 31000.0 ? 31000.0 / peak : 1.0;
        short[] out = new short[x.length];
        for (int i = 0; i < x.length; i++) out[i] = clamp(x[i] * gain);
        return out;
    }

    private static short[] concat(short[] a, short[] b) {
        short[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static short[] concatWithCrossfade(short[] a, short[] b, int cross) {
        int c = Math.min(cross, Math.min(a.length, b.length));
        short[] out = new short[a.length + b.length - c];
        System.arraycopy(a, 0, out, 0, a.length - c);
        for (int i = 0; i < c; i++) {
            double t = (i + 1.0) / (c + 1.0);
            double v = a[a.length - c + i] * (1.0 - t) + b[i] * t;
            out[a.length - c + i] = clamp(v);
        }
        System.arraycopy(b, c, out, a.length, b.length - c);
        return out;
    }

    private static short[] fadeEdges(short[] x, int n) {
        short[] y = Arrays.copyOf(x, x.length);
        int m = Math.min(n, x.length / 2);
        for (int i = 0; i < m; i++) {
            double t = i / (double)Math.max(1, m);
            y[i] = clamp(y[i] * t);
            y[y.length - 1 - i] = clamp(y[y.length - 1 - i] * t);
        }
        return y;
    }

    private static short clamp(double v) {
        if (v > 32767.0) return 32767;
        if (v < -32768.0) return -32768;
        return (short)Math.round(v);
    }
}

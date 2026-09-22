package com.example.clipsong;

import java.io.File;
import java.io.IOException;
import java.util.*;

final class SongMaker {
    private static final int SR = WavIO.SAMPLE_RATE;
    private static final int GAP = (int)(0.06 * SR);
    private static final int CROSS = (int)(0.08 * SR);
    private static final int MAX_CLIP_SECONDS = 20;

    private SongMaker() {}

    static File makeSong(List<File> input, File output) throws IOException {
        if (input.isEmpty()) throw new IOException("Сначала запишите хотя бы один фрагмент");
        List<short[]> clips = new ArrayList<>();
        for (File f : input) {
            short[] x = WavIO.readPcm16Mono(f);
            x = trimSilence(x);
            x = limitLength(x, MAX_CLIP_SECONDS * SR);
            x = normalize(x, 0.82);
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
        short[] song = concatWithCrossfade(body, finale, (int)(0.35 * SR));
        song = fadeEdges(song, (int)(0.35 * SR));
        song = normalize(song, 0.92);
        WavIO.writePcm16Mono(output, song);
        return output;
    }

    private static short[] trimSilence(short[] x) {
        if (x.length == 0) return x;
        int peak = 0;
        for (short s : x) peak = Math.max(peak, Math.abs((int)s));
        int threshold = Math.max(450, (int)(peak * 0.045));
        int start = 0, end = x.length - 1;
        while (start < x.length && Math.abs((int)x[start]) < threshold) start++;
        while (end > start && Math.abs((int)x[end]) < threshold) end--;
        int pad = (int)(0.04 * SR);
        start = Math.max(0, start - pad);
        end = Math.min(x.length - 1, end + pad);
        return Arrays.copyOfRange(x, start, end + 1);
    }

    private static short[] limitLength(short[] x, int max) {
        if (x.length <= max) return x;
        return Arrays.copyOf(x, max);
    }

    private static short[] normalize(short[] x, double target) {
        int peak = 1;
        for (short s : x) peak = Math.max(peak, Math.abs((int)s));
        double gain = Math.min(6.0, target * 32767.0 / peak);
        short[] y = new short[x.length];
        for (int i = 0; i < x.length; i++) y[i] = clamp(x[i] * gain);
        return y;
    }

    private static short[] crossfadeSequence(List<short[]> seq) {
        short[] out = new short[0];
        for (short[] clip : seq) {
            if (out.length == 0) out = clip;
            else {
                short[] gap = new short[GAP];
                out = concat(out, gap);
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
            double gain = 0.72 / Math.sqrt(count);
            int offset = (int)(i * 0.18 * SR);
            for (int p = 0; p + offset < len; p++) {
                short s = clip[p % clip.length];
                mix[p + offset] += s * gain;
            }
        }
        short[] out = new short[len];
        for (int i = 0; i < len; i++) out[i] = clamp(mix[i]);
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
        if (v > 32767) return 32767;
        if (v < -32768) return -32768;
        return (short)Math.round(v);
    }
}

package com.example.clipsong;

import java.util.*;

final class MelodyAnalyzer {
    static final class Result {
        final boolean found;
        final int rootPc;
        final boolean minor;
        final int[] barPitchClasses;
        final List<Integer> notes;

        Result(boolean found, int rootPc, boolean minor, int[] barPitchClasses, List<Integer> notes) {
            this.found = found;
            this.rootPc = rootPc;
            this.minor = minor;
            this.barPitchClasses = barPitchClasses;
            this.notes = notes;
        }

        String summary() {
            if (!found) return "Мелодия: уверенный основной тон не найден";
            String[] names = {"C","C#","D","D#","E","F","F#","G","G#","A","A#","B"};
            StringBuilder s = new StringBuilder();
            s.append("Мелодия: ").append(names[rootPc]).append(minor ? " minor" : " major");
            if (!notes.isEmpty()) {
                s.append(". Ноты: ");
                int n = Math.min(18, notes.size());
                for (int i = 0; i < n; i++) {
                    if (i > 0) s.append(' ');
                    int midi = notes.get(i);
                    s.append(names[(midi % 12 + 12) % 12]).append((midi / 12) - 1);
                }
                if (notes.size() > n) s.append(" …");
            }
            return s.toString();
        }
    }

    private static final int SR = WavIO.SAMPLE_RATE;
    // Aggressive downsampling keeps pitch detection fast enough for a phone.
    private static final int DS = 8;
    private static final int DSR = SR / DS;
    private static final int FRAME = 512;
    private static final int HOP = 384;

    private MelodyAnalyzer() {}

    static Result analyze(short[] pcm, int bpm) {
        if (pcm == null || pcm.length < SR / 2) {
            return new Result(false, 0, false, new int[0], new ArrayList<>());
        }

        int maxSamples = Math.min(pcm.length, SR * 30);
        int n = maxSamples / DS;
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = pcm[i * DS] / 32768.0;

        int frameCount = Math.max(0, (n - FRAME) / HOP + 1);
        double[] pcHist = new double[12];
        List<Integer> collapsed = new ArrayList<>();

        double secPerBar = 60.0 / Math.max(60, Math.min(180, bpm)) * 4.0;
        int barCount = Math.max(1, (int)Math.ceil((maxSamples / (double)SR) / secPerBar));
        double[][] barHist = new double[barCount][12];

        int previousMidi = -999;
        int stableCount = 0;

        for (int f = 0; f < frameCount; f++) {
            if (Thread.currentThread().isInterrupted()) {
                return new Result(false, 0, false, new int[0], collapsed);
            }
            int start = f * HOP;
            double mean = 0.0;
            double energy = 0.0;
            for (int i = 0; i < FRAME; i++) mean += x[start + i];
            mean /= FRAME;
            for (int i = 0; i < FRAME; i++) {
                double v = x[start + i] - mean;
                energy += v * v;
            }
            double rms = Math.sqrt(energy / FRAME);
            if (rms < 0.014) continue;

            Pitch p = detectPitch(x, start, mean);
            if (p.hz < 75.0 || p.hz > 1000.0 || p.confidence < 0.46) continue;

            int midi = (int)Math.round(69.0 + 12.0 * Math.log(p.hz / 440.0) / Math.log(2.0));
            if (midi < 30 || midi > 96) continue;
            int pc = (midi % 12 + 12) % 12;
            double weight = rms * p.confidence;

            pcHist[pc] += weight;
            double seconds = (start * DS) / (double)SR;
            int bar = Math.min(barCount - 1, (int)(seconds / secPerBar));
            barHist[bar][pc] += weight;

            if (Math.abs(midi - previousMidi) <= 1) {
                stableCount++;
            } else {
                stableCount = 1;
                previousMidi = midi;
            }
            if (stableCount == 3) {
                if (collapsed.isEmpty() || Math.abs(collapsed.get(collapsed.size() - 1) - midi) > 1) {
                    collapsed.add(midi);
                }
            }
        }

        double total = 0.0;
        for (double v : pcHist) total += v;
        if (total < 0.02) return new Result(false, 0, false, new int[0], collapsed);

        int bestRoot = 0;
        boolean bestMinor = false;
        double bestScore = -1.0;
        int[] majorScale = {0,2,4,5,7,9,11};
        int[] minorScale = {0,2,3,5,7,8,10};

        for (int root = 0; root < 12; root++) {
            double maj = scaleScore(pcHist, root, majorScale);
            double min = scaleScore(pcHist, root, minorScale);
            maj += pcHist[root] * 0.55 + pcHist[(root + 7) % 12] * 0.12;
            min += pcHist[root] * 0.55 + pcHist[(root + 7) % 12] * 0.12;
            if (maj > bestScore) { bestScore = maj; bestRoot = root; bestMinor = false; }
            if (min > bestScore) { bestScore = min; bestRoot = root; bestMinor = true; }
        }

        int[] bars = new int[barCount];
        Arrays.fill(bars, -1);
        for (int b = 0; b < barCount; b++) {
            double best = 0.0;
            int pc = -1;
            for (int k = 0; k < 12; k++) {
                if (barHist[b][k] > best) { best = barHist[b][k]; pc = k; }
            }
            bars[b] = pc;
        }

        return new Result(true, bestRoot, bestMinor, bars, collapsed);
    }

    private static double scaleScore(double[] hist, int root, int[] scale) {
        boolean[] allowed = new boolean[12];
        for (int d : scale) allowed[(root + d) % 12] = true;
        double good = 0.0, bad = 0.0;
        for (int pc = 0; pc < 12; pc++) {
            if (allowed[pc]) good += hist[pc];
            else bad += hist[pc];
        }
        return good - bad * 0.35;
    }

    private static Pitch detectPitch(double[] x, int start, double mean) {
        int minLag = Math.max(2, DSR / 1000);
        int maxLag = Math.min(FRAME / 2, DSR / 75);
        double best = -1.0;
        int bestLag = -1;

        for (int lag = minLag; lag <= maxLag; lag++) {
            if (Thread.currentThread().isInterrupted()) return new Pitch(0.0, 0.0);
            double xy = 0.0, xx = 0.0, yy = 0.0;
            int count = FRAME - lag;
            for (int i = 0; i < count; i++) {
                double a = x[start + i] - mean;
                double b = x[start + i + lag] - mean;
                xy += a * b;
                xx += a * a;
                yy += b * b;
            }
            double corr = xy / Math.sqrt(Math.max(1e-12, xx * yy));
            if (corr > best) { best = corr; bestLag = lag; }
        }
        if (bestLag <= 0) return new Pitch(0.0, 0.0);

        double refined = bestLag;
        if (bestLag > minLag && bestLag < maxLag) {
            double y1 = correlation(x, start, mean, bestLag - 1);
            double y2 = correlation(x, start, mean, bestLag);
            double y3 = correlation(x, start, mean, bestLag + 1);
            double denom = (y1 - 2.0 * y2 + y3);
            if (Math.abs(denom) > 1e-8) refined += 0.5 * (y1 - y3) / denom;
        }
        return new Pitch(DSR / refined, best);
    }

    private static double correlation(double[] x, int start, double mean, int lag) {
        double xy = 0.0, xx = 0.0, yy = 0.0;
        int count = FRAME - lag;
        for (int i = 0; i < count; i++) {
            double a = x[start + i] - mean;
            double b = x[start + i + lag] - mean;
            xy += a * b; xx += a * a; yy += b * b;
        }
        return xy / Math.sqrt(Math.max(1e-12, xx * yy));
    }

    private static final class Pitch {
        final double hz;
        final double confidence;
        Pitch(double hz, double confidence) { this.hz = hz; this.confidence = confidence; }
    }
}

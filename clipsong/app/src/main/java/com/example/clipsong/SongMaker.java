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
        final String request;
        final boolean instrumental;

        Options(int cleanup, int brightness, boolean drums, boolean bass, boolean pad, int bpm, String request, boolean instrumental) {
            this.cleanup = cleanup;
            this.brightness = brightness;
            this.drums = drums;
            this.bass = bass;
            this.pad = pad;
            this.bpm = Math.max(60, Math.min(180, bpm));
            this.request = request == null ? "" : request;
            this.instrumental = instrumental;
        }
    }

    private static final int SR = WavIO.SAMPLE_RATE;
    private static final int GAP = (int)(0.04 * SR);
    private static final int CROSS = (int)(0.07 * SR);
    private static final int MAX_CLIP_SECONDS = 25;

    private SongMaker() {}

    static StyleInterpreter.Plan interpret(Options options) {
        return StyleInterpreter.parse(
                options.request,
                options.cleanup,
                options.brightness,
                options.bpm,
                options.drums,
                options.bass,
                options.pad,
                options.instrumental
        );
    }

    static File enhanceClip(File input, File output, Options options) throws IOException {
        StyleInterpreter.Plan plan = interpret(options);
        short[] x = WavIO.readPcm16Mono(input);
        x = trimSilence(x);
        x = limitLength(x, MAX_CLIP_SECONDS * SR);
        x = AudioProcessor.enhance(x, new AudioProcessor.Settings(plan.cleanup, plan.brightness, plan.timbre, plan.reverb));
        WavIO.writePcm16Mono(output, x);
        return output;
    }

    static File makeSong(List<File> input, File output, Options options) throws IOException {
        if (input.isEmpty()) throw new IOException("Сначала запишите хотя бы один фрагмент");

        StyleInterpreter.Plan plan = interpret(options);
        List<short[]> clips = new ArrayList<>();
        for (File f : input) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Операция остановлена");
            short[] x = WavIO.readPcm16Mono(f);
            x = trimSilence(x);
            x = limitLength(x, MAX_CLIP_SECONDS * SR);
            x = AudioProcessor.enhance(x, new AudioProcessor.Settings(plan.cleanup, plan.brightness, plan.timbre, plan.reverb));
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

        MelodyAnalyzer.Result melody = MelodyAnalyzer.analyze(song, plan.bpm);
        if (plan.instrumental && (melody == null || !melody.found || melody.notes.isEmpty())) {
            throw new IOException("Не удалось уверенно распознать мелодию, поэтому убрать голос пока нельзя");
        }
        if (plan.drums || plan.bass || plan.pad || plan.piano || plan.guitar || plan.instrumental) {
            song = mixAccompaniment(song, plan, melody);
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

    private static short[] mixAccompaniment(short[] voice, StyleInterpreter.Plan plan, MelodyAnalyzer.Result melody) {
        double[] mix = new double[voice.length];
        double voiceGain = plan.instrumental ? 0.0 : (plan.energy > 1.1 ? 0.79 : 0.86);
        for (int i = 0; i < voice.length; i++) mix[i] = voice[i] * voiceGain;

        double beat = SR * 60.0 / plan.bpm;
        int beats = (int)Math.ceil(voice.length / beat);
        Random random = new Random(260922L);

        int[][] chords = chordProgression(plan.style, melody);
        if (plan.instrumental && melody != null && melody.found && !melody.notes.isEmpty()) {
            addLeadMelody(mix, melody.notes, 0.18 * plan.energy);
        }
        int[] roots = new int[chords.length];
        for (int i = 0; i < chords.length; i++) roots[i] = chords[i][0] - 12;

        if (plan.drums) {
            for (int b = 0; b < beats; b++) {
                if (Thread.currentThread().isInterrupted()) return doublesToShorts(mix);
                int at = beatPosition(b, beat, plan.swing);
                int beatInBar = b % 4;
                double kickLevel = 0.22 * plan.energy;
                double snareLevel = 0.14 * plan.energy;
                double hatLevel = 0.036 * plan.energy;

                if ("рок".equals(plan.style)) {
                    kickLevel *= 1.35; snareLevel *= 1.35; hatLevel *= 1.2;
                } else if ("джаз".equals(plan.style)) {
                    kickLevel *= 0.55; snareLevel *= 0.55; hatLevel *= 0.7;
                } else if ("кантри".equals(plan.style)) {
                    kickLevel *= 0.85; snareLevel *= 0.8; hatLevel *= 0.8;
                }

                if (beatInBar == 0 || beatInBar == 2) addKick(mix, at, kickLevel);
                if (beatInBar == 1 || beatInBar == 3) addSnare(mix, at, snareLevel, random);

                int half = at + (int)Math.round(beat / 2.0);
                addHat(mix, at, hatLevel, random);
                addHat(mix, half, hatLevel * 0.72, random);
            }
        }

        if (plan.bass) {
            for (int b = 0; b < beats; b++) {
                int at = beatPosition(b, beat, plan.swing);
                int bar = (b / 4) % roots.length;
                int root = roots[bar];
                int midi;
                if ("кантри".equals(plan.style)) {
                    midi = root + ((b % 2 == 0) ? 0 : 7);
                } else if ("джаз".equals(plan.style)) {
                    int[] walk = {0, 4, 7, 9};
                    midi = root + walk[b % 4];
                } else {
                    midi = root + ((b % 4 == 3) ? 7 : 0);
                }
                addBass(mix, at, beat * 0.84, midiToHz(midi), 0.095 * plan.energy);
            }
        }

        double barLength = beat * 4.0;
        int bars = (int)Math.ceil(voice.length / barLength);

        if (plan.pad) {
            for (int bar = 0; bar < bars; bar++) {
                if (Thread.currentThread().isInterrupted()) return doublesToShorts(mix);
                int at = (int)Math.round(bar * barLength);
                int[] chord = chords[bar % chords.length];
                for (int midi : chord) {
                    addPadNote(mix, at, barLength * 0.96, midiToHz(midi), 0.020 * plan.energy);
                }
            }
        }

        if (plan.piano) {
            for (int b = 0; b < beats; b++) {
                int at = beatPosition(b, beat, plan.swing);
                int bar = (b / 4) % chords.length;
                int[] chord = chords[bar];
                double level = 0.050 * plan.energy;
                if ("баллада".equals(plan.style)) level *= 1.15;
                if ("джаз".equals(plan.style)) level *= 1.10;

                if ("джаз".equals(plan.style) && b % 2 == 1) {
                    for (int midi : chord) addPianoNote(mix, at, beat * 0.90, midiToHz(midi), level);
                } else if (b % 2 == 0 || "баллада".equals(plan.style)) {
                    int index = b % chord.length;
                    addPianoNote(mix, at, beat * 0.86, midiToHz(chord[index]), level);
                    if ("баллада".equals(plan.style)) {
                        addPianoNote(mix, at, beat * 1.65, midiToHz(chord[(index + 1) % chord.length] + 12), level * 0.55);
                    }
                }
            }
        }

        if (plan.guitar) {
            for (int b = 0; b < beats; b++) {
                int at = beatPosition(b, beat, plan.swing);
                int bar = (b / 4) % chords.length;
                int[] chord = chords[bar];
                double level = 0.032 * plan.energy;
                if ("рок".equals(plan.style)) level *= 1.45;
                if ("кантри".equals(plan.style)) level *= 1.20;

                if ("кантри".equals(plan.style)) {
                    addGuitarStrum(mix, at, beat * 0.68, chord, level, random, b % 2 == 0);
                    int off = at + (int)(beat * 0.5);
                    addGuitarStrum(mix, off, beat * 0.40, chord, level * 0.72, random, false);
                } else if ("рок".equals(plan.style)) {
                    addGuitarStrum(mix, at, beat * 0.72, chord, level, random, true);
                } else {
                    if (b % 2 == 0) addGuitarStrum(mix, at, beat * 0.80, chord, level, random, true);
                }
            }
        }

        return doublesToShorts(mix);
    }

    private static int[][] chordProgression(String style, MelodyAnalyzer.Result melody) {
        if (melody != null && melody.found) {
            int root = 48 + melody.rootPc;
            while (root > 59) root -= 12;
            if (melody.minor) {
                return new int[][]{
                        triad(root, true),
                        triad(root + 8, false),
                        triad(root + 3, false),
                        triad(root + 10, false)
                };
            } else {
                return new int[][]{
                        triad(root, false),
                        triad(root + 5, false),
                        triad(root + 7, false),
                        triad(root, false)
                };
            }
        }

        if ("кантри".equals(style)) {
            return new int[][]{
                    {48, 52, 55}, // C
                    {53, 57, 60}, // F
                    {55, 59, 62}, // G
                    {48, 52, 55}  // C
            };
        }
        if ("джаз".equals(style)) {
            return new int[][]{
                    {50, 53, 57, 60}, // Dm7
                    {55, 59, 62, 65}, // G7-ish
                    {48, 52, 55, 59}, // Cmaj7
                    {45, 48, 52, 55}  // Am7
            };
        }
        if ("рок".equals(style)) {
            return new int[][]{
                    {48, 55, 60},
                    {46, 53, 58},
                    {53, 60, 65},
                    {55, 62, 67}
            };
        }
        return new int[][]{
                {48, 52, 55}, // C
                {45, 48, 52}, // Am
                {41, 45, 48}, // F
                {43, 47, 50}  // G
        };
    }

    private static int[] triad(int root, boolean minor) {
        return new int[]{root, root + (minor ? 3 : 4), root + 7};
    }

    static File stitchTwo(File first, File second, File output, Options options) throws IOException {
        if (first == null || second == null) throw new IOException("Выберите два фрагмента");
        StyleInterpreter.Plan plan = interpret(options);
        short[] a = trimSilence(WavIO.readPcm16Mono(first));
        short[] b = trimSilence(WavIO.readPcm16Mono(second));
        a = AudioProcessor.enhance(a, new AudioProcessor.Settings(plan.cleanup, plan.brightness, plan.timbre, plan.reverb));
        b = AudioProcessor.enhance(b, new AudioProcessor.Settings(plan.cleanup, plan.brightness, plan.timbre, plan.reverb));
        short[] joined = concatWithCrossfade(a, b, (int)(0.12 * SR));
        joined = fadeEdges(master(joined), (int)(0.02 * SR));
        WavIO.writePcm16Mono(output, joined);
        return output;
    }

    static MelodyAnalyzer.Result analyzeMelody(File input, Options options) throws IOException {
        short[] x = WavIO.readPcm16Mono(input);
        StyleInterpreter.Plan plan = interpret(options);
        x = AudioProcessor.enhance(trimSilence(x), new AudioProcessor.Settings(plan.cleanup, plan.brightness, plan.timbre, 0));
        return MelodyAnalyzer.analyze(x, plan.bpm);
    }

    private static int beatPosition(int beatIndex, double beat, boolean swing) {
        double p = beatIndex * beat;
        if (swing && (beatIndex % 2 == 1)) p += beat * 0.10;
        return (int)Math.round(p);
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

    private static void addPianoNote(double[] mix, int at, double lengthSamples, double hz, double level) {
        int n = Math.min((int)lengthSamples, mix.length - at);
        if (n <= 0) return;
        for (int i = 0; i < n; i++) {
            double t = i / (double)SR;
            double attack = Math.min(1.0, t / 0.006);
            double decay = Math.exp(-t * 3.1);
            double hammer = Math.exp(-t * 30.0);
            double s =
                    Math.sin(2.0 * Math.PI * hz * t)
                    + 0.46 * Math.sin(2.0 * Math.PI * hz * 2.01 * t)
                    + 0.20 * Math.sin(2.0 * Math.PI * hz * 3.99 * t)
                    + 0.08 * Math.sin(2.0 * Math.PI * hz * 7.1 * t) * hammer;
            mix[at + i] += s * attack * decay * level * 32767.0;
        }
    }

    private static void addGuitarStrum(double[] mix, int at, double lengthSamples, int[] chord,
                                       double level, Random random, boolean down) {
        int stringDelay = (int)(0.012 * SR);
        for (int s = 0; s < chord.length; s++) {
            int idx = down ? s : (chord.length - 1 - s);
            int noteAt = at + s * stringDelay;
            double hz = midiToHz(chord[idx] + 12);
            addPluckedString(mix, noteAt, lengthSamples, hz, level / Math.sqrt(chord.length), random);
        }
    }

    private static void addPluckedString(double[] mix, int at, double lengthSamples, double hz,
                                         double level, Random random) {
        int n = Math.min((int)lengthSamples, mix.length - at);
        if (n <= 0) return;
        int period = Math.max(2, (int)Math.round(SR / hz));
        double[] ring = new double[period];
        for (int i = 0; i < period; i++) ring[i] = random.nextDouble() * 2.0 - 1.0;
        int pos = 0;
        for (int i = 0; i < n; i++) {
            double current = ring[pos];
            double next = ring[(pos + 1) % period];
            ring[pos] = 0.493 * (current + next);
            pos = (pos + 1) % period;
            double t = i / (double)SR;
            double env = Math.exp(-t * 2.8);
            mix[at + i] += current * env * level * 32767.0;
        }
    }

    private static void addLeadMelody(double[] mix, List<Integer> notes, double level) {
        if (notes == null || notes.isEmpty() || mix.length == 0) return;
        double segment = mix.length / (double)notes.size();
        for (int n = 0; n < notes.size(); n++) {
            if (Thread.currentThread().isInterrupted()) return;
            int at = (int)Math.round(n * segment);
            int len = Math.max((int)(0.16 * SR), (int)Math.round(segment * 0.92));
            len = Math.min(len, mix.length - at);
            if (len <= 0) continue;
            addLeadNote(mix, at, len, midiToHz(notes.get(n)), level);
        }
    }

    private static void addLeadNote(double[] mix, int at, int n, double hz, double level) {
        for (int i = 0; i < n; i++) {
            double t = i / (double)SR;
            double life = i / (double)Math.max(1, n - 1);
            double attack = Math.min(1.0, t / 0.025);
            double release = Math.min(1.0, (1.0 - life) / 0.10);
            double env = attack * Math.max(0.0, release);
            double phase = 2.0 * Math.PI * hz * t;
            double s = Math.sin(phase)
                    + 0.28 * Math.sin(phase * 2.0)
                    + 0.10 * Math.sin(phase * 3.0);
            mix[at + i] += s * env * level * 32767.0;
        }
    }

    private static double midiToHz(int midi) {
        return 440.0 * Math.pow(2.0, (midi - 69) / 12.0);
    }

    private static short[] master(short[] x) {
        double[] y = new double[x.length];
        double peak = 1e-9;
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

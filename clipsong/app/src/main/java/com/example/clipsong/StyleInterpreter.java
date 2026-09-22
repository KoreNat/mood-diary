package com.example.clipsong;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class StyleInterpreter {
    static final class Plan {
        final String style;
        final int bpm;
        final int cleanup;
        final int brightness;
        final boolean drums;
        final boolean bass;
        final boolean pad;
        final boolean piano;
        final boolean guitar;
        final boolean swing;
        final double energy;

        Plan(String style, int bpm, int cleanup, int brightness,
             boolean drums, boolean bass, boolean pad,
             boolean piano, boolean guitar, boolean swing, double energy) {
            this.style = style;
            this.bpm = bpm;
            this.cleanup = cleanup;
            this.brightness = brightness;
            this.drums = drums;
            this.bass = bass;
            this.pad = pad;
            this.piano = piano;
            this.guitar = guitar;
            this.swing = swing;
            this.energy = energy;
        }

        String summary() {
            StringBuilder s = new StringBuilder();
            s.append("Стиль: ").append(style).append(", ").append(bpm).append(" BPM");
            if (piano) s.append(", пианино");
            if (guitar) s.append(", гитара");
            if (bass) s.append(", бас");
            if (drums) s.append(", ударные");
            if (pad) s.append(", фон");
            s.append(". Голос: очистка ").append(cleanup).append(", яркость ").append(brightness);
            return s.toString();
        }
    }

    private StyleInterpreter() {}

    static Plan parse(String request,
                      int baseCleanup, int baseBrightness, int baseBpm,
                      boolean baseDrums, boolean baseBass, boolean basePad) {
        String q = request == null ? "" : request.toLowerCase(Locale.ROOT).trim();

        String style = "нейтральный";
        int bpm = baseBpm;
        int cleanup = baseCleanup;
        int brightness = baseBrightness;
        boolean drums = baseDrums;
        boolean bass = baseBass;
        boolean pad = basePad;
        boolean piano = false;
        boolean guitar = false;
        boolean swing = false;
        double energy = 1.0;

        if (containsAny(q, "кантри", "country", "americana", "американа")) {
            style = "кантри";
            if (!hasExplicitTempo(q)) bpm = 108;
            drums = true;
            bass = true;
            guitar = true;
            pad = false;
            energy = 0.90;
        } else if (containsAny(q, "рок", "rock")) {
            style = "рок";
            if (!hasExplicitTempo(q)) bpm = 124;
            drums = true;
            bass = true;
            guitar = true;
            pad = false;
            energy = 1.20;
        } else if (containsAny(q, "поп", "pop")) {
            style = "поп";
            if (!hasExplicitTempo(q)) bpm = 116;
            drums = true;
            bass = true;
            pad = true;
            energy = 1.00;
        } else if (containsAny(q, "джаз", "jazz", "swing", "свинг")) {
            style = "джаз";
            if (!hasExplicitTempo(q)) bpm = 104;
            drums = true;
            bass = true;
            piano = true;
            pad = false;
            swing = true;
            energy = 0.80;
        } else if (containsAny(q, "эмбиент", "ambient", "атмосфер", "воздушн")) {
            style = "эмбиент";
            if (!hasExplicitTempo(q)) bpm = 78;
            drums = false;
            bass = false;
            pad = true;
            energy = 0.55;
        } else if (containsAny(q, "баллад", "ballad", "лиричес", "нежн")) {
            style = "баллада";
            if (!hasExplicitTempo(q)) bpm = 76;
            drums = false;
            bass = true;
            piano = true;
            pad = true;
            energy = 0.58;
        } else if (containsAny(q, "акуст", "acoustic")) {
            style = "акустический";
            if (!hasExplicitTempo(q)) bpm = 96;
            drums = false;
            bass = true;
            guitar = true;
            pad = false;
            energy = 0.70;
        }

        int explicitBpm = extractBpm(q);
        if (explicitBpm > 0) bpm = explicitBpm;
        if (containsAny(q, "медлен", "slower", "slow")) bpm = Math.max(60, bpm - 18);
        if (containsAny(q, "быстр", "faster", "fast", "энергич")) bpm = Math.min(180, bpm + 18);

        if (requested(q, "пианино", "piano", "рояль", "фортепиано", "keys", "клавиш")) piano = true;
        if (requested(q, "гитар", "guitar", "акустическ")) guitar = true;
        if (requested(q, "бас", "bass")) bass = true;
        if (requested(q, "ударн", "drum", "бит", "beat")) drums = true;
        if (requested(q, "фон", "pad", "атмосфер", "струнн")) pad = true;

        if (forbidden(q, "пианино", "piano", "рояль", "фортепиано")) piano = false;
        if (forbidden(q, "гитар", "guitar")) guitar = false;
        if (forbidden(q, "бас", "bass")) bass = false;
        if (forbidden(q, "ударн", "drum", "бит", "beat")) drums = false;
        if (forbidden(q, "фон", "pad", "струнн")) pad = false;

        if (containsAny(q, "ярче", "звонче", "bright", "crisp", "воздушнее")) brightness = Math.min(100, brightness + 24);
        if (containsAny(q, "мягче", "теплее", "soft", "warm")) brightness = Math.max(0, brightness - 20);
        if (containsAny(q, "сильнее убрать шум", "максимально убрать шум", "чище голос", "cleaner", "denoise")) cleanup = Math.min(100, cleanup + 24);
        if (containsAny(q, "меньше обработки", "естественнее", "natural", "натуральнее")) {
            cleanup = Math.max(0, cleanup - 18);
            brightness = Math.max(0, brightness - 10);
        }

        if (containsAny(q, "тихо", "спокойн", "minimal", "минимал", "нежн")) energy *= 0.72;
        if (containsAny(q, "мощн", "жирн", "сильно", "big", "powerful")) energy *= 1.25;

        bpm = clamp(bpm, 60, 180);
        cleanup = clamp(cleanup, 0, 100);
        brightness = clamp(brightness, 0, 100);
        energy = Math.max(0.35, Math.min(1.45, energy));

        return new Plan(style, bpm, cleanup, brightness, drums, bass, pad, piano, guitar, swing, energy);
    }

    private static boolean containsAny(String s, String... words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    private static boolean requested(String q, String... nouns) {
        if (q.isEmpty()) return false;
        if (forbidden(q, nouns)) return false;
        for (String n : nouns) {
            if (q.contains(n)) return true;
        }
        return false;
    }

    private static boolean forbidden(String q, String... nouns) {
        String[] negations = {"без ", "убери ", "убрать ", "не добавляй ", "не надо ", "no ", "without ", "remove "};
        for (String n : nouns) {
            for (String neg : negations) {
                int p = q.indexOf(neg);
                while (p >= 0) {
                    int end = Math.min(q.length(), p + neg.length() + 40);
                    if (q.substring(p, end).contains(n)) return true;
                    p = q.indexOf(neg, p + 1);
                }
            }
        }
        return false;
    }

    private static boolean hasExplicitTempo(String q) {
        return extractBpm(q) > 0;
    }

    private static int extractBpm(String q) {
        Matcher m = Pattern.compile("(?<!\\d)(6\\d|7\\d|8\\d|9\\d|1[0-7]\\d|180)\\s*(?:bpm|бпм)?").matcher(q);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); }
            catch (Exception ignored) {}
        }
        return -1;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

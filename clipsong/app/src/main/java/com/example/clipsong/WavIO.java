package com.example.clipsong;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class WavIO {
    static final int SAMPLE_RATE = 44100;
    static final int CHANNELS = 1;
    static final int BITS_PER_SAMPLE = 16;

    private WavIO() {}

    static void writeHeader(RandomAccessFile f, long dataBytes) throws IOException {
        int byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8;
        int blockAlign = CHANNELS * BITS_PER_SAMPLE / 8;
        f.seek(0);
        f.writeBytes("RIFF");
        writeLEInt(f, (int) (36 + dataBytes));
        f.writeBytes("WAVE");
        f.writeBytes("fmt ");
        writeLEInt(f, 16);
        writeLEShort(f, (short) 1);
        writeLEShort(f, (short) CHANNELS);
        writeLEInt(f, SAMPLE_RATE);
        writeLEInt(f, byteRate);
        writeLEShort(f, (short) blockAlign);
        writeLEShort(f, (short) BITS_PER_SAMPLE);
        f.writeBytes("data");
        writeLEInt(f, (int) dataBytes);
    }

    static short[] readPcm16Mono(File file) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            if (f.length() < 44) throw new IOException("Слишком короткий WAV");
            byte[] head = new byte[44];
            f.readFully(head);
            String riff = new String(head, 0, 4, "US-ASCII");
            String wave = new String(head, 8, 4, "US-ASCII");
            if (!"RIFF".equals(riff) || !"WAVE".equals(wave)) throw new IOException("Неверный WAV");
            int dataSize = ByteBuffer.wrap(head, 40, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            dataSize = Math.min(dataSize, (int)Math.max(0, f.length() - 44));
            byte[] bytes = new byte[dataSize];
            f.readFully(bytes);
            short[] pcm = new short[dataSize / 2];
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
            return pcm;
        }
    }

    static void writePcm16Mono(File file, short[] pcm) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file, "rw")) {
            f.setLength(0);
            writeHeader(f, (long) pcm.length * 2L);
            ByteBuffer bb = ByteBuffer.allocate(pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            bb.asShortBuffer().put(pcm);
            f.write(bb.array());
        }
    }

    static long durationMs(File file) {
        long bytes = Math.max(0, file.length() - 44);
        return (bytes * 1000L) / (SAMPLE_RATE * 2L);
    }

    private static void writeLEInt(RandomAccessFile f, int v) throws IOException {
        f.write(v & 0xff); f.write((v >>> 8) & 0xff); f.write((v >>> 16) & 0xff); f.write((v >>> 24) & 0xff);
    }

    private static void writeLEShort(RandomAccessFile f, short v) throws IOException {
        f.write(v & 0xff); f.write((v >>> 8) & 0xff);
    }
}

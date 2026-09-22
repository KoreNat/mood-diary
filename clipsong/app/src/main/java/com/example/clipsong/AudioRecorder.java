package com.example.clipsong;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.*;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.concurrent.atomic.AtomicBoolean;

final class AudioRecorder {
    interface Listener {
        void onStopped(File file);
        void onError(Exception error);
    }

    private final Context context;
    private AudioRecord audioRecord;
    private Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    AudioRecorder(Context context) { this.context = context.getApplicationContext(); }

    boolean isRecording() { return running.get(); }

    void start(File output, Listener listener) {
        if (running.get()) return;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.onError(new SecurityException("Нет разрешения на микрофон"));
            return;
        }
        int min = AudioRecord.getMinBufferSize(
                WavIO.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            listener.onError(new IllegalStateException("Микрофон не поддерживает выбранный формат"));
            return;
        }
        int bufferBytes = Math.max(min * 2, 8192);
        try {
            audioRecord = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(WavIO.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(bufferBytes)
                    .build();
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("Не удалось открыть микрофон");
            }
        } catch (Exception e) {
            listener.onError(e);
            return;
        }

        running.set(true);
        thread = new Thread(() -> {
            long dataBytes = 0;
            byte[] buffer = new byte[bufferBytes];
            try (RandomAccessFile f = new RandomAccessFile(output, "rw")) {
                f.setLength(0);
                WavIO.writeHeader(f, 0);
                audioRecord.startRecording();
                while (running.get()) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        f.write(buffer, 0, read);
                        dataBytes += read;
                    } else if (read < 0) {
                        throw new IllegalStateException("Ошибка чтения микрофона: " + read);
                    }
                }
                try { audioRecord.stop(); } catch (Exception ignored) {}
                WavIO.writeHeader(f, dataBytes);
                listener.onStopped(output);
            } catch (Exception e) {
                output.delete();
                listener.onError(e);
            } finally {
                if (audioRecord != null) {
                    audioRecord.release();
                    audioRecord = null;
                }
                running.set(false);
            }
        }, "ClipSongRecorder");
        thread.start();
    }

    void stop() { running.set(false); }

    void release() {
        running.set(false);
        if (thread != null) {
            try { thread.join(600); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }
}

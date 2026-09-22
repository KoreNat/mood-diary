package com.example.clipsong;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_MIC = 10;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private AudioRecorder recorder;
    private MediaPlayer player;
    private LinearLayout clipsBox;
    private TextView status;
    private Button recordButton;
    private File clipsDir;
    private File processedDir;
    private File songFile;

    private SeekBar cleanupBar;
    private SeekBar brightnessBar;
    private SeekBar bpmBar;
    private TextView cleanupValue;
    private TextView brightnessValue;
    private TextView bpmValue;
    private CheckBox drumsBox;
    private CheckBox bassBox;
    private CheckBox padBox;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        recorder = new AudioRecorder(this);
        clipsDir = new File(getFilesDir(), "clips");
        processedDir = new File(getFilesDir(), "processed");
        clipsDir.mkdirs();
        processedDir.mkdirs();
        songFile = new File(getFilesDir(), "clipsong_mix.wav");
        setContentView(buildUi());
        refreshClips();
        requestMicIfNeeded();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(28));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("ClipSong");
        title.setTextSize(32);
        title.setTextColor(Color.BLACK);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Запишите голос или звуки. Приложение очистит и улучшит запись, а затем может добавить ритм, бас и гармонический фон.");
        sub.setTextSize(16);
        sub.setPadding(0, dp(6), 0, dp(14));
        root.addView(sub);

        recordButton = bigButton("●  Записать фрагмент");
        recordButton.setOnClickListener(v -> toggleRecord());
        root.addView(recordButton);

        root.addView(sectionTitle("Обработка голоса"));

        cleanupValue = valueLabel();
        root.addView(labelRow("Очистка шума", cleanupValue));
        cleanupBar = seek(0, 100, 68);
        cleanupBar.setOnSeekBarChangeListener(simpleProgress(cleanupValue, ""));
        root.addView(cleanupBar);

        brightnessValue = valueLabel();
        root.addView(labelRow("Звонкость / присутствие", brightnessValue));
        brightnessBar = seek(0, 100, 58);
        brightnessBar.setOnSeekBarChangeListener(simpleProgress(brightnessValue, ""));
        root.addView(brightnessBar);

        root.addView(sectionTitle("Сопровождение"));

        drumsBox = new CheckBox(this);
        drumsBox.setText("Ударные");
        drumsBox.setChecked(true);
        root.addView(drumsBox);

        bassBox = new CheckBox(this);
        bassBox.setText("Бас");
        bassBox.setChecked(true);
        root.addView(bassBox);

        padBox = new CheckBox(this);
        padBox.setText("Гармонический фон");
        padBox.setChecked(false);
        root.addView(padBox);

        bpmValue = valueLabel();
        root.addView(labelRow("Темп", bpmValue));
        bpmBar = seek(60, 180, 108);
        bpmBar.setOnSeekBarChangeListener(simpleProgress(bpmValue, " BPM"));
        root.addView(bpmBar);

        Button make = bigButton("♫  Обработать и собрать песню");
        make.setOnClickListener(v -> makeSong());
        root.addView(make);

        LinearLayout songActions = new LinearLayout(this);
        songActions.setOrientation(LinearLayout.HORIZONTAL);
        songActions.setGravity(Gravity.CENTER_VERTICAL);
        Button playSong = smallButton("▶ Песня");
        Button export = smallButton("Сохранить WAV");
        playSong.setOnClickListener(v -> playFile(songFile));
        export.setOnClickListener(v -> exportSong());
        songActions.addView(playSong, new LinearLayout.LayoutParams(0, dp(48), 1));
        songActions.addView(export, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(songActions);

        status = new TextView(this);
        status.setText("Готово к записи");
        status.setTextSize(14);
        status.setPadding(0, dp(14), 0, dp(14));
        root.addView(status);

        root.addView(sectionTitle("Фрагменты"));
        clipsBox = new LinearLayout(this);
        clipsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(clipsBox);

        updateLabels();
        return scroll;
    }

    private TextView sectionTitle(String s) {
        TextView h = new TextView(this);
        h.setText(s);
        h.setTextSize(21);
        h.setTypeface(null, 1);
        h.setPadding(0, dp(12), 0, dp(6));
        return h;
    }

    private LinearLayout labelRow(String left, TextView right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView l = new TextView(this);
        l.setText(left);
        l.setTextSize(15);
        row.addView(l, new LinearLayout.LayoutParams(0, dp(34), 1));
        row.addView(right, new LinearLayout.LayoutParams(dp(90), dp(34)));
        return row;
    }

    private TextView valueLabel() {
        TextView v = new TextView(this);
        v.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        v.setTextSize(14);
        return v;
    }

    private SeekBar seek(int min, int max, int initial) {
        SeekBar s = new SeekBar(this);
        s.setMin(min);
        s.setMax(max);
        s.setProgress(initial);
        return s;
    }

    private SeekBar.OnSeekBarChangeListener simpleProgress(TextView value, String suffix) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(progress + suffix);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
    }

    private void updateLabels() {
        cleanupValue.setText(cleanupBar.getProgress() + "");
        brightnessValue.setText(brightnessBar.getProgress() + "");
        bpmValue.setText(bpmBar.getProgress() + " BPM");
    }

    private Button bigButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(18);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(58));
        p.setMargins(0, 0, 0, dp(10));
        b.setLayoutParams(p);
        return b;
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        return b;
    }

    private SongMaker.Options options() {
        return new SongMaker.Options(
                cleanupBar.getProgress(),
                brightnessBar.getProgress(),
                drumsBox.isChecked(),
                bassBox.isChecked(),
                padBox.isChecked(),
                bpmBar.getProgress()
        );
    }

    private void toggleRecord() {
        if (recorder.isRecording()) {
            recorder.stop();
            recordButton.setEnabled(false);
            status.setText("Сохраняю запись…");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File out = new File(clipsDir, "clip_" + stamp + ".wav");
        recorder.start(out, new AudioRecorder.Listener() {
            @Override public void onStopped(File file) {
                runOnUiThread(() -> {
                    recordButton.setEnabled(true);
                    recordButton.setText("●  Записать фрагмент");
                    status.setText("Записано: " + prettyDuration(file) + ". Можно улучшить и прослушать.");
                    refreshClips();
                });
            }
            @Override public void onError(Exception error) {
                runOnUiThread(() -> {
                    recordButton.setEnabled(true);
                    recordButton.setText("●  Записать фрагмент");
                    status.setText("Ошибка: " + error.getMessage());
                });
            }
        });
        recordButton.setText("■  Остановить");
        status.setText("Идёт запись…");
    }

    private void refreshClips() {
        clipsBox.removeAllViews();
        List<File> files = getClipFiles();
        if (files.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Пока пусто. Запишите первый звук.");
            empty.setTextSize(15);
            clipsBox.addView(empty);
            return;
        }
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            LinearLayout block = new LinearLayout(this);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setPadding(0, dp(4), 0, dp(6));

            TextView label = new TextView(this);
            label.setText((i + 1) + ".  " + prettyDuration(f));
            label.setTextSize(16);
            block.addView(label);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            Button raw = smallButton("▶ Оригинал");
            Button enhanced = smallButton("✨ Улучшить");
            Button del = smallButton("Удалить");

            raw.setOnClickListener(v -> playFile(f));
            enhanced.setOnClickListener(v -> enhanceAndPlay(f));
            del.setOnClickListener(v -> {
                stopPlayer();
                File processed = processedFileFor(f);
                processed.delete();
                if (f.delete()) refreshClips();
            });

            row.addView(raw, new LinearLayout.LayoutParams(0, dp(50), 1));
            row.addView(enhanced, new LinearLayout.LayoutParams(0, dp(50), 1));
            row.addView(del, new LinearLayout.LayoutParams(dp(90), dp(50)));
            block.addView(row);
            clipsBox.addView(block);
        }
    }

    private void enhanceAndPlay(File source) {
        SongMaker.Options opts = options();
        File out = processedFileFor(source);
        status.setText("Очищаю и улучшаю голос…");
        worker.execute(() -> {
            try {
                SongMaker.enhanceClip(source, out, opts);
                runOnUiThread(() -> {
                    status.setText("Улучшенная версия готова");
                    playFile(out);
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Ошибка обработки: " + e.getMessage()));
            }
        });
    }

    private File processedFileFor(File source) {
        return new File(processedDir, "enhanced_" + source.getName());
    }

    private List<File> getClipFiles() {
        File[] arr = clipsDir.listFiles((d, n) -> n.endsWith(".wav"));
        if (arr == null) return new ArrayList<>();
        Arrays.sort(arr, Comparator.comparing(File::getName));
        return new ArrayList<>(Arrays.asList(arr));
    }

    private void makeSong() {
        List<File> clips = getClipFiles();
        SongMaker.Options opts = options();
        status.setText("Обрабатываю голос и собираю аранжировку…");
        worker.execute(() -> {
            try {
                SongMaker.makeSong(clips, songFile, opts);
                runOnUiThread(() -> status.setText("Песня готова: " + prettyDuration(songFile) + "."));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Не получилось собрать: " + e.getMessage()));
            }
        });
    }

    private void playFile(File f) {
        if (!f.exists()) {
            status.setText("Файл ещё не создан");
            return;
        }
        stopPlayer();
        try {
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.setOnCompletionListener(mp -> stopPlayer());
            player.prepare();
            player.start();
            status.setText("Воспроизведение: " + prettyDuration(f));
        } catch (Exception e) {
            stopPlayer();
            status.setText("Ошибка воспроизведения: " + e.getMessage());
        }
    }

    private void exportSong() {
        if (!songFile.exists()) {
            status.setText("Сначала нажмите «Обработать и собрать песню»");
            return;
        }
        worker.execute(() -> {
            String name = "ClipSong_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".wav";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav");
            values.put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/ClipSong");
            values.put(MediaStore.Audio.Media.IS_PENDING, 1);
            Uri uri = null;
            try {
                uri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("MediaStore не создал файл");
                try (InputStream in = new FileInputStream(songFile);
                     OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IOException("Нет доступа к файлу назначения");
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                values.clear();
                values.put(MediaStore.Audio.Media.IS_PENDING, 0);
                getContentResolver().update(uri, values, null, null);
                runOnUiThread(() -> status.setText("Сохранено в Music/ClipSong/" + name));
            } catch (Exception e) {
                if (uri != null) getContentResolver().delete(uri, null, null);
                runOnUiThread(() -> status.setText("Ошибка сохранения: " + e.getMessage()));
            }
        });
    }

    private void stopPlayer() {
        if (player != null) {
            try { if (player.isPlaying()) player.stop(); } catch (Exception ignored) {}
            player.release();
            player = null;
        }
    }

    private String prettyDuration(File f) {
        long ms = WavIO.durationMs(f);
        return String.format(Locale.getDefault(), "%d:%02d", ms / 60000, (ms / 1000) % 60);
    }

    private void requestMicIfNeeded() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    @Override public void onRequestPermissionsResult(int req, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(req, permissions, grantResults);
        if (req == REQ_MIC && (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            status.setText("Для записи нужно разрешить доступ к микрофону.");
        }
    }

    @Override protected void onDestroy() {
        stopPlayer();
        recorder.release();
        worker.shutdownNow();
        super.onDestroy();
    }

    private int dp(int x) { return (int)(x * getResources().getDisplayMetrics().density + 0.5f); }
}

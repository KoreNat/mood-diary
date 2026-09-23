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
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_MIC = 10;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LinkedHashSet<String> stitchSelection = new LinkedHashSet<>();

    private AudioRecorder recorder;
    private MediaPlayer player;
    private LinearLayout clipsBox;
    private TextView status;
    private Button recordButton;
    private File clipsDir;
    private File processedDir;
    private File songFile;

    private EditText requestBox;
    private TextView planView;
    private SeekBar cleanupBar;
    private SeekBar brightnessBar;
    private SeekBar bpmBar;
    private TextView cleanupValue;
    private TextView brightnessValue;
    private TextView bpmValue;
    private CheckBox drumsBox;
    private CheckBox bassBox;
    private CheckBox padBox;

    private EditText apiKeyBox;
    private EditText transcriptBox;
    private EditText literaryInstructionBox;

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
        title.setText("ClipSong 0.4");
        title.setTextSize(32);
        title.setTextColor(Color.BLACK);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Записывайте куски, сшивайте их, улучшайте вокал, распознавайте слова и мелодию, затем стройте музыку по самой мелодии.");
        sub.setTextSize(16);
        sub.setPadding(0, dp(6), 0, dp(14));
        root.addView(sub);

        recordButton = bigButton("●  Записать фрагмент");
        recordButton.setOnClickListener(v -> toggleRecord());
        root.addView(recordButton);

        root.addView(sectionTitle("Музыкальный запрос"));
        requestBox = new EditText(this);
        requestBox.setHint("Например: тёплый глубокий вокал, мягкое пианино и бас, без ударных; музыку подстроить под мою мелодию.");
        requestBox.setMinLines(3);
        requestBox.setMaxLines(6);
        requestBox.setGravity(Gravity.TOP | Gravity.START);
        requestBox.setTextSize(16);
        requestBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(requestBox, new LinearLayout.LayoutParams(-1, dp(120)));

        Button understand = smallButton("Понять запрос");
        understand.setOnClickListener(v -> {
            hideKeyboard();
            planView.setText(SongMaker.interpret(options()).summary());
        });
        root.addView(understand, new LinearLayout.LayoutParams(-1, dp(48)));

        planView = new TextView(this);
        planView.setText("Можно описывать стиль, инструменты, тембр голоса, reverb и характер звучания.");
        planView.setTextSize(14);
        planView.setPadding(0, dp(8), 0, dp(10));
        root.addView(planView);

        root.addView(sectionTitle("Ручные настройки"));
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

        Button make = bigButton("♫  Сделать музыку по мелодии");
        make.setOnClickListener(v -> makeSong());
        root.addView(make);

        LinearLayout songActions = new LinearLayout(this);
        songActions.setOrientation(LinearLayout.HORIZONTAL);
        Button playSong = smallButton("▶ Песня");
        Button export = smallButton("Сохранить WAV");
        playSong.setOnClickListener(v -> playFile(songFile));
        export.setOnClickListener(v -> exportSong());
        songActions.addView(playSong, new LinearLayout.LayoutParams(0, dp(48), 1));
        songActions.addView(export, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(songActions);

        root.addView(sectionTitle("Распознавание и литературная редактура"));
        apiKeyBox = new EditText(this);
        apiKeyBox.setHint("OpenAI API key (нужен только для текста)");
        apiKeyBox.setSingleLine(true);
        apiKeyBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(apiKeyBox, new LinearLayout.LayoutParams(-1, dp(54)));

        transcriptBox = new EditText(this);
        transcriptBox.setHint("Здесь появится распознанный текст. Его можно исправлять вручную.");
        transcriptBox.setMinLines(4);
        transcriptBox.setGravity(Gravity.TOP | Gravity.START);
        root.addView(transcriptBox, new LinearLayout.LayoutParams(-1, dp(150)));

        literaryInstructionBox = new EditText(this);
        literaryInstructionBox.setHint("Например: сделай литературнее, убери повторы, сохрани мою интонацию и все факты.");
        literaryInstructionBox.setMinLines(2);
        literaryInstructionBox.setGravity(Gravity.TOP | Gravity.START);
        root.addView(literaryInstructionBox, new LinearLayout.LayoutParams(-1, dp(92)));

        Button literary = bigButton("✎  Литературно обработать текст");
        literary.setOnClickListener(v -> literaryEdit());
        root.addView(literary);

        status = new TextView(this);
        status.setText("Готово к записи");
        status.setTextSize(14);
        status.setPadding(0, dp(14), 0, dp(14));
        root.addView(status);

        root.addView(sectionTitle("Фрагменты"));
        Button stitch = bigButton("Сшить выбранные 2 фрагмента");
        stitch.setOnClickListener(v -> stitchSelected());
        root.addView(stitch);

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
        cleanupValue.setText(String.valueOf(cleanupBar.getProgress()));
        brightnessValue.setText(String.valueOf(brightnessBar.getProgress()));
        bpmValue.setText(bpmBar.getProgress() + " BPM");
    }

    private Button bigButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(17);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(58));
        p.setMargins(0, 0, 0, dp(10));
        b.setLayoutParams(p);
        return b;
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        return b;
    }

    private SongMaker.Options options() {
        return new SongMaker.Options(
                cleanupBar.getProgress(),
                brightnessBar.getProgress(),
                drumsBox.isChecked(),
                bassBox.isChecked(),
                padBox.isChecked(),
                bpmBar.getProgress(),
                requestBox == null ? "" : requestBox.getText().toString()
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
                    status.setText("Записано: " + prettyDuration(file));
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
        stitchSelection.retainAll(fileNames(files));
        if (files.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Пока пусто. Запишите первый звук.");
            clipsBox.addView(empty);
            return;
        }

        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            LinearLayout block = new LinearLayout(this);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setPadding(0, dp(6), 0, dp(8));

            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            TextView label = new TextView(this);
            label.setText((i + 1) + ". " + prettyDuration(f) + "   " + f.getName());
            label.setTextSize(15);
            CheckBox select = new CheckBox(this);
            select.setText("Сшить");
            select.setChecked(stitchSelection.contains(f.getName()));
            select.setOnCheckedChangeListener((button, checked) -> {
                if (checked) {
                    if (stitchSelection.size() >= 2) {
                        button.setChecked(false);
                        status.setText("Для сшивания выберите ровно два фрагмента");
                    } else {
                        stitchSelection.add(f.getName());
                    }
                } else {
                    stitchSelection.remove(f.getName());
                }
            });
            head.addView(label, new LinearLayout.LayoutParams(0, dp(48), 1));
            head.addView(select, new LinearLayout.LayoutParams(dp(100), dp(48)));
            block.addView(head);

            LinearLayout row1 = new LinearLayout(this);
            row1.setOrientation(LinearLayout.HORIZONTAL);
            Button raw = smallButton("▶ Оригинал");
            Button enhanced = smallButton("✨ Вокал");
            Button del = smallButton("Удалить");
            raw.setOnClickListener(v -> playFile(f));
            enhanced.setOnClickListener(v -> enhanceAndPlay(f));
            del.setOnClickListener(v -> {
                stopPlayer();
                stitchSelection.remove(f.getName());
                processedFileFor(f).delete();
                if (f.delete()) refreshClips();
            });
            row1.addView(raw, new LinearLayout.LayoutParams(0, dp(48), 1));
            row1.addView(enhanced, new LinearLayout.LayoutParams(0, dp(48), 1));
            row1.addView(del, new LinearLayout.LayoutParams(0, dp(48), 1));
            block.addView(row1);

            LinearLayout row2 = new LinearLayout(this);
            row2.setOrientation(LinearLayout.HORIZONTAL);
            Button text = smallButton("Текст");
            Button melody = smallButton("Мелодия");
            text.setOnClickListener(v -> transcribe(f));
            melody.setOnClickListener(v -> analyzeMelody(f));
            row2.addView(text, new LinearLayout.LayoutParams(0, dp(48), 1));
            row2.addView(melody, new LinearLayout.LayoutParams(0, dp(48), 1));
            block.addView(row2);

            clipsBox.addView(block);
        }
    }

    private Set<String> fileNames(List<File> files) {
        HashSet<String> names = new HashSet<>();
        for (File f : files) names.add(f.getName());
        return names;
    }

    private void stitchSelected() {
        if (stitchSelection.size() != 2) {
            status.setText("Отметьте ровно два фрагмента флажком «Сшить»");
            return;
        }
        List<File> files = getClipFiles();
        ArrayList<File> selected = new ArrayList<>();
        for (File f : files) if (stitchSelection.contains(f.getName())) selected.add(f);
        if (selected.size() != 2) {
            status.setText("Не удалось найти два выбранных файла");
            return;
        }

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File out = new File(clipsDir, "joined_" + stamp + ".wav");
        SongMaker.Options opts = options();
        status.setText("Сшиваю два куска и выравниваю переход…");
        worker.execute(() -> {
            try {
                SongMaker.stitchTwo(selected.get(0), selected.get(1), out, opts);
                runOnUiThread(() -> {
                    stitchSelection.clear();
                    status.setText("Готов новый сшитый фрагмент: " + prettyDuration(out));
                    refreshClips();
                    playFile(out);
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Ошибка сшивания: " + e.getMessage()));
            }
        });
    }

    private void enhanceAndPlay(File source) {
        SongMaker.Options opts = options();
        StyleInterpreter.Plan plan = SongMaker.interpret(opts);
        File out = processedFileFor(source);
        status.setText("Делаю вокал: " + plan.timbre.name().toLowerCase(Locale.ROOT) + "…");
        worker.execute(() -> {
            try {
                SongMaker.enhanceClip(source, out, opts);
                runOnUiThread(() -> {
                    status.setText("Вокальная обработка готова");
                    playFile(out);
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Ошибка обработки: " + e.getMessage()));
            }
        });
    }

    private void analyzeMelody(File source) {
        SongMaker.Options opts = options();
        status.setText("Распознаю высоту нот и тональность…");
        Toast.makeText(this, "Распознаю мелодию…", Toast.LENGTH_SHORT).show();

        final android.app.ProgressDialog progress = new android.app.ProgressDialog(this);
        progress.setMessage("Распознаю мелодию…");
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();

        worker.execute(() -> {
            try {
                MelodyAnalyzer.Result result = SongMaker.analyzeMelody(source, opts);
                runOnUiThread(() -> {
                    progress.dismiss();
                    status.setText(result.summary());
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("Результат анализа")
                            .setMessage(result.summary())
                            .setPositiveButton("OK", null)
                            .show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    status.setText("Ошибка анализа мелодии: " + e.getMessage());
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("Не удалось распознать мелодию")
                            .setMessage(e.getMessage())
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        });
    }

    private void transcribe(File source) {
        String key = apiKeyBox.getText().toString();
        status.setText("Распознаю речь…");
        worker.execute(() -> {
            try {
                String text = OpenAiClient.transcribe(source, key);
                runOnUiThread(() -> {
                    transcriptBox.setText(text);
                    status.setText("Текст распознан");
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Ошибка распознавания: " + e.getMessage()));
            }
        });
    }

    private void literaryEdit() {
        hideKeyboard();
        String key = apiKeyBox.getText().toString();
        String text = transcriptBox.getText().toString();
        String instruction = literaryInstructionBox.getText().toString();
        status.setText("Литературно редактирую текст…");
        worker.execute(() -> {
            try {
                String edited = OpenAiClient.literaryEdit(text, instruction, key);
                runOnUiThread(() -> {
                    transcriptBox.setText(edited);
                    status.setText("Литературная редактура готова");
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Ошибка редактора: " + e.getMessage()));
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
        hideKeyboard();
        List<File> clips = getClipFiles();
        SongMaker.Options opts = options();
        StyleInterpreter.Plan plan = SongMaker.interpret(opts);
        planView.setText(plan.summary());
        status.setText("Распознаю мелодию и строю под неё аранжировку…");
        worker.execute(() -> {
            try {
                SongMaker.makeSong(clips, songFile, opts);
                runOnUiThread(() -> {
                    status.setText("Песня готова: " + prettyDuration(songFile));
                    Toast.makeText(this, "Песня готова", Toast.LENGTH_LONG).show();
                });
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
            status.setText("Сначала сделайте песню");
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

    private void hideKeyboard() {
        View v = getCurrentFocus();
        if (v == null) return;
        InputMethodManager imm = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        v.clearFocus();
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

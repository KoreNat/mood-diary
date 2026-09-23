package com.korenat.pozvonimne;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.DatePickerDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaRecorder;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

public class MainActivity extends Activity {
    public static final String CHANNEL_ID = "self_call_channel";
    public static final String EXTRA_CALLER = "caller_name";
    public static final String PREFS = "fake_call_settings";

    public static final String[] TRIGGERS = {
            "Volume Up",
            "Volume Down",
            "Один тап по пустому экрану",
            "Двойной тап по пустому экрану",
            "Двойной тап по красной кнопке",
            "Только голосовое слово"
    };

    public static final String[] VOICE_LANGUAGES = {
            "Авто: English / Русский / Nederlands",
            "English",
            "Русский",
            "Nederlands"
    };

    private SharedPreferences prefs;
    private EditText callerName;
    private EditText relativeMinutes;
    private EditText emergencyWords;
    private EditText neutralWords;
    private Spinner emergencyTrigger;
    private Spinner neutralTrigger;
    private Spinner voiceLanguage;
    private CheckBox voiceEnabled;
    private TextView dateTimeText;
    private TextView statusText;
    private Button recordEmergency;
    private Button recordNeutral;

    private final Calendar selected = Calendar.getInstance();
    private MediaRecorder recorder;
    private String recordingKind;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        selected.add(Calendar.MINUTE, 15);
        createNotificationChannel();
        buildUi();
        loadSettings();
        requestNotificationPermissionIfNeeded();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(30), dp(22), dp(36));
        root.setBackgroundColor(Color.WHITE);
        scroll.addView(root);

        TextView title = text("Позвони мне", 30, Color.BLACK);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap(0, dp(4)));

        TextView sub = text("Запланированный входящий звонок", 15, Color.DKGRAY);
        sub.setGravity(Gravity.CENTER);
        root.addView(sub, matchWrap(0, dp(22)));

        addSection(root, "Звонящий");
        callerName = input("Имя звонящего", true);
        callerName.setText("Анна");
        root.addView(callerName, matchWrap(0, dp(18)));

        addSection(root, "Когда позвонить");
        LinearLayout dateRow = new LinearLayout(this);
        dateRow.setOrientation(LinearLayout.HORIZONTAL);

        Button date = new Button(this);
        date.setText("Дата");
        date.setOnClickListener(v -> chooseDate());
        dateRow.addView(date, weighted());

        Button time = new Button(this);
        time.setText("Время");
        time.setOnClickListener(v -> chooseTime());
        dateRow.addView(time, weighted());

        root.addView(dateRow, matchWrap(0, dp(6)));

        dateTimeText = text("", 20, Color.BLACK);
        dateTimeText.setGravity(Gravity.CENTER);
        root.addView(dateTimeText, matchWrap(0, dp(10)));
        updateDateTimeText();

        Button scheduleExact = new Button(this);
        scheduleExact.setText("Назначить на дату и время");
        scheduleExact.setOnClickListener(v -> scheduleExact());
        root.addView(scheduleExact, matchWrap(0, dp(16)));

        TextView or = text("или", 14, Color.GRAY);
        or.setGravity(Gravity.CENTER);
        root.addView(or, matchWrap(0, dp(6)));

        relativeMinutes = input("Через сколько минут, например 15", true);
        relativeMinutes.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        relativeMinutes.setText("15");
        root.addView(relativeMinutes, matchWrap(0, dp(6)));

        Button scheduleRelative = new Button(this);
        scheduleRelative.setText("Назначить через N минут");
        scheduleRelative.setOnClickListener(v -> scheduleRelative());
        root.addView(scheduleRelative, matchWrap(0, dp(22)));

        addSection(root, "Срочный сценарий");
        emergencyTrigger = spinner(TRIGGERS);
        root.addView(emergencyTrigger, matchWrap(0, dp(8)));

        emergencyWords = input("Ключевые слова через запятую", false);
        emergencyWords.setText("hello, hallo, алло, хэлло");
        root.addView(emergencyWords, matchWrap(0, dp(8)));

        recordEmergency = new Button(this);
        recordEmergency.setText("Записать срочную реплику");
        recordEmergency.setOnClickListener(v -> toggleRecording("emergency"));
        root.addView(recordEmergency, matchWrap(0, dp(18)));

        addSection(root, "Нейтральный сценарий");
        neutralTrigger = spinner(TRIGGERS);
        root.addView(neutralTrigger, matchWrap(0, dp(8)));

        neutralWords = input("Ключевые слова через запятую", false);
        neutralWords.setText("hi, high, хай, привет");
        root.addView(neutralWords, matchWrap(0, dp(8)));

        recordNeutral = new Button(this);
        recordNeutral.setText("Записать нейтральную реплику");
        recordNeutral.setOnClickListener(v -> toggleRecording("neutral"));
        root.addView(recordNeutral, matchWrap(0, dp(12)));

        voiceEnabled = new CheckBox(this);
        voiceEnabled.setText("Реагировать на ключевые слова после ответа");
        voiceEnabled.setChecked(true);
        root.addView(voiceEnabled, matchWrap(0, dp(8)));

        TextView languageLabel = text("Язык распознавания", 16, Color.BLACK);
        root.addView(languageLabel, matchWrap(0, dp(4)));
        voiceLanguage = spinner(VOICE_LANGUAGES);
        root.addView(voiceLanguage, matchWrap(0, dp(22)));

        Button save = new Button(this);
        save.setText("Сохранить настройки");
        save.setOnClickListener(v -> {
            saveSettings();
            if (ensureVoicePermission()) {
                Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(save, matchWrap(0, dp(10)));

        Button test = new Button(this);
        test.setText("Тест: позвонить сейчас");
        test.setOnClickListener(v -> {
            saveSettings();
            if (!ensureVoicePermission()) return;
            startCallNow();
        });
        root.addView(test, matchWrap(0, dp(10)));

        Button permissions = new Button(this);
        permissions.setText("Проверить системные разрешения звонка");
        permissions.setOnClickListener(v -> openNeededPermissions());
        root.addView(permissions, matchWrap(0, dp(16)));

        statusText = text("Сначала проверь тестовый звонок.", 14, Color.DKGRAY);
        statusText.setGravity(Gravity.CENTER);
        root.addView(statusText);

        setContentView(scroll);
    }

    private Spinner spinner(String[] items) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                items
        );
        s.setAdapter(adapter);
        return s;
    }

    private void addSection(LinearLayout root, String value) {
        TextView v = text(value, 19, Color.BLACK);
        v.setPadding(0, dp(6), 0, dp(8));
        root.addView(v, matchWrap(dp(4), dp(4)));
    }

    private TextView text(String value, float size, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }

    private EditText input(String hint, boolean singleLine) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(singleLine);
        if (!singleLine) e.setMinLines(2);
        return e;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    private LinearLayout.LayoutParams matchWrap(int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, top, 0, bottom);
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void chooseDate() {
        new DatePickerDialog(
                this,
                (view, year, month, day) -> {
                    selected.set(Calendar.YEAR, year);
                    selected.set(Calendar.MONTH, month);
                    selected.set(Calendar.DAY_OF_MONTH, day);
                    updateDateTimeText();
                },
                selected.get(Calendar.YEAR),
                selected.get(Calendar.MONTH),
                selected.get(Calendar.DAY_OF_MONTH)
        ).show();
    }

    private void chooseTime() {
        new TimePickerDialog(
                this,
                (view, hour, minute) -> {
                    selected.set(Calendar.HOUR_OF_DAY, hour);
                    selected.set(Calendar.MINUTE, minute);
                    selected.set(Calendar.SECOND, 0);
                    selected.set(Calendar.MILLISECOND, 0);
                    updateDateTimeText();
                },
                selected.get(Calendar.HOUR_OF_DAY),
                selected.get(Calendar.MINUTE),
                true
        ).show();
    }

    private void updateDateTimeText() {
        SimpleDateFormat fmt = new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault());
        dateTimeText.setText(fmt.format(selected.getTime()));
    }

    private boolean ensureExactAlarmPermission() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception ignored) { }
            statusText.setText("Разреши «Будильники и напоминания», затем повтори.");
            return false;
        }
        return true;
    }

    private boolean ensureVoicePermission() {
        if (!voiceEnabled.isChecked()) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 51);
            statusText.setText("Разреши микрофон и повтори действие.");
            return false;
        }
        return true;
    }

    private void scheduleExact() {
        saveSettings();
        if (!ensureVoicePermission()) return;
        if (!ensureExactAlarmPermission()) return;
        if (selected.getTimeInMillis() <= System.currentTimeMillis()) {
            statusText.setText("Выбранное время уже прошло.");
            return;
        }
        scheduleAlarm(selected.getTimeInMillis());
    }

    private void scheduleRelative() {
        saveSettings();
        if (!ensureVoicePermission()) return;
        if (!ensureExactAlarmPermission()) return;

        int minutes;
        try {
            minutes = Integer.parseInt(relativeMinutes.getText().toString().trim());
        } catch (Exception e) {
            statusText.setText("Введи число минут.");
            return;
        }

        if (minutes < 1 || minutes > 10080) {
            statusText.setText("Допустимо от 1 минуты до 7 дней.");
            return;
        }

        scheduleAlarm(System.currentTimeMillis() + minutes * 60_000L);
    }

    private void scheduleAlarm(long when) {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        Intent receiverIntent = new Intent(this, AlarmReceiver.class);
        receiverIntent.putExtra(EXTRA_CALLER, getCallerName());

        PendingIntent pi = PendingIntent.getBroadcast(
                this,
                1001,
                receiverIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        SimpleDateFormat fmt = new SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault());
        statusText.setText("Звонок назначен: " + fmt.format(when));
        Toast.makeText(this, "Звонок назначен", Toast.LENGTH_SHORT).show();
    }

    private String getCallerName() {
        String s = callerName.getText().toString().trim();
        return s.isEmpty() ? "Анна" : s;
    }

    private void startCallNow() {
        Intent i = new Intent(this, IncomingCallActivity.class);
        i.putExtra(EXTRA_CALLER, getCallerName());
        startActivity(i);
    }

    private void saveSettings() {
        prefs.edit()
                .putString("caller", getCallerName())
                .putInt("emergency_trigger", emergencyTrigger.getSelectedItemPosition())
                .putInt("neutral_trigger", neutralTrigger.getSelectedItemPosition())
                .putString("emergency_words", emergencyWords.getText().toString().trim())
                .putString("neutral_words", neutralWords.getText().toString().trim())
                .putBoolean("voice_enabled", voiceEnabled.isChecked())
                .putInt("voice_language", voiceLanguage.getSelectedItemPosition())
                .apply();
    }

    private void loadSettings() {
        callerName.setText(prefs.getString("caller", "Анна"));
        emergencyTrigger.setSelection(prefs.getInt("emergency_trigger", 0));
        neutralTrigger.setSelection(prefs.getInt("neutral_trigger", 1));
        emergencyWords.setText(prefs.getString("emergency_words", "hello, hallo, алло, хэлло"));
        neutralWords.setText(prefs.getString("neutral_words", "hi, high, хай, привет"));
        voiceEnabled.setChecked(prefs.getBoolean("voice_enabled", true));
        voiceLanguage.setSelection(prefs.getInt("voice_language", 0));
    }

    private File recordingFile(String kind) {
        return new File(getFilesDir(), kind + ".m4a");
    }

    private void toggleRecording(String kind) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 51);
            Toast.makeText(this, "Разреши микрофон и нажми запись ещё раз", Toast.LENGTH_LONG).show();
            return;
        }

        if (recorder != null) {
            stopRecording();
            return;
        }

        try {
            recorder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? new MediaRecorder(this)
                    : new MediaRecorder();
            recordingKind = kind;
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(recordingFile(kind).getAbsolutePath());
            recorder.prepare();
            recorder.start();
            buttonFor(kind).setText("Стоп — сохранить запись");
            statusText.setText("Идёт запись…");
        } catch (Exception e) {
            recorder = null;
            recordingKind = null;
            statusText.setText("Не удалось начать запись.");
        }
    }

    private Button buttonFor(String kind) {
        return "emergency".equals(kind) ? recordEmergency : recordNeutral;
    }

    private void stopRecording() {
        if (recorder == null) return;
        try { recorder.stop(); } catch (Exception ignored) { }
        try { recorder.release(); } catch (Exception ignored) { }
        recorder = null;
        recordingKind = null;
        recordEmergency.setText("Записать срочную реплику");
        recordNeutral.setText("Записать нейтральную реплику");
        statusText.setText("Запись сохранена.");
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 44);
        }
    }

    private void openNeededPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (!nm.canUseFullScreenIntent()) {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                    return;
                } catch (Exception ignored) { }
            }
        }
        if (!ensureExactAlarmPermission()) return;
        Toast.makeText(this, "Основные разрешения звонка включены", Toast.LENGTH_SHORT).show();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Входящие звонки",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.enableVibration(true);
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build();
            channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), attrs);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    protected void onDestroy() {
        if (recorder != null) stopRecording();
        super.onDestroy();
    }
}

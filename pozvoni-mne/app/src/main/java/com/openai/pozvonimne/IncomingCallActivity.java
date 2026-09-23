package com.openai.pozvonimne;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.telephony.SmsManager;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

public class IncomingCallActivity extends Activity {
    private SharedPreferences prefs;
    private Ringtone ringtone;
    private Vibrator vibrator;
    private MediaPlayer player;
    private TextToSpeech tts;
    private SpeechRecognizer speechRecognizer;

    private boolean answered = false;
    private boolean scenarioPlayed = false;
    private boolean voiceListening = false;
    private String caller;
    private TextView timerText;
    private long callStartedAt;

    private int emergencyTrigger;
    private int neutralTrigger;
    private String emergencyWords;
    private String neutralWords;
    private boolean voiceEnabled;

    private long lastBackgroundTap = 0L;
    private long lastRedTap = 0L;
    private boolean pendingSingleBackground = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean volumeUpDown = false;
    private boolean sosTriggered = false;
    private final Runnable sosRunnable = () -> {
        if (volumeUpDown && answered) {
            sosTriggered = true;
            performSos();
        }
    };

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!answered || timerText == null) return;
            long seconds = (System.currentTimeMillis() - callStartedAt) / 1000L;
            timerText.setText(String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60));
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            );
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        caller = getIntent().getStringExtra(MainActivity.EXTRA_CALLER);
        if (caller == null || caller.trim().isEmpty()) caller = prefs.getString("caller", "Анна");

        emergencyTrigger = prefs.getInt("emergency_trigger", 0);
        neutralTrigger = prefs.getInt("neutral_trigger", 1);
        emergencyWords = prefs.getString("emergency_words", "hello, алло");
        neutralWords = prefs.getString("neutral_words", "hi, привет");
        voiceEnabled = prefs.getBoolean("voice_enabled", true);

        initTts();
        buildIncomingUi();
        cancelNotification();
        startRinging();
    }

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(new Locale("ru", "RU"));
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.getDefault());
                }
                tts.setSpeechRate(0.95f);
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
            }
        });
    }

    private void buildIncomingUi() {
        LinearLayout root = baseRoot();

        TextView label = text("Входящий вызов", 17, Color.LTGRAY);
        label.setGravity(Gravity.CENTER);
        root.addView(label, wrap(0, dp(26)));

        TextView avatar = text(caller.substring(0, 1).toUpperCase(Locale.getDefault()), 42, Color.WHITE);
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable avatarBg = new GradientDrawable();
        avatarBg.setShape(GradientDrawable.OVAL);
        avatarBg.setColor(Color.rgb(76, 82, 94));
        avatar.setBackground(avatarBg);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(dp(116), dp(116));
        aLp.gravity = Gravity.CENTER_HORIZONTAL;
        aLp.setMargins(0, 0, 0, dp(22));
        root.addView(avatar, aLp);

        TextView name = text(caller, 35, Color.WHITE);
        name.setGravity(Gravity.CENTER);
        root.addView(name, wrap(0, dp(5)));

        TextView type = text("Мобильный", 16, Color.LTGRAY);
        type.setGravity(Gravity.CENTER);
        root.addView(type, wrap(0, dp(66)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);

        Button decline = roundButton("✕", Color.rgb(205, 55, 55));
        decline.setOnClickListener(v -> endCall());
        actions.addView(decline, square(dp(80), dp(24)));

        Space gap = new Space(this);
        actions.addView(gap, new LinearLayout.LayoutParams(dp(82), 1));

        Button answer = roundButton("☎", Color.rgb(38, 174, 88));
        answer.setOnClickListener(v -> answerCall());
        actions.addView(answer, square(dp(80), dp(24)));

        root.addView(actions, matchWrap(0, dp(8)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.HORIZONTAL);
        labels.setGravity(Gravity.CENTER);
        TextView dl = text("Отклонить", 14, Color.LTGRAY);
        dl.setGravity(Gravity.CENTER);
        labels.addView(dl, new LinearLayout.LayoutParams(dp(120), dp(34)));
        TextView al = text("Ответить", 14, Color.LTGRAY);
        al.setGravity(Gravity.CENTER);
        labels.addView(al, new LinearLayout.LayoutParams(dp(120), dp(34)));
        root.addView(labels);

        setContentView(root);
    }

    private void buildConnectedUi() {
        LinearLayout root = baseRoot();
        root.setClickable(true);
        root.setOnTouchListener((v, event) -> {
            if (event.getAction() != MotionEvent.ACTION_UP) return true;

            long now = System.currentTimeMillis();
            if (now - lastBackgroundTap < 420) {
                lastBackgroundTap = 0;
                pendingSingleBackground = false;
                handler.removeCallbacksAndMessages("single_bg");
                handleTrigger(3);
            } else {
                lastBackgroundTap = now;
                pendingSingleBackground = true;
                Runnable single = () -> {
                    if (pendingSingleBackground) {
                        pendingSingleBackground = false;
                        handleTrigger(2);
                    }
                };
                handler.postDelayed(single, 430);
            }
            return true;
        });

        TextView name = text(caller, 32, Color.WHITE);
        name.setGravity(Gravity.CENTER);
        root.addView(name, wrap(dp(8), dp(6)));

        timerText = text("00:00", 18, Color.LTGRAY);
        timerText.setGravity(Gravity.CENTER);
        root.addView(timerText, wrap(0, dp(52)));

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER);

        Button mute = smallButton("Микрофон");
        tools.addView(mute, square(dp(108), dp(8)));

        Button speaker = smallButton("Динамик");
        speaker.setAlpha(0.55f);
        speaker.setOnClickListener(v -> {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            boolean on = speaker.getAlpha() < 0.9f;
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            am.setSpeakerphoneOn(on);
            speaker.setAlpha(on ? 1f : 0.55f);
        });
        tools.addView(speaker, square(dp(108), dp(8)));

        root.addView(tools, matchWrap(0, dp(48)));

        Button end = roundButton("✕", Color.rgb(205, 55, 55));
        end.setOnClickListener(v -> handleRedButton());
        LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(dp(84), dp(84));
        eLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(end, eLp);

        TextView endLabel = text("Завершить", 14, Color.LTGRAY);
        endLabel.setGravity(Gravity.CENTER);
        root.addView(endLabel, matchWrap(dp(8), dp(8)));

        TextView hint = text(" ", 12, Color.rgb(24, 28, 35));
        root.addView(hint);

        setContentView(root);
    }

    private LinearLayout baseRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(64), dp(24), dp(36));
        root.setBackgroundColor(Color.rgb(24, 28, 35));
        return root;
    }

    private TextView text(String value, float size, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }

    private Button roundButton(String value, int color) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextSize(27);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, 0, 0, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        b.setBackground(bg);
        return b;
    }

    private Button smallButton(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(65, 70, 80)));
        return b;
    }

    private LinearLayout.LayoutParams square(int size, int margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMargins(margin, 0, margin, 0);
        return lp;
    }

    private LinearLayout.LayoutParams wrap(int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.setMargins(0, top, 0, bottom);
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

    private void startRinging() {
        try {
            ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && ringtone != null) ringtone.setLooping(true);
            if (ringtone != null) ringtone.play();
        } catch (Exception ignored) { }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vibrator = vm.getDefaultVibrator();
            } else {
                vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (vibrator != null) {
                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 650, 350, 650, 1200}, 0));
            }
        } catch (Exception ignored) { }
    }

    private void stopRinging() {
        try {
            if (ringtone != null && ringtone.isPlaying()) ringtone.stop();
        } catch (Exception ignored) { }
        try {
            if (vibrator != null) vibrator.cancel();
        } catch (Exception ignored) { }
    }

    private void answerCall() {
        stopRinging();
        answered = true;
        scenarioPlayed = false;
        callStartedAt = System.currentTimeMillis();
        buildConnectedUi();
        handler.post(timerRunnable);
        startVoiceRecognition();
    }

    private void handleRedButton() {
        long now = System.currentTimeMillis();
        if (now - lastRedTap < 480) {
            lastRedTap = 0;
            handleTrigger(4);
            return;
        }
        lastRedTap = now;
        handler.postDelayed(() -> {
            if (lastRedTap != 0 && System.currentTimeMillis() - lastRedTap >= 470) {
                lastRedTap = 0;
                endCall();
            }
        }, 500);
    }

    private void handleTrigger(int triggerCode) {
        if (!answered || scenarioPlayed) return;
        if (emergencyTrigger == triggerCode) {
            playScenario(true);
        } else if (neutralTrigger == triggerCode) {
            playScenario(false);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!answered) return super.onKeyDown(keyCode, event);

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.getRepeatCount() == 0) {
                volumeUpDown = true;
                sosTriggered = false;
                handler.postDelayed(sosRunnable, 3000);
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getRepeatCount() == 0) handleTrigger(1);
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (answered && keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            volumeUpDown = false;
            handler.removeCallbacks(sosRunnable);
            if (!sosTriggered) handleTrigger(0);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void startVoiceRecognition() {
        if (!voiceEnabled || scenarioPlayed) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                speechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
            } else {
                return;
            }

            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { voiceListening = true; }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { voiceListening = false; }
                @Override public void onError(int error) {
                    voiceListening = false;
                    if (answered && !scenarioPlayed) handler.postDelayed(IncomingCallActivity.this::restartVoiceRecognition, 900);
                }
                @Override public void onResults(Bundle results) {
                    voiceListening = false;
                    ArrayList<String> heard = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (heard != null) {
                        for (String s : heard) {
                            String normalized = s.toLowerCase(Locale.getDefault());
                            if (containsKeyword(normalized, emergencyWords)) {
                                playScenario(true);
                                return;
                            }
                            if (containsKeyword(normalized, neutralWords)) {
                                playScenario(false);
                                return;
                            }
                        }
                    }
                    if (answered && !scenarioPlayed) handler.postDelayed(IncomingCallActivity.this::restartVoiceRecognition, 700);
                }
                @Override public void onPartialResults(Bundle partialResults) { }
                @Override public void onEvent(int eventType, Bundle params) { }
            });

            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            speechRecognizer.startListening(i);
        } catch (Exception ignored) { }
    }

    private void restartVoiceRecognition() {
        stopVoiceRecognition();
        startVoiceRecognition();
    }

    private boolean containsKeyword(String heard, String csv) {
        if (csv == null) return false;
        for (String raw : csv.split(",")) {
            String k = raw.trim().toLowerCase(Locale.getDefault());
            if (!k.isEmpty() && heard.contains(k)) return true;
        }
        return false;
    }

    private void stopVoiceRecognition() {
        if (speechRecognizer != null) {
            try { speechRecognizer.cancel(); } catch (Exception ignored) { }
            try { speechRecognizer.destroy(); } catch (Exception ignored) { }
            speechRecognizer = null;
        }
        voiceListening = false;
    }

    private File recordingFile(boolean emergency) {
        return new File(getFilesDir(), emergency ? "emergency.m4a" : "neutral.m4a");
    }

    private void playScenario(boolean emergency) {
        if (scenarioPlayed) return;
        scenarioPlayed = true;
        stopVoiceRecognition();

        File f = recordingFile(emergency);
        if (f.exists() && f.length() > 1000) {
            try {
                player = new MediaPlayer();
                player.setDataSource(f.getAbsolutePath());
                player.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                player.setOnCompletionListener(mp -> {
                    try { mp.release(); } catch (Exception ignored) { }
                    player = null;
                });
                player.prepare();
                player.start();
                return;
            } catch (Exception ignored) { }
        }

        if (tts != null) {
            String phrase = emergency
                    ? "Слушай, приезжай скорее. У тебя дома вода, похоже соседи сверху затопили квартиру. Ты можешь сейчас приехать?"
                    : "Привет! Как дела? Я хотела тебя кое о чём спросить.";
            tts.setPitch(emergency ? 1.08f : 1.0f);
            tts.setSpeechRate(emergency ? 1.08f : 0.96f);
            tts.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "scenario");
        }
    }

    private void performSos() {
        Toast.makeText(this, "SOS", Toast.LENGTH_SHORT).show();

        String primary = prefs.getString("sos_primary", "").trim();
        String recipients = prefs.getString("sos_recipients", "").trim();

        String locationText = lastKnownLocationText();
        String message = "SOS. Мне нужна помощь. " + locationText;

        if (checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            SmsManager sm = SmsManager.getDefault();
            for (String raw : recipients.split(",")) {
                String number = raw.trim();
                if (number.isEmpty()) continue;
                try {
                    ArrayList<String> parts = sm.divideMessage(message);
                    sm.sendMultipartTextMessage(number, null, parts, null, null);
                } catch (Exception ignored) { }
            }
        }

        if (!primary.isEmpty() &&
                checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            try {
                Intent call = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(primary)));
                startActivity(call);
            } catch (Exception ignored) { }
        }
    }

    private String lastKnownLocationText() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return "Геолокация недоступна.";
        }

        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location best = null;
            for (String provider : lm.getProviders(true)) {
                Location loc = lm.getLastKnownLocation(provider);
                if (loc != null && (best == null || loc.getTime() > best.getTime())) best = loc;
            }
            if (best != null) {
                return "Моё местоположение: https://maps.google.com/?q=" +
                        best.getLatitude() + "," + best.getLongitude();
            }
        } catch (Exception ignored) { }
        return "Геолокация пока не определилась.";
    }

    private void endCall() {
        answered = false;
        volumeUpDown = false;
        handler.removeCallbacksAndMessages(null);
        stopVoiceRecognition();
        stopRinging();

        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        if (tts != null) {
            try { tts.stop(); } catch (Exception ignored) { }
        }

        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            am.setSpeakerphoneOn(false);
            am.setMode(AudioManager.MODE_NORMAL);
        } catch (Exception ignored) { }

        cancelNotification();
        finishAndRemoveTask();
    }

    private void cancelNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.cancel(3001);
    }

    @Override
    protected void onDestroy() {
        answered = false;
        handler.removeCallbacksAndMessages(null);
        stopVoiceRecognition();
        stopRinging();
        if (player != null) {
            try { player.release(); } catch (Exception ignored) { }
        }
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) { }
        }
        super.onDestroy();
    }
}

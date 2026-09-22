package com.openai.pozvonimne;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class IncomingCallActivity extends Activity {
    private Ringtone ringtone;
    private Vibrator vibrator;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean answered = false;
    private boolean speakerOn = false;
    private boolean micMuted = false;

    private String caller;
    private String script;
    private List<String> lines = new ArrayList<>();
    private int lineIndex = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView timerText;
    private long callStartedAt;

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

        caller = getIntent().getStringExtra(MainActivity.EXTRA_CALLER);
        script = getIntent().getStringExtra(MainActivity.EXTRA_SCRIPT);
        if (caller == null || caller.trim().isEmpty()) caller = "Анна";
        if (script == null) script = "";

        for (String line : script.split("\\r?\\n")) {
            String clean = line.trim();
            if (!clean.isEmpty()) lines.add(clean);
        }

        initTts();
        buildIncomingUi();
        cancelNotification();
        startRinging();
    }

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(new Locale("ru", "RU"));
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.getDefault());
                }
                tts.setSpeechRate(0.93f);
                tts.setPitch(1.02f);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    tts.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build());
                }
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) { }
                    @Override public void onError(String utteranceId) {
                        scheduleNextLine();
                    }
                    @Override public void onDone(String utteranceId) {
                        scheduleNextLine();
                    }
                });
                ttsReady = true;
                if (answered) handler.postDelayed(this::speakNextLine, 1800);
            }
        });
    }

    private void buildIncomingUi() {
        LinearLayout root = baseRoot();

        TextView small = text("Входящий вызов", 17, Color.LTGRAY);
        root.addView(small, wrap(0, dp(28)));

        TextView avatar = text(caller.substring(0, 1).toUpperCase(Locale.getDefault()), 40, Color.WHITE);
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable avatarBg = new GradientDrawable();
        avatarBg.setShape(GradientDrawable.OVAL);
        avatarBg.setColor(Color.rgb(75, 82, 96));
        avatar.setBackground(avatarBg);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(dp(112), dp(112));
        avatarLp.gravity = Gravity.CENTER_HORIZONTAL;
        avatarLp.setMargins(0, 0, 0, dp(22));
        root.addView(avatar, avatarLp);

        TextView name = text(caller, 35, Color.WHITE);
        name.setGravity(Gravity.CENTER);
        root.addView(name, wrap(0, dp(6)));

        TextView mobile = text("Мобильный", 16, Color.LTGRAY);
        mobile.setGravity(Gravity.CENTER);
        root.addView(mobile, wrap(0, dp(64)));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);

        Button decline = roundButton("✕", Color.rgb(205, 55, 55));
        decline.setOnClickListener(v -> endCall());
        buttons.addView(decline, square(dp(78), dp(24)));

        Space spacer = new Space(this);
        buttons.addView(spacer, new LinearLayout.LayoutParams(dp(82), 1));

        Button answer = roundButton("☎", Color.rgb(38, 174, 88));
        answer.setOnClickListener(v -> answerCall());
        buttons.addView(answer, square(dp(78), dp(24)));

        root.addView(buttons, matchWrap(0, dp(10)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.HORIZONTAL);
        labels.setGravity(Gravity.CENTER);

        TextView declineLabel = text("Отклонить", 14, Color.LTGRAY);
        declineLabel.setGravity(Gravity.CENTER);
        labels.addView(declineLabel, new LinearLayout.LayoutParams(dp(120), dp(32)));

        TextView answerLabel = text("Ответить", 14, Color.LTGRAY);
        answerLabel.setGravity(Gravity.CENTER);
        labels.addView(answerLabel, new LinearLayout.LayoutParams(dp(120), dp(32)));

        root.addView(labels);
        setContentView(root);
    }

    private void buildConnectedUi() {
        LinearLayout root = baseRoot();

        TextView name = text(caller, 32, Color.WHITE);
        name.setGravity(Gravity.CENTER);
        root.addView(name, wrap(dp(8), dp(8)));

        timerText = text("00:00", 18, Color.LTGRAY);
        timerText.setGravity(Gravity.CENTER);
        root.addView(timerText, wrap(0, dp(56)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        Button mute = smallCallButton("Микрофон");
        mute.setOnClickListener(v -> {
            micMuted = !micMuted;
            mute.setAlpha(micMuted ? 0.45f : 1f);
        });
        row.addView(mute, square(dp(105), dp(10)));

        Button speaker = smallCallButton("Динамик");
        speaker.setOnClickListener(v -> {
            speakerOn = !speakerOn;
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            am.setSpeakerphoneOn(speakerOn);
            speaker.setAlpha(speakerOn ? 1f : 0.55f);
        });
        speaker.setAlpha(0.55f);
        row.addView(speaker, square(dp(105), dp(10)));

        root.addView(row, matchWrap(0, dp(48)));

        Button end = roundButton("✕", Color.rgb(205, 55, 55));
        end.setOnClickListener(v -> endCall());
        LinearLayout.LayoutParams endLp = new LinearLayout.LayoutParams(dp(82), dp(82));
        endLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(end, endLp);

        TextView endLabel = text("Завершить", 14, Color.LTGRAY);
        endLabel.setGravity(Gravity.CENTER);
        root.addView(endLabel, matchWrap(dp(8), 0));

        setContentView(root);
    }

    private LinearLayout baseRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(26), dp(64), dp(26), dp(36));
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
        b.setTextSize(26);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, 0, 0, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        b.setBackground(bg);
        return b;
    }

    private Button smallCallButton(String value) {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && ringtone != null) {
                ringtone.setLooping(true);
            }
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
                long[] pattern = {0, 650, 350, 650, 1200};
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
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
        callStartedAt = System.currentTimeMillis();
        buildConnectedUi();
        handler.post(timerRunnable);
        if (ttsReady && !lines.isEmpty()) {
            handler.postDelayed(this::speakNextLine, 1800);
        }
    }

    private void speakNextLine() {
        if (!answered || !ttsReady || lineIndex >= lines.size()) return;
        String line = lines.get(lineIndex++);
        tts.speak(line, TextToSpeech.QUEUE_FLUSH, null, "line_" + lineIndex);
    }

    private void scheduleNextLine() {
        if (!answered) return;
        handler.postDelayed(this::speakNextLine, 4200);
    }

    private void endCall() {
        answered = false;
        handler.removeCallbacksAndMessages(null);
        stopRinging();
        if (tts != null) {
            try { tts.stop(); } catch (Exception ignored) { }
        }
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        try {
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
        stopRinging();
        answered = false;
        handler.removeCallbacksAndMessages(null);
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) { }
        }
        super.onDestroy();
    }
}

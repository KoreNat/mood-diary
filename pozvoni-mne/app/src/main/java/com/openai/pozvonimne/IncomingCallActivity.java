package com.openai.pozvonimne;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Color;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class IncomingCallActivity extends Activity {
    private Ringtone ringtone;
    private Vibrator vibrator;
    private TextView callState;
    private Button answerButton;

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

        String caller = getIntent().getStringExtra(MainActivity.EXTRA_CALLER);
        if (caller == null || caller.trim().isEmpty()) caller = "Напоминание";
        buildUi(caller);
        cancelNotification();
        startRinging();
    }

    private void buildUi(String caller) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(26), dp(60), dp(26), dp(40));
        root.setBackgroundColor(Color.rgb(22, 27, 34));

        callState = new TextView(this);
        callState.setText("входящий звонок");
        callState.setTextColor(Color.LTGRAY);
        callState.setTextSize(18);
        callState.setGravity(Gravity.CENTER);
        root.addView(callState, wrap(dp(8), dp(14)));

        TextView callerText = new TextView(this);
        callerText.setText(caller);
        callerText.setTextColor(Color.WHITE);
        callerText.setTextSize(34);
        callerText.setGravity(Gravity.CENTER);
        root.addView(callerText, wrap(dp(10), dp(60)));

        Button declineButton = new Button(this);
        declineButton.setText("Отклонить");
        declineButton.setOnClickListener(v -> endCall());
        root.addView(declineButton, match(dp(0), dp(14)));

        answerButton = new Button(this);
        answerButton.setText("Ответить");
        answerButton.setOnClickListener(v -> answerCall());
        root.addView(answerButton, match(dp(0), dp(0)));

        setContentView(root);
    }

    private LinearLayout.LayoutParams match(int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, top, 0, bottom);
        return lp;
    }

    private LinearLayout.LayoutParams wrap(int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
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
                long[] pattern = {0, 700, 350, 700, 1200};
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
        callState.setText("соединено");
        answerButton.setText("Завершить");
        answerButton.setOnClickListener(v -> endCall());
    }

    private void endCall() {
        stopRinging();
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
        super.onDestroy();
    }
}

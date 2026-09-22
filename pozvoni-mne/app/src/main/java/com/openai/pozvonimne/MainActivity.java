package com.openai.pozvonimne;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    public static final String CHANNEL_ID = "self_call_channel";
    public static final String EXTRA_CALLER = "caller_name";
    private EditText callerName;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createNotificationChannel();
        buildUi();
        requestNotificationPermissionIfNeeded();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(42), dp(24), dp(24));
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("Позвони мне");
        title.setTextSize(30);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap(dp(0), dp(18)));

        TextView hint = new TextView(this);
        hint.setText("Телефон сам покажет локальный входящий звонок.");
        hint.setTextSize(16);
        hint.setTextColor(Color.DKGRAY);
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, matchWrap(dp(0), dp(26)));

        callerName = new EditText(this);
        callerName.setHint("Имя звонящего");
        callerName.setText("Напоминание");
        callerName.setSingleLine(true);
        root.addView(callerName, matchWrap(dp(0), dp(18)));

        Button now = new Button(this);
        now.setText("Позвонить сейчас");
        now.setOnClickListener(v -> startCallNow());
        root.addView(now, matchWrap(dp(0), dp(10)));

        Button later = new Button(this);
        later.setText("Позвонить через 10 секунд");
        later.setOnClickListener(v -> scheduleCallInTenSeconds());
        root.addView(later, matchWrap(dp(0), dp(10)));

        Button permissions = new Button(this);
        permissions.setText("Проверить разрешения");
        permissions.setOnClickListener(v -> openNeededPermissions());
        root.addView(permissions, matchWrap(dp(0), dp(18)));

        statusText = new TextView(this);
        statusText.setText("Для первого теста нажми «Позвонить сейчас».");
        statusText.setTextSize(15);
        statusText.setTextColor(Color.DKGRAY);
        statusText.setGravity(Gravity.CENTER);
        root.addView(statusText, matchWrap(dp(0), dp(0)));

        setContentView(root);
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

    private String getCaller() {
        String value = callerName.getText().toString().trim();
        return value.isEmpty() ? "Напоминание" : value;
    }

    private void startCallNow() {
        Intent intent = new Intent(this, IncomingCallActivity.class);
        intent.putExtra(EXTRA_CALLER, getCaller());
        startActivity(intent);
    }

    private void scheduleCallInTenSeconds() {
        AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            statusText.setText("Разреши «Будильники и напоминания», затем вернись и нажми кнопку снова.");
            try {
                Intent permissionIntent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                permissionIntent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(permissionIntent);
            } catch (Exception e) {
                Toast.makeText(this, "Открой настройки приложения и разреши точные будильники", Toast.LENGTH_LONG).show();
            }
            return;
        }

        Intent receiverIntent = new Intent(this, AlarmReceiver.class);
        receiverIntent.putExtra(EXTRA_CALLER, getCaller());
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this,
                1001,
                receiverIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        long triggerAt = System.currentTimeMillis() + 10_000L;
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
        statusText.setText("Назначено. Заблокируй экран — примерно через 10 секунд должен появиться звонок.");
        Toast.makeText(this, "Звонок назначен", Toast.LENGTH_SHORT).show();
    }

    private void openNeededPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (!nm.canUseFullScreenIntent()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                    return;
                } catch (Exception ignored) { }
            }
        }

        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                return;
            } catch (Exception ignored) { }
        }

        Toast.makeText(this, "Основные специальные разрешения уже включены", Toast.LENGTH_SHORT).show();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 44);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Локальный звонок",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Звонок, созданный самим телефоном");
            channel.enableVibration(true);
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build();
            channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), attributes);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            manager.createNotificationChannel(channel);
        }
    }
}

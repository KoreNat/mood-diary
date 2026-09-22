package com.openai.pozvonimne;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TimePickerDialog;
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
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

public class MainActivity extends Activity {
    public static final String CHANNEL_ID = "self_call_channel";
    public static final String EXTRA_CALLER = "caller_name";
    public static final String EXTRA_SCRIPT = "conversation_script";

    private EditText callerName;
    private EditText scriptText;
    private TextView selectedTimeText;
    private TextView statusText;
    private int selectedHour;
    private int selectedMinute;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Calendar now = Calendar.getInstance();
        now.add(Calendar.MINUTE, 2);
        selectedHour = now.get(Calendar.HOUR_OF_DAY);
        selectedMinute = now.get(Calendar.MINUTE);

        createNotificationChannel();
        buildUi();
        requestNotificationPermissionIfNeeded();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(36), dp(22), dp(32));
        root.setBackgroundColor(Color.WHITE);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Позвони мне");
        title.setTextSize(30);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap(0, dp(6)));

        TextView subtitle = new TextView(this);
        subtitle.setText("Настрой входящий звонок");
        subtitle.setTextSize(16);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle, matchWrap(0, dp(24)));

        TextView nameLabel = label("Кто звонит");
        root.addView(nameLabel);

        callerName = new EditText(this);
        callerName.setHint("Например: Анна");
        callerName.setText("Анна");
        callerName.setSingleLine(true);
        root.addView(callerName, matchWrap(0, dp(18)));

        TextView timeLabel = label("Во сколько позвонить");
        root.addView(timeLabel);

        Button timeButton = new Button(this);
        timeButton.setText("Выбрать время");
        timeButton.setOnClickListener(v -> showTimePicker());
        root.addView(timeButton, matchWrap(0, dp(8)));

        selectedTimeText = new TextView(this);
        selectedTimeText.setTextSize(22);
        selectedTimeText.setTextColor(Color.BLACK);
        selectedTimeText.setGravity(Gravity.CENTER);
        updateSelectedTimeText();
        root.addView(selectedTimeText, matchWrap(0, dp(20)));

        TextView scriptLabel = label("Фразы собеседника");
        root.addView(scriptLabel);

        TextView scriptHint = new TextView(this);
        scriptHint.setText("По одной фразе в строке. После ответа телефон будет произносить их с паузами, чтобы ты могла отвечать.");
        scriptHint.setTextSize(14);
        scriptHint.setTextColor(Color.DKGRAY);
        root.addView(scriptHint, matchWrap(0, dp(8)));

        scriptText = new EditText(this);
        scriptText.setMinLines(6);
        scriptText.setGravity(Gravity.TOP);
        scriptText.setText(
                "Привет, ты можешь сейчас говорить?\n" +
                "Да, поняла. Я как раз хотела тебе об этом сказать.\n" +
                "Хорошо, тогда давай сделаем так.\n" +
                "Ладно, договорились. Я тебе потом напишу.\n" +
                "Хорошо, пока."
        );
        root.addView(scriptText, matchWrap(0, dp(18)));

        Button schedule = new Button(this);
        schedule.setText("Назначить звонок");
        schedule.setTextSize(17);
        schedule.setOnClickListener(v -> scheduleAtSelectedTime());
        root.addView(schedule, matchWrap(0, dp(10)));

        Button test = new Button(this);
        test.setText("Тест: позвонить сейчас");
        test.setOnClickListener(v -> startCallNow());
        root.addView(test, matchWrap(0, dp(10)));

        Button permissions = new Button(this);
        permissions.setText("Проверить разрешения");
        permissions.setOnClickListener(v -> openNeededPermissions());
        root.addView(permissions, matchWrap(0, dp(18)));

        statusText = new TextView(this);
        statusText.setText("Выбери время и нажми «Назначить звонок».");
        statusText.setTextSize(15);
        statusText.setTextColor(Color.DKGRAY);
        statusText.setGravity(Gravity.CENTER);
        root.addView(statusText);

        setContentView(scroll);
    }

    private TextView label(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(17);
        v.setTextColor(Color.BLACK);
        v.setPadding(0, dp(4), 0, dp(6));
        return v;
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

    private void showTimePicker() {
        TimePickerDialog dialog = new TimePickerDialog(
                this,
                (view, hourOfDay, minute) -> {
                    selectedHour = hourOfDay;
                    selectedMinute = minute;
                    updateSelectedTimeText();
                },
                selectedHour,
                selectedMinute,
                true
        );
        dialog.show();
    }

    private void updateSelectedTimeText() {
        selectedTimeText.setText(String.format(Locale.getDefault(), "%02d:%02d", selectedHour, selectedMinute));
    }

    private String getCallerName() {
        String value = callerName.getText().toString().trim();
        return value.isEmpty() ? "Анна" : value;
    }

    private String getScript() {
        String value = scriptText.getText().toString().trim();
        if (value.isEmpty()) {
            return "Привет, ты можешь сейчас говорить?\nХорошо.\nЛадно, тогда созвонимся позже. Пока.";
        }
        return value;
    }

    private void startCallNow() {
        Intent intent = new Intent(this, IncomingCallActivity.class);
        intent.putExtra(EXTRA_CALLER, getCallerName());
        intent.putExtra(EXTRA_SCRIPT, getScript());
        startActivity(intent);
    }

    private void scheduleAtSelectedTime() {
        AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            statusText.setText("Сначала разреши «Будильники и напоминания», затем вернись сюда.");
            try {
                Intent permissionIntent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                permissionIntent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(permissionIntent);
            } catch (Exception e) {
                Toast.makeText(this, "Разреши точные будильники в настройках приложения", Toast.LENGTH_LONG).show();
            }
            return;
        }

        Calendar trigger = Calendar.getInstance();
        trigger.set(Calendar.HOUR_OF_DAY, selectedHour);
        trigger.set(Calendar.MINUTE, selectedMinute);
        trigger.set(Calendar.SECOND, 0);
        trigger.set(Calendar.MILLISECOND, 0);

        if (trigger.getTimeInMillis() <= System.currentTimeMillis()) {
            trigger.add(Calendar.DAY_OF_YEAR, 1);
        }

        Intent receiverIntent = new Intent(this, AlarmReceiver.class);
        receiverIntent.putExtra(EXTRA_CALLER, getCallerName());
        receiverIntent.putExtra(EXTRA_SCRIPT, getScript());

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this,
                1001,
                receiverIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                trigger.getTimeInMillis(),
                pendingIntent
        );

        SimpleDateFormat fmt = new SimpleDateFormat("EEE, d MMM, HH:mm", Locale.getDefault());
        statusText.setText("Звонок назначен на " + fmt.format(trigger.getTime()));
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
                    "Входящие звонки",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Запланированный локальный входящий звонок");
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

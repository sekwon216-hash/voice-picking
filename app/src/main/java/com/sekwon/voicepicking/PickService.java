package com.sekwon.voicepicking;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Keeps picking alive with the screen off: holds a media session so the earbud
 * buttons come here, plays silent audio so the phone routes those buttons to this app,
 * and shows the current item on the lock screen.
 */
public class PickService extends Service implements Store.Listener {
    public static final String ACTION_START = "start";
    public static final String ACTION_NEXT = "next";
    public static final String ACTION_PREV = "prev";
    public static final String ACTION_REPEAT = "repeat";
    public static final String ACTION_STOP = "stop";
    public static final String ACTION_PAUSE = "pause";

    private static final String CHANNEL = "picking";
    private static final int NOTE_ID = 7;

    private MediaSession session;
    private AudioTrack track;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private PowerManager.WakeLock wakeLock;
    private Store store;
    private long lastPress = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        store = Store.get(this);
        createChannel();
        startForegroundCompat(buildNotification());

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(change -> {
                    if (change == AudioManager.AUDIOFOCUS_LOSS) store.setEvent("다른 앱이 소리를 가져감 · 음악 앱을 꺼 주세요");
                })
                .build();
        audioManager.requestAudioFocus(focusRequest);

        startSilence(attrs);

        session = new MediaSession(this, "VoicePicking");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { press("재생", ACTION_NEXT); }
            @Override public void onPause() { press("일시정지", ACTION_NEXT); }
            @Override public void onStop() { press("정지", ACTION_NEXT); }
            @Override public void onSkipToNext() { press("다음곡(두 번)", "dbl"); }
            @Override public void onSkipToPrevious() { press("이전곡(세 번)", ACTION_PREV); }
            @Override public boolean onMediaButtonEvent(Intent intent) {
                KeyEvent ev = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (ev != null && ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
                    switch (ev.getKeyCode()) {
                        case KeyEvent.KEYCODE_MEDIA_NEXT: press("다음곡(두 번)", "dbl"); return true;
                        case KeyEvent.KEYCODE_MEDIA_PREVIOUS: press("이전곡(세 번)", ACTION_PREV); return true;
                        default: break;
                    }
                }
                return super.onMediaButtonEvent(intent);
            }
        });
        setPlaying();
        session.setActive(true);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voicepicking:session");
        wakeLock.acquire(12 * 60 * 60 * 1000L);

        store.setSession(true);
        store.addListener(this);
        refresh();
        store.startAnnounce();
    }

    private void startSilence(AudioAttributes attrs) {
        try {
            int rate = 8000;
            short[] silence = new short[rate];
            track = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(silence.length * 2)
                    .build();
            track.write(silence, 0, silence.length);
            track.setLoopPoints(0, silence.length, -1);
            track.play();
        } catch (Exception e) {
            track = null;
        }
    }

    private void press(String label, String action) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastPress < 600) return;
        lastPress = now;
        store.setEvent(label + " · " + new SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(new Date()));
        if ("dbl".equals(action)) action = "next".equals(store.dbl()) ? ACTION_NEXT : ACTION_REPEAT;
        handle(action);
        setPlaying();
    }

    private void handle(String action) {
        if (ACTION_NEXT.equals(action)) store.next();
        else if (ACTION_PREV.equals(action)) store.prev();
        else if (ACTION_REPEAT.equals(action)) { store.repeat(); refresh(); }
    }

    private void setPlaying() {
        if (session == null) return;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP)
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(a)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(a)) {
            Speaker.get(this).say("작업을 일시중단합니다. 지금 위치는 저장됐습니다.");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (a != null && !ACTION_START.equals(a)) {
            store.setEvent("알림창 버튼");
            handle(a);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void changed() { refresh(); }

    private void refresh() {
        Store.Step s = store.current();
        if (session != null) {
            MediaMetadata.Builder m = new MediaMetadata.Builder();
            if (s == null) {
                m.putString(MediaMetadata.METADATA_KEY_TITLE, "피킹 완료");
                m.putString(MediaMetadata.METADATA_KEY_ARTIST, "보이스 피킹");
            } else {
                m.putString(MediaMetadata.METADATA_KEY_TITLE, s.qty + "박스 · " + store.info(s.code).optString("name"));
                m.putString(MediaMetadata.METADATA_KEY_ARTIST, s.dest);
            }
            m.putLong(MediaMetadata.METADATA_KEY_DURATION, -1);
            session.setMetadata(m.build());
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTE_ID, buildNotification());
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "피킹 안내", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    private PendingIntent servicePI(String action, int code) {
        Intent i = new Intent(this, PickService.class).setAction(action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildNotification() {
        Store.Step s = store == null ? null : store.current();
        String title = s == null ? "피킹 완료" : s.qty + "박스 · " + store.info(s.code).optString("name");
        String text = s == null ? "보이스 피킹" : s.dest;
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPI = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openPI)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_prev), "이전", servicePI(ACTION_PREV, 1)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_replay), "다시", servicePI(ACTION_REPEAT, 2)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_next), "완료·다음", servicePI(ACTION_NEXT, 3)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_pause), "일시중단", servicePI(ACTION_PAUSE, 4)).build());
        Notification.MediaStyle style = new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2);
        if (session != null) style.setMediaSession(session.getSessionToken());
        b.setStyle(style);
        return b.build();
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTE_ID, n);
        }
    }

    @Override
    public void onDestroy() {
        store.removeListener(this);
        store.setSession(false);
        store.setEvent("");
        if (session != null) { session.setActive(false); session.release(); session = null; }
        if (track != null) { try { track.stop(); } catch (Exception ignored) { } track.release(); track = null; }
        if (audioManager != null && focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
        Store.get(this).nudge();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}

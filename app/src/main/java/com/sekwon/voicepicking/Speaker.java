package com.sekwon.voicepicking;

import android.content.Context;
import android.media.AudioAttributes;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

/** Korean text-to-speech shared by the screen and the background service. */
public final class Speaker {
    private static Speaker instance;

    public static synchronized Speaker get(Context c) {
        if (instance == null) instance = new Speaker(c.getApplicationContext());
        return instance;
    }

    private TextToSpeech tts;
    private boolean ready = false;
    private String pending = null;
    private float rate = 1.1f;
    private String problem = null;

    private Speaker(Context c) {
        tts = new TextToSpeech(c, status -> {
            synchronized (Speaker.this) {
                if (status != TextToSpeech.SUCCESS) {
                    problem = "음성 엔진을 시작하지 못했습니다";
                    return;
                }
                int r = tts.setLanguage(Locale.KOREAN);
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    problem = "휴대폰에 한국어 음성이 없습니다. 설정 → 일반 → 텍스트 음성 변환에서 한국어를 받아 주세요";
                }
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                tts.setSpeechRate(rate);
                ready = true;
                if (pending != null) {
                    tts.speak(pending, TextToSpeech.QUEUE_FLUSH, null, "vp");
                    pending = null;
                }
            }
        });
    }

    public synchronized void say(String text) {
        if (text == null || text.isEmpty()) return;
        if (!ready) { pending = text; return; }
        tts.setSpeechRate(rate);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vp");
    }

    public synchronized void stop() {
        if (ready) tts.stop();
    }

    public synchronized void setRate(float r) { rate = r; }

    public synchronized String problem() { return problem; }
}

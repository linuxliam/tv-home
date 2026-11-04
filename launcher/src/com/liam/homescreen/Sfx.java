package com.liam.homescreen;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Tiny synthesised menu sounds (move / select / back), like RetroArch's menu sounds. */
final class Sfx {
    private final AudioTrack move = tone(1250, 32, 0.22f);
    private final AudioTrack ok = tone(1700, 80, 0.26f);
    private final AudioTrack back = tone(820, 70, 0.24f);

    private static AudioTrack tone(int hz, int ms, float gain) {
        int rate = 22050;
        int n = rate * ms / 1000;
        short[] pcm = new short[n];
        for (int i = 0; i < n; i++) {
            double env = Math.exp(-5.0 * i / n);
            pcm[i] = (short) (Math.sin(2 * Math.PI * hz * i / rate) * env * gain * Short.MAX_VALUE);
        }
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.length * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        t.write(pcm, 0, pcm.length);
        return t;
    }

    private static void play(AudioTrack t) {
        try {
            t.stop();
            t.setPlaybackHeadPosition(0);
            t.play();
        } catch (IllegalStateException ignored) {
            // released
        }
    }

    void move() {
        play(move);
    }

    void ok() {
        play(ok);
    }

    void back() {
        play(back);
    }

    void release() {
        move.release();
        ok.release();
        back.release();
    }
}

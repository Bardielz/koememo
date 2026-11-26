package dev.koememo.offloadprobe;

import android.app.Activity;
import android.hardware.*;
import android.media.*;
import android.os.*;
import android.util.Log;
import java.util.Arrays;

public class Probe extends Activity implements SensorEventListener {
  static final String TAG = "KoeMemoOffload";
  SensorManager sensors;
  final Handler main = new Handler(Looper.getMainLooper());
  boolean received;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    sensors = getSystemService(SensorManager.class);
    Sensor sensor = sensors.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT);
    boolean registered =
        sensor != null && sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
    Log.i(TAG, "offbody registered=" + registered);
    new Thread(
            () -> {
              try {
                for (String mime : Arrays.asList("audio/mp4a-latm", "audio/opus")) {
                  for (MediaCodecInfo codec :
                      new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                    if (!codec.isEncoder()
                        || codec.isAlias()
                        || !Arrays.asList(codec.getSupportedTypes()).contains(mime)) continue;
                    Log.i(
                        TAG,
                        "encoder="
                            + codec.getName()
                            + " mime="
                            + mime
                            + " hardware="
                            + codec.isHardwareAccelerated());
                  }
                }
                for (int encoding :
                    new int[] {AudioFormat.ENCODING_AAC_LC, AudioFormat.ENCODING_OPUS}) {
                  for (int rate : new int[] {16000, 24000, 48000}) {
                    AudioFormat format =
                        new AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build();
                    AudioAttributes attributes =
                        new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build();
                    boolean supported =
                        AudioManager.isOffloadedPlaybackSupported(format, attributes);
                    Log.i(
                        TAG,
                        "playback encoding="
                            + encoding
                            + " rate="
                            + rate
                            + " supported="
                            + supported);
                    if (supported) {
                      AudioTrack track = null;
                      try {
                        track =
                            new AudioTrack.Builder()
                                .setAudioAttributes(attributes)
                                .setAudioFormat(format)
                                .setTransferMode(AudioTrack.MODE_STREAM)
                                .setBufferSizeInBytes(1024)
                                .setOffloadedPlayback(true)
                                .build();
                        Log.i(TAG, "playback allocated_offloaded=" + track.isOffloadedPlayback());
                        if (rate == 16000
                            && encoding == AudioFormat.ENCODING_AAC_LC
                            && track.isOffloadedPlayback()) {
                          playFixture(track);
                        }
                      } catch (RuntimeException unavailable) {
                        Log.w(TAG, "offload allocation failed", unavailable);
                      } finally {
                        if (track != null) track.release();
                      }
                    }
                  }
                }
                GpuLevel.run();
              } catch (Exception e) {
                Log.e(TAG, "probe failed", e);
              } finally {
                main.postDelayed(
                    () -> {
                      Log.i(TAG, "finished offbody_event=" + received);
                      finish();
                    },
                    2000);
              }
            },
            "offload-test")
        .start();
  }

  private void playFixture(AudioTrack track) throws java.io.IOException {
    // One ADTS frame from a synthetic mono 16 kHz AAC-LC silence encode.
    byte[] frame = {
      (byte) 0xff, (byte) 0xf9, 0x60, 0x60, 0x01, 0x60, 0x00, 0x00, (byte) 0xd0, 0x00, 0x07
    };
    byte[] adts = new byte[frame.length * 8192];
    for (int i = 0; i < adts.length; i += frame.length)
      System.arraycopy(frame, 0, adts, i, frame.length);
    track.setVolume(0f);
    track.play();
    long first = Integer.toUnsignedLong(track.getPlaybackHeadPosition());
    int written = 0;
    long until = SystemClock.elapsedRealtime() + 3500;
    while (SystemClock.elapsedRealtime() < until) {
      if (written < adts.length) {
        int n = track.write(adts, written, adts.length - written, AudioTrack.WRITE_NON_BLOCKING);
        if (n < 0) throw new java.io.IOException("AudioTrack.write returned " + n);
        written += n;
      }
      SystemClock.sleep(50);
    }
    long last = Integer.toUnsignedLong(track.getPlaybackHeadPosition());
    AudioTimestamp timestamp = new AudioTimestamp();
    boolean valid = track.getTimestamp(timestamp);
    track.pause();
    track.flush();
    Log.i(
        TAG,
        "aac_offload bytes="
            + written
            + " head_start="
            + first
            + " head_end="
            + last
            + " timestamp_valid="
            + valid
            + " frame_position="
            + timestamp.framePosition);
  }

  @Override
  public void onSensorChanged(SensorEvent e) {
    received = true;
    Log.i(
        TAG,
        "offbody value="
            + e.values[0]
            + " wakeup="
            + e.sensor.isWakeUpSensor()
            + " fifo="
            + e.sensor.getFifoMaxEventCount());
  }

  @Override
  public void onAccuracyChanged(Sensor sensor, int accuracy) {}

  @Override
  public void onDestroy() {
    sensors.unregisterListener(this);
    main.removeCallbacksAndMessages(null);
    super.onDestroy();
  }
}

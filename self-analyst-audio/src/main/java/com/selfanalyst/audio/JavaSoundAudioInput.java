package com.selfanalyst.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class JavaSoundAudioInput implements AudioInput {
    private final AudioFormat format;
    private final int chunkSeconds;
    private final float vadThreshold;
    private final String source;
    private final TargetDataLine line;
    private final String deviceName;

    static AudioInput open(int chunkSeconds, float vadThreshold, String source) {
        String normalized = "system".equals(source) ? "system" : "mic";
        AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
        OpenedLine opened = openLine(format, normalized);
        return new JavaSoundAudioInput(format, chunkSeconds, vadThreshold, normalized,
                opened.line(), opened.deviceName());
    }

    private JavaSoundAudioInput(AudioFormat format, int chunkSeconds, float vadThreshold,
                                String source, TargetDataLine line, String deviceName) {
        this.format = format;
        this.chunkSeconds = chunkSeconds;
        this.vadThreshold = vadThreshold;
        this.source = source;
        this.line = line;
        this.deviceName = deviceName;
    }

    @Override
    public boolean isAvailable() {
        return line != null && line.isOpen();
    }

    @Override
    public AudioCapturer.CaptureResult captureResult() {
        if (!isAvailable()) return null;
        try {
            int bufferSize = (int) format.getFrameSize() * (int) format.getFrameRate() * chunkSeconds;
            byte[] buffer = new byte[bufferSize];
            int total = 0;
            while (total < bufferSize) {
                int n = line.read(buffer, total, bufferSize - total);
                if (n <= 0) break;
                total += n;
            }
            byte[] pcm = new byte[total];
            System.arraycopy(buffer, 0, pcm, 0, total);

            float rms = PcmAudio.rms(pcm);
            if (rms < vadThreshold) {
                return new AudioCapturer.CaptureResult(null, false, rms, source, deviceName);
            }
            return new AudioCapturer.CaptureResult(PcmAudio.toWav(pcm), true, rms, source, deviceName);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String source() {
        return source;
    }

    @Override
    public String deviceName() {
        return deviceName;
    }

    @Override
    public void close() {
        if (line != null) {
            line.stop();
            line.close();
        }
    }

    static List<String> availableInputDevices() {
        AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
        DataLine.Info lineInfo = new DataLine.Info(TargetDataLine.class, format);
        List<String> devices = new ArrayList<>();
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            try {
                Mixer mixer = AudioSystem.getMixer(mixerInfo);
                if (mixer.isLineSupported(lineInfo)) {
                    devices.add(mixerInfo.getName() + " - " + mixerInfo.getDescription());
                }
            } catch (Exception ignored) {
            }
        }
        return devices;
    }

    static boolean systemMixerLooksLikeLoopback(String name, String description) {
        String text = ((name == null ? "" : name) + " " +
                (description == null ? "" : description)).toLowerCase(Locale.ROOT);
        return text.contains("stereo mix")
                || text.contains("what u hear")
                || text.contains("wave out")
                || text.contains("loopback")
                || text.contains("立体声混音")
                || text.contains("系统声音")
                || text.contains("扬声器");
    }

    private static OpenedLine openLine(AudioFormat format, String source) {
        DataLine.Info lineInfo = new DataLine.Info(TargetDataLine.class, format);
        if ("system".equals(source)) {
            for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                if (!systemMixerLooksLikeLoopback(mixerInfo.getName(), mixerInfo.getDescription())) {
                    continue;
                }
                try {
                    Mixer mixer = AudioSystem.getMixer(mixerInfo);
                    if (!mixer.isLineSupported(lineInfo)) continue;
                    TargetDataLine line = (TargetDataLine) mixer.getLine(lineInfo);
                    line.open(format);
                    line.start();
                    return new OpenedLine(line, mixerInfo.getName());
                } catch (Exception ignored) {
                }
            }
            return new OpenedLine(null, "");
        }
        try {
            TargetDataLine line = (TargetDataLine) AudioSystem.getLine(lineInfo);
            line.open(format);
            line.start();
            return new OpenedLine(line, "default microphone");
        } catch (Exception e) {
            return new OpenedLine(null, "");
        }
    }

    private record OpenedLine(TargetDataLine line, String deviceName) {}
}

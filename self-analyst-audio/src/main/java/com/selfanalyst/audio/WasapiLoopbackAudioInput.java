package com.selfanalyst.audio;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.COM.COMUtils;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;

final class WasapiLoopbackAudioInput implements AudioInput {
    private static final Guid.GUID CLSID_MM_DEVICE_ENUMERATOR =
            new Guid.GUID("{BCDE0395-E52F-467C-8E3D-C4579291692E}");
    private static final Guid.IID IID_IMM_DEVICE_ENUMERATOR =
            new Guid.IID("{A95664D2-9614-4F35-A746-DE8DB63617E6}");
    private static final Guid.IID IID_IAUDIO_CLIENT =
            new Guid.IID("{1CB9AD4C-DBFA-4c32-B178-C2F568A703B2}");
    private static final Guid.IID IID_IAUDIO_CAPTURE_CLIENT =
            new Guid.IID("{C8ADBD64-E71E-48a0-A4DE-185C395CD317}");

    private static final int CLSCTX_ALL = 0x17;
    private static final int E_RENDER = 0;
    private static final int E_CONSOLE = 0;
    private static final int AUDCLNT_SHAREMODE_SHARED = 0;
    private static final int AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000;
    private static final int AUDCLNT_BUFFERFLAGS_SILENT = 0x00000002;
    private static final long REFTIMES_PER_SEC = 10_000_000L;

    private final int chunkSeconds;
    private final float vadThreshold;
    private Session session;

    static AudioInput open(int chunkSeconds, float vadThreshold, String source) {
        if (!"system".equals(source) || !isWindows()) {
            return AudioInput.unavailable("system");
        }
        return new WasapiLoopbackAudioInput(chunkSeconds, vadThreshold);
    }

    private WasapiLoopbackAudioInput(int chunkSeconds, float vadThreshold) {
        this.chunkSeconds = chunkSeconds;
        this.vadThreshold = vadThreshold;
    }

    @Override
    public boolean isAvailable() {
        return ensureOpen();
    }

    @Override
    public AudioCapturer.CaptureResult captureResult() {
        if (!ensureOpen()) {
            return null;
        }
        try {
            byte[] pcm = session.capturePcm16Mono(chunkSeconds);
            float rms = PcmAudio.rms(pcm);
            if (rms < vadThreshold) {
                return new AudioCapturer.CaptureResult(null, false, rms, source(), deviceName());
            }
            return new AudioCapturer.CaptureResult(PcmAudio.toWav(pcm), true, rms, source(), deviceName());
        } catch (Exception e) {
            close();
            return null;
        }
    }

    @Override
    public String source() {
        return "system";
    }

    @Override
    public String deviceName() {
        return "WASAPI loopback";
    }

    @Override
    public void close() {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    static List<String> availableInputDevices() {
        return isWindows()
                ? List.of("WASAPI loopback - default render device")
                : List.of();
    }

    private boolean ensureOpen() {
        if (session != null) {
            return true;
        }
        try {
            session = Session.open();
            return true;
        } catch (Throwable ignored) {
            session = null;
            return false;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static final class Session implements AutoCloseable {
        private final ImmDeviceEnumerator enumerator;
        private final ImmDevice device;
        private final AudioClient audioClient;
        private final AudioCaptureClient captureClient;
        private final AudioSampleFormat format;
        private final boolean comInitialized;

        private Session(ImmDeviceEnumerator enumerator, ImmDevice device,
                        AudioClient audioClient, AudioCaptureClient captureClient,
                        AudioSampleFormat format, boolean comInitialized) {
            this.enumerator = enumerator;
            this.device = device;
            this.audioClient = audioClient;
            this.captureClient = captureClient;
            this.format = format;
            this.comInitialized = comInitialized;
        }

        static Session open() {
            boolean comInitialized = false;
            HRESULT init = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_MULTITHREADED);
            if (COMUtils.SUCCEEDED(init)) {
                comInitialized = true;
            } else if (init.intValue() != 0x80010106) {
                COMUtils.checkRC(init);
            }

            ImmDeviceEnumerator enumerator = null;
            ImmDevice device = null;
            AudioClient audioClient = null;
            AudioCaptureClient captureClient = null;
            Pointer mixFormatPointer = null;
            try {
                PointerByReference enumeratorRef = new PointerByReference();
                COMUtils.checkRC(Ole32.INSTANCE.CoCreateInstance(
                        CLSID_MM_DEVICE_ENUMERATOR,
                        Pointer.NULL,
                        CLSCTX_ALL,
                        IID_IMM_DEVICE_ENUMERATOR,
                        enumeratorRef));
                enumerator = new ImmDeviceEnumerator(enumeratorRef.getValue());

                PointerByReference deviceRef = new PointerByReference();
                COMUtils.checkRC(enumerator.getDefaultAudioEndpoint(E_RENDER, E_CONSOLE, deviceRef));
                device = new ImmDevice(deviceRef.getValue());

                PointerByReference audioClientRef = new PointerByReference();
                COMUtils.checkRC(device.activate(new Guid.REFIID(IID_IAUDIO_CLIENT),
                        CLSCTX_ALL, Pointer.NULL, audioClientRef));
                audioClient = new AudioClient(audioClientRef.getValue());

                PointerByReference formatRef = new PointerByReference();
                COMUtils.checkRC(audioClient.getMixFormat(formatRef));
                mixFormatPointer = formatRef.getValue();
                AudioSampleFormat format = parseMixFormat(mixFormatPointer);

                COMUtils.checkRC(audioClient.initialize(
                        AUDCLNT_SHAREMODE_SHARED,
                        AUDCLNT_STREAMFLAGS_LOOPBACK,
                        REFTIMES_PER_SEC,
                        0,
                        mixFormatPointer,
                        Pointer.NULL));

                PointerByReference captureClientRef = new PointerByReference();
                COMUtils.checkRC(audioClient.getService(new Guid.REFIID(IID_IAUDIO_CAPTURE_CLIENT),
                        captureClientRef));
                captureClient = new AudioCaptureClient(captureClientRef.getValue());
                COMUtils.checkRC(audioClient.start());
                return new Session(enumerator, device, audioClient, captureClient, format, comInitialized);
            } catch (RuntimeException e) {
                release(captureClient);
                release(audioClient);
                release(device);
                release(enumerator);
                if (comInitialized) {
                    Ole32.INSTANCE.CoUninitialize();
                }
                throw e;
            } finally {
                if (mixFormatPointer != null) {
                    Ole32.INSTANCE.CoTaskMemFree(mixFormatPointer);
                }
            }
        }

        byte[] capturePcm16Mono(int chunkSeconds) throws InterruptedException {
            int targetFrames = Math.max(1, format.sampleRate() * chunkSeconds);
            ByteArrayOutputStream raw = new ByteArrayOutputStream(targetFrames * format.frameSize());
            int capturedFrames = 0;
            long deadline = System.nanoTime() + (chunkSeconds + 2L) * 1_000_000_000L;
            while (capturedFrames < targetFrames && System.nanoTime() < deadline) {
                IntByReference packetFrames = new IntByReference();
                COMUtils.checkRC(captureClient.getNextPacketSize(packetFrames));
                if (packetFrames.getValue() == 0) {
                    Thread.sleep(10);
                    continue;
                }
                while (packetFrames.getValue() > 0 && capturedFrames < targetFrames) {
                    PointerByReference dataRef = new PointerByReference();
                    IntByReference framesRef = new IntByReference();
                    IntByReference flagsRef = new IntByReference();
                    COMUtils.checkRC(captureClient.getBuffer(dataRef, framesRef, flagsRef,
                            Pointer.NULL, Pointer.NULL));
                    int frames = framesRef.getValue();
                    int bytes = Math.max(0, frames * format.frameSize());
                    if ((flagsRef.getValue() & AUDCLNT_BUFFERFLAGS_SILENT) != 0 || dataRef.getValue() == null) {
                        raw.writeBytes(new byte[bytes]);
                    } else {
                        raw.writeBytes(dataRef.getValue().getByteArray(0, bytes));
                    }
                    capturedFrames += frames;
                    COMUtils.checkRC(captureClient.releaseBuffer(frames));
                    COMUtils.checkRC(captureClient.getNextPacketSize(packetFrames));
                }
            }
            if (capturedFrames < targetFrames) {
                raw.writeBytes(new byte[(targetFrames - capturedFrames) * format.frameSize()]);
            }
            return PcmAudio.toPcm16Mono(raw.toByteArray(), format);
        }

        @Override
        public void close() {
            try {
                if (audioClient != null) {
                    audioClient.stop();
                }
            } catch (Exception ignored) {
            }
            release(captureClient);
            release(audioClient);
            release(device);
            release(enumerator);
            if (comInitialized) {
                Ole32.INSTANCE.CoUninitialize();
            }
        }

        private static void release(Unknown unknown) {
            if (unknown != null) {
                try {
                    unknown.Release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static AudioSampleFormat parseMixFormat(Pointer pointer) {
        int formatTag = unsignedShort(pointer.getShort(0));
        int channels = unsignedShort(pointer.getShort(2));
        int sampleRate = pointer.getInt(4);
        int blockAlign = unsignedShort(pointer.getShort(12));
        int bitsPerSample = unsignedShort(pointer.getShort(14));
        AudioSampleFormat.Encoding encoding = switch (formatTag) {
            case 3 -> AudioSampleFormat.Encoding.IEEE_FLOAT;
            case 0xFFFE -> extensibleEncoding(pointer);
            default -> AudioSampleFormat.Encoding.PCM_SIGNED;
        };
        return new AudioSampleFormat(sampleRate, channels, bitsPerSample, blockAlign, encoding);
    }

    private static AudioSampleFormat.Encoding extensibleEncoding(Pointer pointer) {
        int subFormatData1 = pointer.getInt(24);
        return subFormatData1 == 3
                ? AudioSampleFormat.Encoding.IEEE_FLOAT
                : AudioSampleFormat.Encoding.PCM_SIGNED;
    }

    private static int unsignedShort(short value) {
        return value & 0xFFFF;
    }

    private static final class ImmDeviceEnumerator extends Unknown {
        private ImmDeviceEnumerator(Pointer pointer) {
            super(pointer);
        }

        HRESULT getDefaultAudioEndpoint(int dataFlow, int role, PointerByReference endpoint) {
            return (HRESULT) _invokeNativeObject(4,
                    new Object[] {getPointer(), dataFlow, role, endpoint},
                    HRESULT.class);
        }
    }

    private static final class ImmDevice extends Unknown {
        private ImmDevice(Pointer pointer) {
            super(pointer);
        }

        HRESULT activate(Guid.REFIID iid, int clsCtx, Pointer activationParams, PointerByReference object) {
            return (HRESULT) _invokeNativeObject(3,
                    new Object[] {getPointer(), iid, clsCtx, activationParams, object},
                    HRESULT.class);
        }
    }

    private static final class AudioClient extends Unknown {
        private AudioClient(Pointer pointer) {
            super(pointer);
        }

        HRESULT initialize(int shareMode, int streamFlags, long bufferDuration,
                           long periodicity, Pointer format, Pointer sessionGuid) {
            return (HRESULT) _invokeNativeObject(3,
                    new Object[] {getPointer(), shareMode, streamFlags, bufferDuration,
                            periodicity, format, sessionGuid},
                    HRESULT.class);
        }

        HRESULT getMixFormat(PointerByReference format) {
            return (HRESULT) _invokeNativeObject(8,
                    new Object[] {getPointer(), format},
                    HRESULT.class);
        }

        HRESULT start() {
            return (HRESULT) _invokeNativeObject(10,
                    new Object[] {getPointer()},
                    HRESULT.class);
        }

        HRESULT stop() {
            return (HRESULT) _invokeNativeObject(11,
                    new Object[] {getPointer()},
                    HRESULT.class);
        }

        HRESULT getService(Guid.REFIID iid, PointerByReference service) {
            return (HRESULT) _invokeNativeObject(14,
                    new Object[] {getPointer(), iid, service},
                    HRESULT.class);
        }
    }

    private static final class AudioCaptureClient extends Unknown {
        private AudioCaptureClient(Pointer pointer) {
            super(pointer);
        }

        HRESULT getBuffer(PointerByReference data, IntByReference frames,
                          IntByReference flags, Pointer devicePosition, Pointer qpcPosition) {
            return (HRESULT) _invokeNativeObject(3,
                    new Object[] {getPointer(), data, frames, flags, devicePosition, qpcPosition},
                    HRESULT.class);
        }

        HRESULT releaseBuffer(int frames) {
            return (HRESULT) _invokeNativeObject(4,
                    new Object[] {getPointer(), frames},
                    HRESULT.class);
        }

        HRESULT getNextPacketSize(IntByReference frames) {
            return (HRESULT) _invokeNativeObject(5,
                    new Object[] {getPointer(), frames},
                    HRESULT.class);
        }
    }
}

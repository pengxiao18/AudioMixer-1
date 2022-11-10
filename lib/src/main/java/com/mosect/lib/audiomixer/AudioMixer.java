package com.mosect.lib.audiomixer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class AudioMixer {

    static {
        System.loadLibrary("mosect_audio_mixer");
    }

    private final static int MAX_MIX_COUNT = 100;

    private AudioBuffer outputBuffer;
    private boolean released = false;
    private final List<Track> tracks = new ArrayList<>();
    private final byte[] lock = new byte[0];
    private final long[] inputs = new long[MAX_MIX_COUNT];

    public AudioMixer(int sampleRate, int channelCount, int timeLength, PcmType pcmType) {
        outputBuffer = new AudioBuffer(sampleRate, channelCount, timeLength, pcmType);
    }

    public AudioTrack requestTrack(int sampleRate, int channelCount, PcmType pcmType) {
        synchronized (lock) {
            if (released) return null;
            AudioBuffer buffer = new AudioBuffer(sampleRate, channelCount, outputBuffer.getTimeLength(), pcmType);
            Track track = new Track(buffer);
            tracks.add(track);
            return track;
        }
    }

    public void release() {
        synchronized (lock) {
            if (!released) {
                outputBuffer.release();
                outputBuffer = null;
                for (Track track : tracks) {
                    track.release();
                }
                tracks.clear();
                released = true;
                lock.notifyAll();
            }
        }
    }

    private void flushTrack() {
        synchronized (lock) {
            if (isFlushOk()) {
                // 所有的轨道已经锁定
                lock.notifyAll();
            }
        }
    }

    public ByteBuffer tick() {
        synchronized (lock) {
            if (!released && !tracks.isEmpty() && isFlushOk()) {
                mix();
                return outputBuffer.getBuffer();
            }
            return null;
        }
    }

    public ByteBuffer tickAndWait() {
        synchronized (lock) {
            if (tracks.isEmpty()) return null;
            while (!isFlushOk()) {
                try {
                    lock.wait();
                } catch (InterruptedException ignored) {
                }
            }
            if (!released) {
                mix();
                return outputBuffer.getBuffer();
            }
            return null;
        }
    }

    private boolean isFlushOk() {
        for (Track t : tracks) {
            if (!t.isLocked()) {
                return false;
            }
        }
        return true;
    }

    private void mix() {
        for (int i = 0; i < tracks.size(); i++) {
            Track track = tracks.get(i);
            long id = track.getBuffer().getObjId();
            inputs[i] = id;
        }
        int status = mixBuffer(inputs, tracks.size(), outputBuffer.getObjId());
        for (Track track : tracks) {
            track.reset();
        }
        if (status != 0) throw new IllegalStateException("Invalid mix status: " + status);
    }

    private static native int mixBuffer(long[] inputs, int inputCount, long output);

    private class Track implements AudioTrack {

        private AudioBuffer buffer;
        private boolean locked = false;
        private int writeLen = 0;
        private boolean deleted = false;
        private final byte[] waitLock = new byte[0];

        public Track(AudioBuffer buffer) {
            this.buffer = buffer;
            buffer.clear();
        }

        @Override
        public int write(ByteBuffer data) {
            synchronized (lock) {
                if (deleted) return WRITE_RESULT_DELETED;
                if (locked) return WRITE_RESULT_LOCKED;
                ByteBuffer buffer = this.buffer.getBuffer();
                int maxLen = this.buffer.getBufferSize() - writeLen;
                int safeSize = Math.min(maxLen, data.remaining());
                if (safeSize == 0) return 0;
                if (safeSize > 0) {
                    data.limit(data.position() + safeSize);
                    buffer.put(data);
                    return safeSize;
                }
                return WRITE_RESULT_FULL;
            }
        }

        @Override
        public int write(byte[] data, int offset, int size) {
            if (offset < 0 || size < 0 || offset + size > data.length) {
                throw new ArrayIndexOutOfBoundsException(String.format("{ data.length=%s, offset=%s, size=%s }", data.length, offset, size));
            }
            synchronized (lock) {
                if (deleted) return WRITE_RESULT_DELETED;
                if (locked) return WRITE_RESULT_LOCKED;
                ByteBuffer buffer = this.buffer.getBuffer();
                int maxLen = this.buffer.getBufferSize() - writeLen;
                int safeSize = Math.min(maxLen, size);
                if (safeSize == 0) return 0;
                if (safeSize > 0) {
                    buffer.put(data, offset, safeSize);
                    return safeSize;
                }
                return WRITE_RESULT_FULL;
            }
        }

        @Override
        public boolean flush() {
            synchronized (lock) {
                if (!deleted && !locked) {
                    locked = true;
                    buffer.getBuffer().position(0);
                    flushTrack();
                    return true;
                }
                return false;
            }
        }

        @Override
        public void waitUnlock() {
            synchronized (lock) {
                if (!deleted && locked) {
                    try {
                        waitLock.wait();
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }

        @Override
        public void delete() {
            synchronized (lock) {
                if (!deleted) {
                    tracks.remove(this);
                    release();
                }
            }
        }

        public void release() {
            deleted = true;
            buffer.release();
            buffer = null;
            waitLock.notifyAll();
        }

        public void reset() {
            synchronized (lock) {
                if (locked) {
                    locked = false;
                    buffer.clear();
                    writeLen = 0;
                    buffer.getBuffer().position(0);
                    waitLock.notifyAll();
                }
            }
        }

        public boolean isLocked() {
            return locked;
        }

        public AudioBuffer getBuffer() {
            return buffer;
        }
    }
}

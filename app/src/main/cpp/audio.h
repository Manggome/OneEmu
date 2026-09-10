#pragma once
#include <oboe/Oboe.h>
#include <atomic>
#include <memory>
#include <mutex>
#include <vector>

// Oboe output with a lock-free-ish ring buffer and RetroArch-style dynamic rate control:
// the emulator's stereo int16 frames are resampled slightly faster/slower depending on how
// full the buffer is, so audio never drifts into constant underruns or overruns.
class AudioOutput : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    bool start(double sampleRate);
    void stop();
    void setMuted(bool muted) { muted_ = muted; }
    void setFastForward(bool ff) { fastForward_ = ff; }

    // Called from the emu thread with interleaved stereo int16 frames.
    void write(const int16_t* frames, size_t frameCount);

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream*, oboe::Result) override;

private:
    size_t available() const;
    void ensureStream();

    std::shared_ptr<oboe::AudioStream> stream_;
    double sampleRate_ = 48000.0;
    std::vector<int16_t> ring_;       // stereo interleaved
    size_t capacityFrames_ = 0;
    std::atomic<size_t> readPos_{0};
    std::atomic<size_t> writePos_{0};
    std::atomic<bool> muted_{false};
    std::atomic<bool> fastForward_{false};
    std::atomic<bool> needRestart_{false};
    std::mutex streamMutex_;

    // resampler state
    double phase_ = 0.0;
    int16_t lastL_ = 0, lastR_ = 0;
};

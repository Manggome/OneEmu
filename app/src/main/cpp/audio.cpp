#include "audio.h"
#include "log.h"
#include <algorithm>
#include <cstring>

bool AudioOutput::start(double sampleRate) {
    stop();
    sampleRate_ = sampleRate > 1000 ? sampleRate : 48000.0;
    capacityFrames_ = (size_t)(sampleRate_ * 0.25); // 250 ms of buffer
    ring_.assign(capacityFrames_ * 2, 0);
    readPos_ = 0;
    writePos_ = 0;
    ensureStream();
    return stream_ != nullptr;
}

void AudioOutput::ensureStream() {
    std::lock_guard<std::mutex> lock(streamMutex_);
    if (stream_) { stream_->close(); stream_.reset(); }
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::I16)
        ->setChannelCount(2)
        ->setSampleRate((int32_t)sampleRate_)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setUsage(oboe::Usage::Game)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    oboe::Result r = builder.openStream(stream_);
    if (r != oboe::Result::OK) {
        LOGE("oboe openStream failed: %s", oboe::convertToText(r));
        stream_.reset();
        return;
    }
    stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * 3);
    stream_->requestStart();
    LOGI("audio stream started: %d Hz, burst %d, buffer %d", stream_->getSampleRate(),
         stream_->getFramesPerBurst(), stream_->getBufferSizeInFrames());
}

void AudioOutput::stop() {
    std::lock_guard<std::mutex> lock(streamMutex_);
    if (stream_) {
        stream_->requestStop();
        stream_->close();
        stream_.reset();
    }
}

size_t AudioOutput::available() const {
    size_t w = writePos_.load(std::memory_order_acquire);
    size_t r = readPos_.load(std::memory_order_acquire);
    return (w + capacityFrames_ - r) % capacityFrames_;
}

void AudioOutput::write(const int16_t* frames, size_t frameCount) {
    if (needRestart_.exchange(false)) ensureStream();
    if (capacityFrames_ == 0 || frameCount == 0) return;

    size_t avail = available();
    size_t freeFrames = capacityFrames_ - 1 - avail;
    if (fastForward_) {
        // Keep the buffer topped up but never block: drop whatever does not fit.
        frameCount = std::min(frameCount, freeFrames);
        if (frameCount == 0) return;
    }

    // Dynamic rate control: fill 50% is the target. ratio > 1 produces more output frames
    // (buffer draining), ratio < 1 produces fewer (buffer filling).
    const double fill = (double)avail / (double)capacityFrames_;
    const double delta = 0.005;
    double ratio = 1.0 + delta * (1.0 - 2.0 * fill);
    if (freeFrames < frameCount * 2) ratio = std::min(ratio, 0.98); // emergency shrink
    ratio = std::clamp(ratio, 0.95, 1.05);

    // Linear interpolation resampler.
    size_t w = writePos_.load(std::memory_order_relaxed);
    size_t produced = 0;
    size_t maxOut = freeFrames;
    for (size_t i = 0; i < frameCount && produced < maxOut; ) {
        int16_t l0 = lastL_, r0 = lastR_;
        int16_t l1 = frames[i * 2], r1 = frames[i * 2 + 1];
        double t = phase_;
        ring_[w * 2] = (int16_t)(l0 + (l1 - l0) * t);
        ring_[w * 2 + 1] = (int16_t)(r0 + (r1 - r0) * t);
        w = (w + 1) % capacityFrames_;
        produced++;
        phase_ += 1.0 / ratio;
        while (phase_ >= 1.0 && i < frameCount) {
            phase_ -= 1.0;
            lastL_ = frames[i * 2];
            lastR_ = frames[i * 2 + 1];
            i++;
        }
    }
    writePos_.store(w, std::memory_order_release);
}

oboe::DataCallbackResult AudioOutput::onAudioReady(oboe::AudioStream*, void* audioData, int32_t numFrames) {
    int16_t* out = static_cast<int16_t*>(audioData);
    size_t avail = available();
    size_t n = std::min((size_t)numFrames, avail);
    size_t r = readPos_.load(std::memory_order_relaxed);
    if (muted_) {
        memset(out, 0, numFrames * 2 * sizeof(int16_t));
    } else {
        for (size_t i = 0; i < n; i++) {
            out[i * 2] = ring_[r * 2];
            out[i * 2 + 1] = ring_[r * 2 + 1];
            r = (r + 1) % capacityFrames_;
        }
        if (n < (size_t)numFrames) memset(out + n * 2, 0, (numFrames - n) * 2 * sizeof(int16_t));
    }
    if (muted_) r = (r + n) % capacityFrames_;
    readPos_.store(r, std::memory_order_release);
    return oboe::DataCallbackResult::Continue;
}

void AudioOutput::onErrorAfterClose(oboe::AudioStream*, oboe::Result r) {
    LOGW("audio stream error (%s), scheduling restart", oboe::convertToText(r));
    needRestart_ = true;
}

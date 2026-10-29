#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>
#include <android/log.h>
#include "whisper.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "KolTorah", __VA_ARGS__)

static std::string fromJString(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_org_koltorah_app_MainActivity_nativeTranscribe(
        JNIEnv *env, jclass, jstring wavPath, jstring modelPath, jstring language) {
    const std::string audioPath = fromJString(env, wavPath);
    const std::string model = fromJString(env, modelPath);
    std::string lang = fromJString(env, language);
    if (lang.empty()) lang = "he";

    whisper_context_params contextParams = whisper_context_default_params();
    contextParams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(model.c_str(), contextParams);
    if (!ctx) {
        LOGE("Could not load Whisper model: %s", model.c_str());
        return env->NewStringUTF("");
    }

    FILE *file = fopen(audioPath.c_str(), "rb");
    if (!file) {
        whisper_free(ctx);
        LOGE("Could not open WAV: %s", audioPath.c_str());
        return env->NewStringUTF("");
    }

    unsigned char header[44];
    if (fread(header, 1, sizeof(header), file) != sizeof(header) ||
        std::string(reinterpret_cast<char *>(header), 4) != "RIFF" ||
        std::string(reinterpret_cast<char *>(header + 8), 4) != "WAVE") {
        fclose(file);
        whisper_free(ctx);
        LOGE("Audio must be a standard PCM WAV file");
        return env->NewStringUTF("");
    }

    // Walk RIFF chunks so WAV metadata chunks before "data" are handled.
    fseek(file, 12, SEEK_SET);
    int sampleRate = 0, channels = 0, bits = 0, format = 0;
    std::vector<unsigned char> raw;
    bool foundData = false;
    while (!feof(file)) {
        unsigned char chunkHeader[8];
        if (fread(chunkHeader, 1, 8, file) != 8) break;
        unsigned int chunkSize = (unsigned int)chunkHeader[4] |
            ((unsigned int)chunkHeader[5] << 8) |
            ((unsigned int)chunkHeader[6] << 16) |
            ((unsigned int)chunkHeader[7] << 24);
        std::string tag(reinterpret_cast<char *>(chunkHeader), 4);
        if (tag == "fmt ") {
            std::vector<unsigned char> fmt(chunkSize);
            if (chunkSize < 16 || fread(fmt.data(), 1, chunkSize, file) != chunkSize) break;
            format = fmt[0] | (fmt[1] << 8);
            channels = fmt[2] | (fmt[3] << 8);
            sampleRate = fmt[4] | (fmt[5] << 8) | (fmt[6] << 16) | (fmt[7] << 24);
            bits = fmt[14] | (fmt[15] << 8);
        } else if (tag == "data") {
            if (chunkSize > 800u * 1024u * 1024u) break;
            raw.resize(chunkSize);
            if (fread(raw.data(), 1, chunkSize, file) != chunkSize) raw.clear();
            foundData = !raw.empty();
            break;
        } else {
            if (fseek(file, (long)chunkSize, SEEK_CUR) != 0) break;
        }
        if (chunkSize & 1u) fseek(file, 1, SEEK_CUR);
    }
    fclose(file);

    if (!foundData || format != 1 || bits != 16 || channels < 1 || channels > 2 ||
        sampleRate < 8000 || sampleRate > 192000) {
        whisper_free(ctx);
        LOGE("Unsupported WAV format. Require PCM 16-bit mono/stereo.");
        return env->NewStringUTF("");
    }

    const size_t frames = raw.size() / (2 * (size_t)channels);
    if (frames < 1) {
        whisper_free(ctx);
        return env->NewStringUTF("");
    }
    std::vector<float> mono(frames);
    for (size_t i = 0; i < frames; ++i) {
        int sum = 0;
        for (int ch = 0; ch < channels; ++ch) {
            const size_t k = (i * channels + ch) * 2;
            const int16_t s = (int16_t)(raw[k] | (raw[k + 1] << 8));
            sum += s;
        }
        mono[i] = (sum / (float)channels) / 32768.0f;
    }

    std::vector<float> samples;
    if (sampleRate == WHISPER_SAMPLE_RATE) {
        samples = std::move(mono);
    } else {
        const size_t outFrames = (size_t)((double)frames * WHISPER_SAMPLE_RATE / sampleRate);
        samples.resize(outFrames);
        for (size_t i = 0; i < outFrames; ++i) {
            const double src = (double)i * sampleRate / WHISPER_SAMPLE_RATE;
            const size_t left = std::min((size_t)src, frames - 1);
            const size_t right = std::min(left + 1, frames - 1);
            const float frac = (float)(src - left);
            samples[i] = mono[left] * (1.0f - frac) + mono[right] * frac;
        }
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = 4;
    params.language = lang.c_str();
    params.initial_prompt = "שיעור תורה בעברית. מונחים נפוצים: גמרא, תלמוד, משנה, רש\u0022י, תוספות, ראשונים, אחרונים, הלכה, קושיה, תירוץ, סוגיה, הקדוש ברוך הוא, בעזרת השם.";
    params.translate = false;
    params.no_context = true;
    params.single_segment = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.suppress_blank = true;

    std::string output;
    if (whisper_full(ctx, params, samples.data(), (int)samples.size()) == 0) {
        const int segments = whisper_full_n_segments(ctx);
        for (int i = 0; i < segments; ++i) {
            const char *txt = whisper_full_get_segment_text(ctx, i);
            if (txt) {
                if (!output.empty()) output += " ";
                output += txt;
            }
        }
    } else {
        LOGE("whisper_full failed");
    }
    whisper_free(ctx);
    return env->NewStringUTF(output.c_str());
}

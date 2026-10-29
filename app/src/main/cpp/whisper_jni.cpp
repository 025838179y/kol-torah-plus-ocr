#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <string>
#include <thread>
#include <vector>
#include <android/log.h>
#include "whisper.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "KolTorah", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "KolTorah", __VA_ARGS__)

static std::string fromJString(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

static jstring errorText(JNIEnv *env, whisper_context *ctx, FILE *file, const char *message) {
    if (file) fclose(file);
    if (ctx) whisper_free(ctx);
    std::string result = "ERROR:";
    result += message;
    return env->NewStringUTF(result.c_str());
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
        return errorText(env, nullptr, nullptr,
            "לא ניתן לטעון את מודל התמלול. בדוק שהקובץ תקין ושיש מספיק זיכרון פנוי.");
    }

    FILE *file = fopen(audioPath.c_str(), "rb");
    if (!file) {
        LOGE("Could not open WAV: %s", audioPath.c_str());
        return errorText(env, ctx, nullptr, "לא ניתן לפתוח את קובץ השמע שנבחר.");
    }

    unsigned char header[12];
    if (fread(header, 1, sizeof(header), file) != sizeof(header) ||
        std::string(reinterpret_cast<char *>(header), 4) != "RIFF" ||
        std::string(reinterpret_cast<char *>(header + 8), 4) != "WAVE") {
        LOGE("Invalid WAV header");
        return errorText(env, ctx, file, "קובץ השמע אינו WAV תקין.");
    }

    int sampleRate = 0, channels = 0, bits = 0, format = 0;
    long dataStart = -1;
    uint32_t dataBytes = 0;

    // Read WAV metadata without loading the complete recording into memory.
    fseek(file, 12, SEEK_SET);
    while (!feof(file)) {
        unsigned char chunkHeader[8];
        if (fread(chunkHeader, 1, sizeof(chunkHeader), file) != sizeof(chunkHeader)) break;
        uint32_t chunkSize = (uint32_t)chunkHeader[4] |
            ((uint32_t)chunkHeader[5] << 8) |
            ((uint32_t)chunkHeader[6] << 16) |
            ((uint32_t)chunkHeader[7] << 24);
        std::string tag(reinterpret_cast<char *>(chunkHeader), 4);
        if (tag == "fmt ") {
            if (chunkSize < 16 || chunkSize > 4096) {
                return errorText(env, ctx, file, "כותרת WAV אינה תקינה.");
            }
            std::vector<unsigned char> fmt(chunkSize);
            if (fread(fmt.data(), 1, chunkSize, file) != chunkSize) {
                return errorText(env, ctx, file, "קובץ WAV נקטע או פגום.");
            }
            format = fmt[0] | (fmt[1] << 8);
            channels = fmt[2] | (fmt[3] << 8);
            sampleRate = fmt[4] | (fmt[5] << 8) | (fmt[6] << 16) | (fmt[7] << 24);
            bits = fmt[14] | (fmt[15] << 8);
        } else if (tag == "data") {
            dataStart = ftell(file);
            dataBytes = chunkSize;
            break;
        } else {
            if (fseek(file, (long)chunkSize, SEEK_CUR) != 0) break;
        }
        if (chunkSize & 1u) fseek(file, 1, SEEK_CUR);
    }

    if (dataStart < 0 || dataBytes == 0 || format != 1 || bits != 16 ||
        channels < 1 || channels > 2 || sampleRate < 8000 || sampleRate > 192000) {
        return errorText(env, ctx, file,
            "פורמט השמע אינו PCM ‏16-bit נתמך. נסה לייבא MP3/M4A או WAV רגיל.");
    }

    const size_t bytesPerFrame = 2u * (size_t)channels;
    const size_t totalFrames = dataBytes / bytesPerFrame;
    if (totalFrames < (size_t)sampleRate / 10u) {
        return errorText(env, ctx, file, "ההקלטה ריקה או קצרה מדי.");
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    const unsigned int cpuCount = std::thread::hardware_concurrency();
    params.n_threads = std::max(2, std::min(6, cpuCount == 0 ? 4 : (int)cpuCount));
    params.language = lang.c_str();
    params.initial_prompt = "שיעור תורה בעברית. מונחים נפוצים: גמרא, תלמוד, משנה, רש\u0022י, תוספות, ראשונים, אחרונים, הלכה, קושיה, תירוץ, סוגיה, הקדוש ברוך הוא, בעזרת השם.";
    params.translate = false;
    params.no_context = true;
    params.single_segment = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.suppress_blank = true;

    // Process at most 30 seconds at a time. This avoids allocating several
    // hundred megabytes for a full-length shiur on a modest phone.
    const size_t framesPerChunk = (size_t)sampleRate * 30u;
    std::vector<unsigned char> raw;
    std::string output;
    size_t doneFrames = 0;
    int chunkNumber = 0;

    while (doneFrames < totalFrames) {
        const size_t frames = std::min(framesPerChunk, totalFrames - doneFrames);
        const size_t bytes = frames * bytesPerFrame;
        raw.resize(bytes);
        const long offset = dataStart + (long)(doneFrames * bytesPerFrame);
        if (fseek(file, offset, SEEK_SET) != 0 || fread(raw.data(), 1, bytes, file) != bytes) {
            return errorText(env, ctx, file, "לא ניתן לקרוא חלק מקובץ השמע.");
        }

        std::vector<float> mono(frames);
        for (size_t i = 0; i < frames; ++i) {
            int sum = 0;
            for (int ch = 0; ch < channels; ++ch) {
                const size_t k = (i * channels + (size_t)ch) * 2;
                const uint16_t word = (uint16_t)raw[k] | ((uint16_t)raw[k + 1] << 8);
                const int16_t sample = (int16_t)word;
                sum += sample;
            }
            mono[i] = (sum / (float)channels) / 32768.0f;
        }

        std::vector<float> samples;
        if (sampleRate == WHISPER_SAMPLE_RATE) {
            samples = std::move(mono);
        } else {
            const size_t outputFrames = (size_t)((double)frames * WHISPER_SAMPLE_RATE / sampleRate);
            samples.resize(outputFrames);
            for (size_t i = 0; i < outputFrames; ++i) {
                const double source = (double)i * sampleRate / WHISPER_SAMPLE_RATE;
                const size_t left = std::min((size_t)source, frames - 1);
                const size_t right = std::min(left + 1, frames - 1);
                const float fraction = (float)(source - left);
                samples[i] = mono[left] * (1.0f - fraction) + mono[right] * fraction;
            }
        }

        if (!samples.empty()) {
            const int status = whisper_full(ctx, params, samples.data(), (int)samples.size());
            if (status != 0) {
                LOGE("whisper_full failed in chunk %d", chunkNumber);
                return errorText(env, ctx, file,
                    "מנוע התמלול נכשל בעיבוד קטע שמע. נסה הקלטה קצרה יותר וודא שיש מספיק זיכרון פנוי.");
            }
            const int segments = whisper_full_n_segments(ctx);
            for (int i = 0; i < segments; ++i) {
                const char *segment = whisper_full_get_segment_text(ctx, i);
                if (segment && segment[0] != '\0') {
                    if (!output.empty()) output += " ";
                    output += segment;
                }
            }
        }
        doneFrames += frames;
        ++chunkNumber;
        LOGI("Finished transcription chunk %d, %zu/%zu frames", chunkNumber, doneFrames, totalFrames);
        raw.clear();
        mono.clear();
        samples.clear();
    }

    fclose(file);
    whisper_free(ctx);
    if (output.empty()) {
        return env->NewStringUTF("ERROR:לא זוהה דיבור. בדוק את עוצמת השמע ונסה קטע קצר וברור.");
    }
    return env->NewStringUTF(output.c_str());
}

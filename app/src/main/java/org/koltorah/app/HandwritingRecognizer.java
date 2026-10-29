package org.koltorah.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import org.json.JSONArray;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Offline Hebrew handwriting recognition using the Mishkefet-v1 HTR model.
 * The model output is returned as recognized, without spelling correction or rewriting.
 * See THIRD_PARTY_NOTICES.txt for the model's non-commercial license.
 */
final class HandwritingRecognizer implements AutoCloseable {
    interface ProgressListener { void onProgress(String message); }

    static final class RecognitionResult {
        final String text;
        final float confidence;
        final int lines;
        RecognitionResult(String text, float confidence, int lines) {
            this.text = text;
            this.confidence = confidence;
            this.lines = lines;
        }
    }

    private static final String TAG = "KolTorahHTR";
    private static final String MODEL_ASSET = "handwriting_htr_int8.onnx";
    private static final String CHARSET_ASSET = "handwriting_charset.json";
    private static final String MODEL_VERSION = "mishkefet-int8-2026-10";
    private static final int LINE_HEIGHT = 64;
    private static final int MAX_LINE_WIDTH = 2560;

    private final Context context;
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    private final String[] charset;

    HandwritingRecognizer(Context context) throws Exception {
        this.context = context.getApplicationContext();
        File modelFile = copyModelIfNeeded();
        environment = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())));
        options.setInterOpNumThreads(1);
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        OrtSession created;
        try {
            created = environment.createSession(modelFile.getAbsolutePath(), options);
        } finally {
            options.close();
        }
        session = created;
        Set<String> names = session.getInputNames();
        if (names == null || names.isEmpty()) {
            session.close();
            throw new IllegalStateException("מודל כתב היד אינו מכיל קלט תקין.");
        }
        inputName = names.iterator().next();
        try (InputStream input = this.context.getAssets().open(CHARSET_ASSET)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            JSONArray values = new JSONArray(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            charset = new String[values.length()];
            for (int i = 0; i < values.length(); i++) charset[i] = values.getString(i);
        } catch (Exception e) {
            session.close();
            throw new IllegalStateException("קובץ אותיות מודל כתב היד חסר או פגום.", e);
        }
        if (charset.length < 20 || !charset[0].isEmpty()) {
            session.close();
            throw new IllegalStateException("מילון האותיות של מודל כתב היד אינו תקין.");
        }
    }

    private File copyModelIfNeeded() throws Exception {
        File target = new File(context.getFilesDir(), MODEL_ASSET);
        android.content.SharedPreferences p = context.getSharedPreferences("kol_torah", Context.MODE_PRIVATE);
        String installedVersion = p.getString("htr_model_version", "");
        if (target.exists() && target.length() > 10_000_000L && MODEL_VERSION.equals(installedVersion)) return target;
        if (target.exists() && !target.delete()) throw new java.io.IOException("לא ניתן לעדכן את מודל כתב היד.");
        File temporary = new File(target.getParentFile(), MODEL_ASSET + ".tmp");
        try (InputStream input = new BufferedInputStream(context.getAssets().open(MODEL_ASSET));
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        }
        if (!temporary.renameTo(target)) throw new java.io.IOException("לא ניתן לשמור את מודל כתב היד.");
        if (target.length() < 10_000_000L) {
            target.delete();
            throw new java.io.IOException("מודל כתב היד חסר או קטן מהצפוי.");
        }
        p.edit().putString("htr_model_version", MODEL_VERSION).apply();
        return target;
    }

    RecognitionResult recognizePage(Bitmap page, boolean enhancedAccuracy, ProgressListener progress) throws Exception {
        if (page == null || page.isRecycled()) throw new IllegalArgumentException("לא נבחרה תמונה תקינה.");
        boolean singleLine = page.getHeight() <= 260 ||
                page.getWidth() / (float) Math.max(1, page.getHeight()) >= 2.5f;
        List<Rect> rects = singleLine
                ? Collections.singletonList(new Rect(0, 0, page.getWidth(), page.getHeight()))
                : findTextLines(page);
        List<Bitmap> crops = new ArrayList<>();
        if (singleLine) {
            crops.add(page);
        } else {
            for (Rect r : rects) crops.add(Bitmap.createBitmap(page, r.left, r.top, r.width(), r.height()));
            if (crops.isEmpty()) crops.add(page);
        }

        StringBuilder combined = new StringBuilder();
        float confidenceTotal = 0f;
        int confidenceCount = 0;
        boolean useMultiScale = enhancedAccuracy || singleLine;
        try {
            for (int i = 0; i < crops.size(); i++) {
                if (progress != null) progress.onProgress(
                        "מזהה כתב יד בעברית — שורה " + (i + 1) + " מתוך " + crops.size() + "…");
                LineResult line = recognizeLine(crops.get(i), useMultiScale);
                if (line.text != null && !line.text.trim().isEmpty()) {
                    if (combined.length() > 0) combined.append('\n');
                    combined.append(line.text);
                    confidenceTotal += line.confidence;
                    confidenceCount++;
                }
            }
        } finally {
            for (Bitmap crop : crops) {
                if (crop != page && !crop.isRecycled()) crop.recycle();
            }
        }
        if (combined.length() == 0) throw new java.io.IOException(
                "מודל כתב היד לא זיהה שורות. נסה צילום קרוב, ישר ומואר יותר.");
        return new RecognitionResult(combined.toString(),
                confidenceCount == 0 ? 0 : confidenceTotal / confidenceCount, crops.size());
    }

    private static final class LineResult {
        final String text;
        final float confidence;
        LineResult(String text, float confidence) { this.text = text; this.confidence = confidence; }
    }

    private LineResult recognizeLine(Bitmap line, boolean enhancedAccuracy) throws Exception {
        float[] scales = enhancedAccuracy ? new float[]{1.0f, 0.82f, 1.22f} : new float[]{1.0f};
        LineResult best = new LineResult("", -1f);
        for (float scale : scales) {
            float[] inputValues = prepareLine(line, scale);
            int width = inputValues.length / LINE_HEIGHT;
            try (OnnxTensor tensor = OnnxTensor.createTensor(
                    environment, FloatBuffer.wrap(inputValues), new long[]{1, 1, LINE_HEIGHT, width});
                 OrtSession.Result result = session.run(Collections.singletonMap(inputName, tensor))) {
                Object raw = result.get(0).getValue();
                if (!(raw instanceof float[][][])) {
                    throw new IllegalStateException("מודל כתב היד החזיר תוצאה בפורמט לא צפוי.");
                }
                float[][][] output = (float[][][]) raw;
                if (output.length == 0 || output[0].length == 0) continue;
                LineResult candidate = greedyDecode(output[0]);
                if (!candidate.text.trim().isEmpty() && candidate.confidence > best.confidence) best = candidate;
            }
        }
        return best.confidence < 0 ? new LineResult("", 0f) : best;
    }

    private LineResult greedyDecode(float[][] logprobs) {
        StringBuilder text = new StringBuilder();
        int previous = -1;
        float confidence = 0f;
        int emitted = 0;
        for (float[] row : logprobs) {
            int bestIndex = 0;
            float bestLogProb = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < row.length; i++) {
                if (row[i] > bestLogProb) { bestLogProb = row[i]; bestIndex = i; }
            }
            if (bestIndex != 0 && bestIndex != previous && bestIndex < charset.length) {
                if (charset[bestIndex] != null) {
                    text.append(charset[bestIndex]);
                    confidence += (float) Math.exp(Math.max(-40f, Math.min(0f, bestLogProb)));
                    emitted++;
                }
            }
            previous = bestIndex;
        }
        return new LineResult(text.toString(), emitted == 0 ? 0f : 100f * confidence / emitted);
    }

    /** Normalize lighting, resize line height to 64, and invert so ink is high-valued. */
    private static float[] prepareLine(Bitmap line, float scale) {
        int sourceW = line.getWidth(), sourceH = line.getHeight();
        int count = sourceW * sourceH;
        int[] pixels = new int[count];
        line.getPixels(pixels, 0, sourceW, 0, 0, sourceW, sourceH);
        int[] gray = new int[count];
        for (int i = 0; i < count; i++) {
            int p = pixels[i];
            gray[i] = ((((p >> 16) & 255) * 299) + (((p >> 8) & 255) * 587) + ((p & 255) * 114)) / 1000;
        }

        int radius = Math.max(sourceH / 8, 3);
        float[] horizontal = new float[count];
        float[] blurred = new float[count];
        for (int y = 0; y < sourceH; y++) {
            long sum = 0;
            for (int x = 0; x <= Math.min(sourceW - 1, radius); x++) sum += gray[y * sourceW + x];
            for (int x = 0; x < sourceW; x++) {
                int left = Math.max(0, x - radius);
                int right = Math.min(sourceW - 1, x + radius);
                horizontal[y * sourceW + x] = (float) sum / (right - left + 1);
                int nextLeft = x - radius;
                int nextRight = x + radius + 1;
                if (nextLeft >= 0) sum -= gray[y * sourceW + nextLeft];
                if (nextRight < sourceW) sum += gray[y * sourceW + nextRight];
            }
        }
        for (int x = 0; x < sourceW; x++) {
            double sum = 0;
            for (int y = 0; y <= Math.min(sourceH - 1, radius); y++) sum += horizontal[y * sourceW + x];
            for (int y = 0; y < sourceH; y++) {
                int top = Math.max(0, y - radius);
                int bottom = Math.min(sourceH - 1, y + radius);
                blurred[y * sourceW + x] = (float) (sum / (bottom - top + 1));
                int nextTop = y - radius;
                int nextBottom = y + radius + 1;
                if (nextTop >= 0) sum -= horizontal[nextTop * sourceW + x];
                if (nextBottom < sourceH) sum += horizontal[nextBottom * sourceW + x];
            }
        }

        float[] ratios = new float[count];
        int[] histogram = new int[2048];
        for (int i = 0; i < count; i++) {
            float ratio = gray[i] / Math.max(blurred[i], 1f);
            ratios[i] = ratio;
            histogram[Math.max(0, Math.min(histogram.length - 1, (int) (ratio * 256f)))]++;
        }
        int lowBin = percentileBin(histogram, (int) (count * 0.02f));
        int highBin = percentileBin(histogram, Math.min(count - 1, (int) (count * 0.98f)));
        float low = lowBin / 256f;
        float high = highBin / 256f;
        if (high - low < 0.02f) { low = 0f; high = 1.5f; }

        int targetW = Math.max(16, Math.min(MAX_LINE_WIDTH,
                Math.round(sourceW * (LINE_HEIGHT / (float) sourceH) * scale)));
        float[] result = new float[LINE_HEIGHT * targetW];
        float yRatio = sourceH / (float) LINE_HEIGHT;
        float xRatio = sourceW / (float) targetW;
        float range = high - low;
        for (int y = 0; y < LINE_HEIGHT; y++) {
            float fy = Math.max(0, Math.min(sourceH - 1, (y + 0.5f) * yRatio - 0.5f));
            int y0 = (int) fy, y1 = Math.min(sourceH - 1, y0 + 1);
            float wy = fy - y0;
            for (int x = 0; x < targetW; x++) {
                float fx = Math.max(0, Math.min(sourceW - 1, (x + 0.5f) * xRatio - 0.5f));
                int x0 = (int) fx, x1 = Math.min(sourceW - 1, x0 + 1);
                float wx = fx - x0;
                float a = ratios[y0 * sourceW + x0] * (1f - wx) + ratios[y0 * sourceW + x1] * wx;
                float b = ratios[y1 * sourceW + x0] * (1f - wx) + ratios[y1 * sourceW + x1] * wx;
                float ratio = a * (1f - wy) + b * wy;
                float pixel = Math.max(0f, Math.min(255f, (ratio - low) / range * 255f));
                result[y * targetW + x] = 1f - pixel / 255f;
            }
        }
        return result;
    }

    private static int percentileBin(int[] histogram, int target) {
        int total = 0;
        for (int i = 0; i < histogram.length; i++) {
            total += histogram[i];
            if (total > target) return i;
        }
        return histogram.length - 1;
    }

    /** Split a photographed page into text-line crops using row-adaptive ink detection. */
    private static List<Rect> findTextLines(Bitmap page) {
        int width = page.getWidth(), height = page.getHeight();
        if (height <= 260 || width / (float) Math.max(1, height) >= 2.5f) {
            return Collections.singletonList(new Rect(0, 0, width, height));
        }
        byte[] gray = new byte[width * height];
        int[] rowPixels = new int[width], histogram = new int[256];
        int[] thresholds = new int[height], inkCount = new int[height];
        for (int y = 0; y < height; y++) {
            page.getPixels(rowPixels, 0, width, 0, y, width, 1);
            Arrays.fill(histogram, 0);
            int offset = y * width;
            for (int x = 0; x < width; x++) {
                int p = rowPixels[x];
                int g = (((p >> 16) & 255) * 299 + ((p >> 8) & 255) * 587 + (p & 255) * 114) / 1000;
                gray[offset + x] = (byte) g;
                histogram[g]++;
            }
            int half = width / 2, seen = 0, median = 220;
            for (int i = 0; i < 256; i++) {
                seen += histogram[i];
                if (seen >= half) { median = i; break; }
            }
            int threshold = Math.max(0, median - 22);
            thresholds[y] = threshold;
            int dark = 0;
            for (int x = 0; x < width; x++) if ((gray[offset + x] & 255) < threshold) dark++;
            inkCount[y] = dark > width * 0.42f ? 0 : dark;
        }

        int minInk = Math.max(4, width / 1200);
        boolean[] active = new boolean[height];
        for (int y = 0; y < height; y++) active[y] = inkCount[y] >= minInk;

        List<int[]> bands = new ArrayList<>();
        int y = 0;
        while (y < height) {
            while (y < height && !active[y]) y++;
            if (y >= height) break;
            int top = y, last = y, gap = 0;
            y++;
            while (y < height) {
                if (active[y]) { last = y; gap = 0; }
                else if (++gap > 6) break;
                y++;
            }
            int bottom = last + 1;
            if (bottom - top >= Math.max(5, height / 500)) bands.add(new int[]{top, bottom});
            y = Math.max(y, bottom);
        }
        if (bands.isEmpty()) return Collections.singletonList(new Rect(0, 0, width, height));

        List<int[]> merged = new ArrayList<>();
        int mergeGap = Math.max(4, Math.min(10, height / 180));
        for (int[] band : bands) {
            if (!merged.isEmpty() && band[0] - merged.get(merged.size() - 1)[1] <= mergeGap) {
                merged.get(merged.size() - 1)[1] = band[1];
            } else {
                merged.add(new int[]{band[0], band[1]});
            }
        }

        List<Rect> result = new ArrayList<>();
        for (int[] band : merged) {
            int top = band[0], bottom = band[1];
            if (bottom - top < Math.max(7, height / 350)) continue;
            int minX = width, maxX = -1;
            for (int yy = top; yy < bottom; yy++) {
                if (inkCount[yy] == 0) continue;
                int offset = yy * width, t = thresholds[yy];
                for (int x = 0; x < width; x++) {
                    if ((gray[offset + x] & 255) < t) {
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                    }
                }
            }
            if (maxX < minX) continue;
            int yPad = Math.max(5, (bottom - top) / 6);
            int xPad = Math.max(8, width / 200);
            int left = Math.max(0, minX - xPad);
            int right = Math.min(width, maxX + 1 + xPad);
            int cropTop = Math.max(0, top - yPad), cropBottom = Math.min(height, bottom + yPad);
            if (right - left >= 16 && cropBottom - cropTop >= 12) {
                result.add(new Rect(left, cropTop, right, cropBottom));
            }
        }
        return result.isEmpty() ? Collections.singletonList(new Rect(0, 0, width, height)) : result;
    }

    @Override public void close() {
        try { session.close(); } catch (Exception e) { Log.w(TAG, "Could not close ONNX session", e); }
    }
}

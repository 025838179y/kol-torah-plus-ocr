package org.koltorah.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.exifinterface.media.ExifInterface;

import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class HandwritingActivity extends Activity {
    private static final int IMAGE_REQUEST = 201;
    private static final int INK = Color.rgb(27, 48, 59);
    private static final int MUTED = Color.rgb(102, 119, 126);
    private static final int PAPER = Color.rgb(247, 248, 245);
    private static final int TEAL = Color.rgb(23, 112, 103);
    private static final int WHITE = Color.WHITE;

    private Bitmap selectedImage;
    private ImageView preview;
    private EditText recognizedText;
    private TextView status;
    private ProgressBar progress;
    private Button recognizeButton;
    private Button returnButton;
    private Spinner engineSpinner;
    private CheckBox accurateCheckBox;
    private boolean busy = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(INK);
        getWindow().setNavigationBarColor(INK);
        buildUi();
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LinearLayout column() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        result.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return result;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView result = new TextView(this);
        result.setText(value);
        result.setTextSize(size);
        result.setTextColor(color);
        result.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        result.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        if (bold) result.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return result;
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h));
    }

    private Button button(String label, boolean primary) {
        Button result = new Button(this);
        result.setText(label);
        result.setAllCaps(false);
        result.setTextSize(14);
        result.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        result.setTextColor(primary ? WHITE : INK);
        result.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary ? TEAL : Color.rgb(232, 237, 233)));
        result.setMinHeight(dp(48));
        return result;
    }

    private void buildUi() {
        LinearLayout root = column();
        root.setBackgroundColor(PAPER);

        LinearLayout header = column();
        header.setBackgroundColor(INK);
        header.setPadding(dp(20), dp(18), dp(20), dp(20));
        header.addView(text("כתב יד לטקסט", 26, WHITE, true), lp(-1, 42));
        header.addView(text("מודל כתב יד עברי ייעודי • עובד מקומית", 13, Color.rgb(203, 219, 216), false), lp(-1, 30));
        root.addView(header, lp(-1, -2));

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = column();
        body.setPadding(dp(16), dp(16), dp(16), dp(24));

        body.addView(text("1. בחר צילום או תמונה של הדף", 19, INK, true), lp(-1, 38));
        TextView explainer = text("לכתב יד בחר במנוע הייעודי: הוא מפצל דף לשורות ומזהה כתב יד עברי במכשיר. התוצאה מועברת לעורך כפי שהמודל זיהה אותה — ללא תיקון איות או שכתוב.", 13, MUTED, false);
        explainer.setGravity(Gravity.RIGHT | Gravity.TOP);
        explainer.setLineSpacing(dp(3), 1.0f);
        body.addView(explainer, lp(-1, -2));

        body.addView(text("סוג הטקסט", 14, INK, true), lp(-1, 30));
        engineSpinner = new Spinner(this);
        String[] engines = {"כתב יד עברי — מודל ייעודי", "טקסט מודפס — OCR"};
        ArrayAdapter<String> engineAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, engines);
        engineAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        engineSpinner.setAdapter(engineAdapter);
        body.addView(engineSpinner, lp(-1, 48));

        accurateCheckBox = new CheckBox(this);
        accurateCheckBox.setText("שיפור דיוק (כמה קריאות לכל שורה, עשוי להיות איטי יותר)");
        accurateCheckBox.setTextSize(13);
        accurateCheckBox.setTextColor(INK);
        accurateCheckBox.setChecked(false);
        body.addView(accurateCheckBox, lp(-1, 48));

        Button choose = button("בחירת תמונה", true);
        LinearLayout.LayoutParams chooseLp = lp(-1, 50);
        chooseLp.topMargin = dp(12);
        body.addView(choose, chooseLp);

        preview = new ImageView(this);
        preview.setBackgroundColor(Color.rgb(234, 237, 233));
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setAdjustViewBounds(true);
        LinearLayout.LayoutParams previewLp = lp(-1, 240);
        previewLp.topMargin = dp(12);
        body.addView(preview, previewLp);

        recognizeButton = button("זיהוי טקסט בתמונה", true);
        recognizeButton.setEnabled(false);
        LinearLayout.LayoutParams recogLp = lp(-1, 50);
        recogLp.topMargin = dp(12);
        body.addView(recognizeButton, recogLp);

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = lp(-1, 28);
        progressLp.topMargin = dp(8);
        body.addView(progress, progressLp);

        status = text("בחר תמונה כדי להתחיל.", 12, MUTED, false);
        body.addView(status, lp(-1, 32));

        body.addView(text("2. בדוק את הטקסט המזוהה", 19, INK, true), lp(-1, 38));
        TextView reviewHelp = text("אפשר לתקן ידנית אם הזיהוי טעה. שום שינוי לא נעשה אוטומטית.", 13, MUTED, false);
        body.addView(reviewHelp, lp(-1, 30));

        recognizedText = new EditText(this);
        recognizedText.setGravity(Gravity.TOP | Gravity.RIGHT);
        recognizedText.setTextDirection(View.TEXT_DIRECTION_RTL);
        recognizedText.setTextSize(19);
        recognizedText.setTextColor(INK);
        recognizedText.setHint("התוצאה תופיע כאן");
        recognizedText.setMinLines(7);
        recognizedText.setPadding(dp(12), dp(12), dp(12), dp(12));
        recognizedText.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(207, 218, 214)));
        LinearLayout.LayoutParams editorLp = lp(-1, 200);
        editorLp.topMargin = dp(4);
        body.addView(recognizedText, editorLp);

        returnButton = button("העברת הטקסט לעורך קול תורה", true);
        returnButton.setEnabled(false);
        LinearLayout.LayoutParams returnLp = lp(-1, 52);
        returnLp.topMargin = dp(12);
        body.addView(returnButton, returnLp);

        Button cancel = button("חזרה", false);
        LinearLayout.LayoutParams cancelLp = lp(-1, 46);
        cancelLp.topMargin = dp(8);
        body.addView(cancel, cancelLp);

        TextView foot = text("המודל הייעודי מיועד לכתב יד עברי מודרני. הוא עשוי לטעות בכתב חריג, רש״י, קיצורים או צילום מטושטש. המודל ניתן לשימוש לא־מסחרי בלבד לפי רישיונו.", 12, MUTED, false);
        foot.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        foot.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams footLp = lp(-1, -2);
        footLp.topMargin = dp(12);
        body.addView(foot, footLp);

        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        choose.setOnClickListener(v -> chooseImage());
        recognizeButton.setOnClickListener(v -> recognize());
        returnButton.setOnClickListener(v -> returnText());
        cancel.setOnClickListener(v -> finish());
    }

    private void chooseImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, IMAGE_REQUEST);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != IMAGE_REQUEST || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        new Thread(() -> {
            try {
                Bitmap bitmap = decodeScaled(uri, 2800);
                if (bitmap == null) throw new IllegalArgumentException("לא ניתן לפענח את התמונה");
                runOnUiThread(() -> {
                    selectedImage = bitmap;
                    preview.setImageBitmap(bitmap);
                    recognizeButton.setEnabled(true);
                    status.setText("התמונה נטענה. אפשר לבצע זיהוי אופליין.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("לא ניתן לקרוא את התמונה.");
                    Toast.makeText(this, "נסה לבחור קובץ JPG או PNG ברור יותר.", Toast.LENGTH_LONG).show();
                });
            }
        }, "KolTorahImageLoader").start();
    }

    private Bitmap decodeScaled(Uri uri, int maxDimension) throws Exception {
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream exifInput = getContentResolver().openInputStream(uri)) {
            if (exifInput != null) {
                ExifInterface exif = new ExifInterface(exifInput);
                orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            }
        } catch (Exception ignored) {}

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            decoded = BitmapFactory.decodeStream(input, null, options);
        }
        if (decoded == null) return null;
        float degrees = 0;
        if (orientation == ExifInterface.ORIENTATION_ROTATE_90) degrees = 90;
        else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) degrees = 180;
        else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) degrees = 270;
        if (degrees == 0) return decoded;
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        Bitmap corrected = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
        if (corrected != decoded) decoded.recycle();
        return corrected;
    }

    private File ensureHebrewData() throws Exception {
        File root = new File(getFilesDir(), "tesseract");
        File data = new File(root, "tessdata");
        if (!data.exists() && !data.mkdirs()) throw new java.io.IOException("לא ניתן ליצור תיקיית נתוני שפה");
        File heb = new File(data, "heb.traineddata");
        if (!heb.exists() || heb.length() < 500_000L) {
            try (InputStream input = getAssets().open("tessdata/heb.traineddata");
                 OutputStream output = new FileOutputStream(heb)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
            }
        }
        if (heb.length() < 500_000L) throw new java.io.IOException("נתוני השפה העברית חסרים");
        return root;
    }

    private void recognize() {
        if (busy || selectedImage == null) return;
        busy = true;
        recognizeButton.setEnabled(false);
        returnButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        recognizedText.setText("");
        boolean handwriting = engineSpinner.getSelectedItemPosition() == 0;
        status.setText(handwriting ? "טוען מודל כתב יד מקומי…" : "מכין OCR לטקסט מודפס…");

        new Thread(() -> {
            String result = null;
            String error = null;
            float confidence = -1f;
            int lines = 0;
            try {
                if (handwriting) {
                    try (HandwritingRecognizer recognizer = new HandwritingRecognizer(this)) {
                        HandwritingRecognizer.RecognitionResult output = recognizer.recognizePage(
                                selectedImage, accurateCheckBox.isChecked(),
                                message -> runOnUiThread(() -> status.setText(message)));
                        result = output.text;
                        confidence = output.confidence;
                        lines = output.lines;
                    }
                } else {
                    PrintedResult output = recognizePrinted(selectedImage);
                    result = output.text;
                    confidence = output.confidence;
                    lines = 1;
                }
                if (result == null || result.trim().isEmpty()) throw new java.io.IOException("לא זוהה טקסט בתמונה");
            } catch (Exception e) {
                error = e.getMessage() == null ? "שגיאה לא צפויה בזמן הזיהוי" : e.getMessage();
            }

            final String finalResult = result;
            final String finalError = error;
            final float finalConfidence = confidence;
            final int finalLines = lines;
            runOnUiThread(() -> {
                busy = false;
                progress.setVisibility(View.GONE);
                recognizeButton.setEnabled(selectedImage != null);
                if (finalError != null) {
                    status.setText("הזיהוי לא הושלם.");
                    Toast.makeText(this, finalError, Toast.LENGTH_LONG).show();
                } else {
                    recognizedText.setText(finalResult);
                    recognizedText.setSelection(recognizedText.length());
                    returnButton.setEnabled(true);
                    String confidenceText = finalConfidence >= 0 ? " • ציון ביטחון משוער " + Math.round(finalConfidence) + "%" : "";
                    String lineText = handwriting ? " • " + finalLines + " שורות" : "";
                    status.setText("הזיהוי הסתיים. הטקסט לא תוקן אוטומטית" + lineText + confidenceText + ".");
                }
            });
        }, "KolTorahOCR").start();
    }

    private static final class PrintedResult {
        final String text;
        final float confidence;
        PrintedResult(String text, float confidence) { this.text = text; this.confidence = confidence; }
    }

    /** Fallback for printed text: compare original and contrast-normalized input. */
    private PrintedResult recognizePrinted(Bitmap source) throws Exception {
        File root = ensureHebrewData();
        TessBaseAPI tess = new TessBaseAPI();
        Bitmap enhanced = null;
        try {
            if (!tess.init(root.getAbsolutePath(), "heb")) throw new java.io.IOException("מנוע OCR לא הצליח לטעון עברית");
            tess.setVariable("preserve_interword_spaces", "1");
            enhanced = contrastBitmap(source);
            Bitmap[] inputs = {source, enhanced};
            int[] modes = {TessBaseAPI.PageSegMode.PSM_AUTO, TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK};
            String best = "";
            float bestScore = -1f, bestConfidence = 0f;
            for (Bitmap image : inputs) {
                for (int mode : modes) {
                    tess.setPageSegMode(mode);
                    tess.setImage(image);
                    String candidate = tess.getUTF8Text();
                    if (candidate == null || candidate.trim().isEmpty()) continue;
                    float confidence = tess.meanConfidence();
                    float score = confidence + Math.min(10f, candidate.trim().length() / 30f);
                    if (score > bestScore) {
                        bestScore = score;
                        bestConfidence = confidence;
                        best = candidate;
                    }
                }
            }
            if (best.isEmpty()) throw new java.io.IOException("OCR לא מצא טקסט מודפס בתמונה");
            return new PrintedResult(best, bestConfidence);
        } finally {
            try { tess.recycle(); } catch (Exception ignored) {}
            if (enhanced != null && enhanced != source && !enhanced.isRecycled()) enhanced.recycle();
        }
    }

    private Bitmap contrastBitmap(Bitmap source) {
        int width = source.getWidth(), height = source.getHeight();
        int[] pixels = new int[width * height];
        source.getPixels(pixels, 0, width, 0, 0, width, height);
        int[] histogram = new int[256], gray = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int value = (((p >> 16) & 255) * 299 + ((p >> 8) & 255) * 587 + (p & 255) * 114) / 1000;
            gray[i] = value;
            histogram[value]++;
        }
        int low = percentile(histogram, pixels.length, 0.02f);
        int high = percentile(histogram, pixels.length, 0.98f);
        if (high <= low + 8) return Bitmap.createBitmap(source);
        for (int i = 0; i < pixels.length; i++) {
            int value = Math.max(0, Math.min(255, (gray[i] - low) * 255 / (high - low)));
            pixels[i] = Color.rgb(value, value, value);
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    private int percentile(int[] histogram, int total, float fraction) {
        int target = (int) (total * fraction), count = 0;
        for (int i = 0; i < histogram.length; i++) {
            count += histogram[i];
            if (count >= target) return i;
        }
        return 255;
    }

    private void returnText() {
        String result = recognizedText.getText().toString();
        if (result.isEmpty()) {
            Toast.makeText(this, "אין טקסט להעברה.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent data = new Intent();
        data.putExtra("ocr_text", result);
        setResult(RESULT_OK, data);
        finish();
    }

    @Override protected void onDestroy() {
        if (selectedImage != null && !selectedImage.isRecycled()) selectedImage.recycle();
        super.onDestroy();
    }
}

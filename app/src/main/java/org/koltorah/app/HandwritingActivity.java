package org.koltorah.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
        header.addView(text("זיהוי מקומי • בלי אינטרנט • בלי שכתוב אוטומטי", 13, Color.rgb(203, 219, 216), false), lp(-1, 30));
        root.addView(header, lp(-1, -2));

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = column();
        body.setPadding(dp(16), dp(16), dp(16), dp(24));

        body.addView(text("1. בחר צילום או תמונה של הדף", 19, INK, true), lp(-1, 38));
        TextView explainer = text("אפשר לבחור תמונה מהטלפון. העיבוד נעשה במכשיר והטקסט שיופק יישלח לעורך של קול תורה בדיוק כפי שהמנוע זיהה אותו — ללא תיקון או ניסוח מחדש.", 13, MUTED, false);
        explainer.setGravity(Gravity.RIGHT | Gravity.TOP);
        explainer.setLineSpacing(dp(3), 1.0f);
        body.addView(explainer, lp(-1, -2));

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

        TextView foot = text("לתשומת לבך: מנוע ה־OCR המקומי אינו מאומן במיוחד לכל סגנונות כתב היד. דיוק הזיהוי תלוי בכתב, בתאורה ובחדות התמונה.", 12, MUTED, false);
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
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(input, null, options);
        }
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
        progress.setVisibility(View.VISIBLE);
        status.setText("מזהה טקסט בעברית במכשיר…");
        new Thread(() -> {
            TessBaseAPI tess = new TessBaseAPI();
            String raw = null;
            String error = null;
            try {
                File root = ensureHebrewData();
                if (!tess.init(root.getAbsolutePath(), "heb")) {
                    throw new java.io.IOException("מנוע הזיהוי לא הצליח לטעון את נתוני העברית");
                }
                tess.setImage(selectedImage);
                raw = tess.getUTF8Text();
                if (raw == null || raw.trim().isEmpty()) throw new java.io.IOException("לא זוהה טקסט בתמונה");
            } catch (Exception e) {
                error = e.getMessage() == null ? "שגיאה בזיהוי" : e.getMessage();
            } finally {
                try { tess.recycle(); } catch (Exception ignored) {}
            }
            final String result = raw;
            final String problem = error;
            runOnUiThread(() -> {
                busy = false;
                progress.setVisibility(View.GONE);
                recognizeButton.setEnabled(selectedImage != null);
                if (problem != null) {
                    status.setText("הזיהוי לא הושלם.");
                    Toast.makeText(this, problem, Toast.LENGTH_LONG).show();
                } else {
                    recognizedText.setText(result);
                    recognizedText.setSelection(recognizedText.length());
                    returnButton.setEnabled(true);
                    status.setText("הזיהוי הסתיים. לא הופעל תיקון אוטומטי.");
                }
            });
        }, "KolTorahHandwritingOCR").start();
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

package org.koltorah.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {
    private static final int REQUEST_AUDIO = 101;
    private static final int REQUEST_DOCX = 102;
    private static final int REQUEST_RECORD_PERMISSION = 103;
    private static final int REQUEST_OCR_RESULT = 104;

    private static final int INK = Color.rgb(27, 48, 59);
    private static final int MUTED = Color.rgb(102, 119, 126);
    private static final int PAPER = Color.rgb(247, 248, 245);
    private static final int TEAL = Color.rgb(23, 112, 103);
    private static final int GOLD = Color.rgb(208, 164, 85);
    private static final int WHITE = Color.WHITE;

    private SharedPreferences prefs;
    private EditText transcriptEditor;
    private TextView statusText;
    private TextView selectedAudioText;
    private Button transcribeButton;
    private Button recordButton;
    private Button stopButton;
    private Button exportButton;
    private File selectedWav;
    private String pendingExportText = "";
    private int preferredSize = 20;
    private String preferredFont = "sans-serif";
    private AudioRecord audioRecord;
    private Thread recordThread;
    private volatile boolean recording;
    private File recordingFile;
    private RandomAccessFile recordingOutput;
    private long audioDataBytes = 0;
    private volatile boolean nativeAvailable = true;
    private boolean busy = false;

    static {
        try {
            System.loadLibrary("koltorah");
        } catch (Throwable ignored) {
            // The interface and document editor remain usable if native ASR isn't built.
        }
    }

    private static native String nativeTranscribe(String wavPath, String modelPath, String language);

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("kol_torah", MODE_PRIVATE);
        preferredSize = prefs.getInt("text_size", 20);
        preferredFont = prefs.getString("font", "sans-serif");
        getWindow().setStatusBarColor(INK);
        getWindow().setNavigationBarColor(INK);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }
        buildHome();
        String draft = prefs.getString("draft", "");
        if (!TextUtils.isEmpty(draft)) transcriptEditor.setText(draft);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        v.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        v.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        return v;
    }

    private LinearLayout column() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }

    private LinearLayout row() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return box;
    }

    private LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width < 0 ? width : dp(width),
                height < 0 ? height : dp(height));
    }

    private void pad(View view, int left, int top, int right, int bottom) {
        view.setPadding(dp(left), dp(top), dp(right), dp(bottom));
    }

    private TextView label(String value) {
        return text(value, 13, MUTED, false);
    }

    private Button button(String title, boolean primary) {
        Button b = new Button(this);
        b.setText(title);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        b.setTextColor(primary ? WHITE : INK);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary ? TEAL : Color.rgb(232, 237, 233)));
        b.setMinHeight(dp(48));
        b.setPadding(dp(10), dp(7), dp(10), dp(7));
        return b;
    }

    private void buildHome() {
        LinearLayout root = column();
        root.setBackgroundColor(PAPER);

        LinearLayout header = column();
        header.setBackgroundColor(INK);
        pad(header, 22, 20, 22, 22);
        TextView brand = text("קול תורה", 29, WHITE, true);
        brand.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        header.addView(brand, lp(-1, 42));
        TextView subtitle = text("מקליטים. מתמללים. שומרים את דברי התורה.", 14, Color.rgb(203, 219, 216), false);
        header.addView(subtitle, lp(-1, 30));
        TextView chip = text("●  עיבוד מקומי • ללא חשבון", 12, Color.rgb(239, 210, 153), true);
        header.addView(chip, lp(-1, 26));
        root.addView(header, lp(-1, -2));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = column();
        pad(content, 16, 16, 16, 24);

        TextView intro = text("השיעור הבא שלך מתחיל כאן", 21, INK, true);
        content.addView(intro, lp(-1, 36));
        TextView helper = text("בחר קובץ WAV או הקלט שיעור חדש. לאחר מכן אפשר לתמלל, לערוך ולייצא למסמך Word.", 14, MUTED, false);
        helper.setGravity(Gravity.RIGHT | Gravity.TOP);
        helper.setLineSpacing(dp(3), 1f);
        content.addView(helper, lp(-1, -2));
        View gap = new View(this);
        content.addView(gap, lp(-1, 12));

        LinearLayout actionCard = column();
        actionCard.setBackgroundColor(WHITE);
        pad(actionCard, 14, 14, 14, 14);
        actionCard.setElevation(dp(2));
        recordButton = button("●  הקלטת שיעור", true);
        stopButton = button("סיום הקלטה", false);
        stopButton.setEnabled(false);
        transcribeButton = button("תמלל שיעור", true);
        transcribeButton.setEnabled(false);
        Button chooseButton = button("בחירת קובץ שמע", false);
        Button handwritingButton = button("זיהוי כתב יד מתמונה", false);
        LinearLayout.LayoutParams actionLp = lp(-1, 50);
        actionLp.bottomMargin = dp(8);
        actionCard.addView(recordButton, actionLp);
        actionCard.addView(stopButton, actionLp);
        actionCard.addView(chooseButton, actionLp);
        actionCard.addView(transcribeButton, lp(-1, 50));
        LinearLayout.LayoutParams handwritingLp = lp(-1, 50);
        handwritingLp.topMargin = dp(8);
        actionCard.addView(handwritingButton, handwritingLp);
        selectedAudioText = text("עדיין לא נבחר קובץ שמע", 12, MUTED, false);
        selectedAudioText.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        actionCard.addView(selectedAudioText, lp(-1, 34));
        content.addView(actionCard, lp(-1, -2));

        View gap2 = new View(this);
        content.addView(gap2, lp(-1, 16));
        TextView editTitle = text("תמליל השיעור", 20, INK, true);
        content.addView(editTitle, lp(-1, 38));
        TextView editHint = label("אפשר לערוך את הטקסט לפני הייצוא. התמלול אינו אמור לשכתב את התוכן.");
        content.addView(editHint, lp(-1, 32));

        transcriptEditor = new EditText(this);
        transcriptEditor.setGravity(Gravity.TOP | Gravity.RIGHT);
        transcriptEditor.setTextDirection(View.TEXT_DIRECTION_RTL);
        transcriptEditor.setTextSize(preferredSize);
        transcriptEditor.setTypeface(Typeface.create(preferredFont, Typeface.NORMAL));
        transcriptEditor.setTextColor(INK);
        transcriptEditor.setHint("כאן יופיע התמלול, ואפשר גם לכתוב או להדביק טקסט...");
        transcriptEditor.setHintTextColor(Color.rgb(145, 155, 157));
        transcriptEditor.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(207, 218, 214)));
        transcriptEditor.setMinLines(9);
        transcriptEditor.setPadding(dp(14), dp(14), dp(14), dp(14));
        transcriptEditor.setText(prefs.getString("draft", ""));
        content.addView(transcriptEditor, lp(-1, 240));

        LinearLayout toolsRow = row();
        Button dictionaryButton = button("מילון החלפות", false);
        Button settingsButton = button("הגדרות עיצוב", false);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(48), 1);
        half.setMargins(0, dp(10), dp(5), dp(5));
        toolsRow.addView(dictionaryButton, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, dp(48), 1);
        half2.setMargins(dp(5), dp(10), 0, dp(5));
        toolsRow.addView(settingsButton, half2);
        content.addView(toolsRow, lp(-1, 58));

        exportButton = button("ייצוא למסמך Word (.docx)", true);
        LinearLayout.LayoutParams exportLp = lp(-1, 54);
        exportLp.topMargin = dp(5);
        content.addView(exportButton, exportLp);
        Button saveDraft = button("שמירת טיוטה במכשיר", false);
        LinearLayout.LayoutParams draftLp = lp(-1, 48);
        draftLp.topMargin = dp(8);
        content.addView(saveDraft, draftLp);

        TextView foot = text("הפרטיות שלך חשובה: ההקלטות והטיוטות נשמרות במכשיר.", 12, MUTED, false);
        foot.setGravity(Gravity.CENTER);
        content.addView(foot, lp(-1, 44));

        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        chooseButton.setOnClickListener(v -> chooseAudio());
        handwritingButton.setOnClickListener(v -> startActivityForResult(new Intent(this, HandwritingActivity.class), REQUEST_OCR_RESULT));
        recordButton.setOnClickListener(v -> requestRecord());
        stopButton.setOnClickListener(v -> stopRecording());
        transcribeButton.setOnClickListener(v -> transcribeSelected());
        dictionaryButton.setOnClickListener(v -> showDictionary());
        settingsButton.setOnClickListener(v -> showSettings());
        exportButton.setOnClickListener(v -> startDocxExport());
        saveDraft.setOnClickListener(v -> saveDraft());
        transcriptEditor.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                prefs.edit().putString("draft", s.toString()).apply();
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
    }

    private void setStatus(String message) {
        if (statusText != null) statusText.setText(message);
        if (selectedAudioText != null) selectedAudioText.setText(message);
    }

    private void chooseAudio() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        startActivityForResult(i, REQUEST_AUDIO);
    }

    private void requestRecord() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_PERMISSION);
        } else {
            startRecording();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_PERMISSION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording();
        } else if (requestCode == REQUEST_RECORD_PERMISSION) {
            toast("נדרשת הרשאת מיקרופון להקלטת שיעור.");
        }
    }

    private void startRecording() {
        if (recording || busy) return;
        int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            toast("לא ניתן להפעיל את המיקרופון במכשיר הזה.");
            return;
        }
        try {
            File dir = new File(getCacheDir(), "recordings");
            if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("לא ניתן ליצור תיקיית הקלטה");
            recordingFile = new File(dir, "kol-torah-" + System.currentTimeMillis() + ".wav");
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 4096));
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("המיקרופון לא מאותחל");
            recordingOutput = new RandomAccessFile(recordingFile, "rw");
            recordingOutput.setLength(0);
            recordingOutput.write(new byte[44]);
            audioDataBytes = 0;
            recording = true;
            audioRecord.startRecording();
            recordButton.setEnabled(false);
            stopButton.setEnabled(true);
            transcribeButton.setEnabled(false);
            selectedAudioText.setText("מקליטים עכשיו…");
            recordThread = new Thread(() -> {
                byte[] buffer = new byte[Math.max(4096, min)];
                try {
                    while (recording) {
                        int n = audioRecord.read(buffer, 0, buffer.length);
                        if (n > 0 && recordingOutput != null) {
                            synchronized (this) {
                                recordingOutput.write(buffer, 0, n);
                                audioDataBytes += n;
                            }
                        }
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> toast("ההקלטה נעצרה עקב שגיאה."));
                }
            }, "KolTorahRecorder");
            recordThread.start();
        } catch (Exception e) {
            recording = false;
            releaseRecorder();
            toast("לא ניתן להתחיל הקלטה: " + e.getMessage());
        }
    }

    private void stopRecording() {
        if (!recording) return;
        recording = false;
        try {
            if (audioRecord != null) audioRecord.stop();
        } catch (Exception ignored) {}
        try {
            if (recordThread != null) recordThread.join(1800);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        try {
            synchronized (this) {
                if (recordingOutput != null) {
                    writeWavHeader(recordingOutput, audioDataBytes, 16000, 1, 16);
                    recordingOutput.close();
                    recordingOutput = null;
                }
            }
        } catch (Exception e) {
            toast("שגיאה בשמירת ההקלטה.");
        }
        releaseRecorder();
        selectedWav = recordingFile;
        recordButton.setEnabled(true);
        stopButton.setEnabled(false);
        transcribeButton.setEnabled(selectedWav != null && selectedWav.exists() && selectedWav.length() > 44);
        selectedAudioText.setText("הקלטה נשמרה במכשיר • " + (audioDataBytes / 32000) + " שניות");
        toast("ההקלטה נשמרה. אפשר להתחיל בתמלול.");
    }

    private void releaseRecorder() {
        try { if (audioRecord != null) audioRecord.release(); } catch (Exception ignored) {}
        audioRecord = null;
    }

    private void writeWavHeader(RandomAccessFile out, long dataBytes, int rate, int channels, int bits) throws java.io.IOException {
        int byteRate = rate * channels * bits / 8;
        out.seek(0);
        out.writeBytes("RIFF");
        writeLittleInt(out, (int) (36 + dataBytes));
        out.writeBytes("WAVE");
        out.writeBytes("fmt ");
        writeLittleInt(out, 16);
        writeLittleShort(out, 1);
        writeLittleShort(out, channels);
        writeLittleInt(out, rate);
        writeLittleInt(out, byteRate);
        writeLittleShort(out, channels * bits / 8);
        writeLittleShort(out, bits);
        out.writeBytes("data");
        writeLittleInt(out, (int) dataBytes);
    }

    private void writeLittleInt(RandomAccessFile out, int value) throws java.io.IOException {
        out.write(value & 255); out.write((value >>> 8) & 255); out.write((value >>> 16) & 255); out.write((value >>> 24) & 255);
    }
    private void writeLittleShort(RandomAccessFile out, int value) throws java.io.IOException {
        out.write(value & 255); out.write((value >>> 8) & 255);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OCR_RESULT) {
            if (resultCode == RESULT_OK && data != null) {
                String recognized = data.getStringExtra("ocr_text");
                if (recognized != null && !recognized.isEmpty()) {
                    transcriptEditor.setText(recognized);
                    transcriptEditor.setSelection(transcriptEditor.length());
                    toast("הטקסט המזוהה הועבר לעורך ללא שכתוב.");
                }
            }
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_AUDIO) {
            importAudio(uri);
        } else if (requestCode == REQUEST_DOCX) {
            writeDocx(uri, pendingExportText);
        }
    }

    private void importAudio(Uri uri) {
        setStatus("קורא את קובץ השמע…");
        new Thread(() -> {
            try {
                String name = getDisplayName(uri);
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".wav")) {
                    runOnUiThread(() -> {
                        selectedAudioText.setText("נבחר: " + name);
                        toast("בגרסה זו התמלול מקבל קובצי WAV מסוג PCM בלבד. אפשר לייבא WAV או להקליט דרך האפליקציה.");
                    });
                    return;
                }
                File dir = new File(getCacheDir(), "imported");
                if (!dir.exists()) dir.mkdirs();
                File target = new File(dir, "import-" + System.currentTimeMillis() + ".wav");
                try (InputStream in = new BufferedInputStream(getContentResolver().openInputStream(uri));
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                    byte[] buf = new byte[32768];
                    int count;
                    while ((count = in.read(buf)) != -1) out.write(buf, 0, count);
                }
                if (!isWave(target)) throw new java.io.IOException("הקובץ אינו WAV תקין");
                selectedWav = target;
                runOnUiThread(() -> {
                    selectedAudioText.setText("נבחר קובץ: " + name);
                    transcribeButton.setEnabled(true);
                    toast("קובץ השמע מוכן לתמלול.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    selectedAudioText.setText("לא ניתן לטעון את הקובץ.");
                    toast("שגיאה בקריאת הקובץ. נסה לבחור קובץ WAV מסוג PCM.");
                });
            }
        }, "KolTorahImport").start();
    }

    private boolean isWave(File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] h = new byte[12];
            return in.read(h) == 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' &&
                    h[8] == 'W' && h[9] == 'A' && h[10] == 'V' && h[11] == 'E';
        } catch (Exception e) { return false; }
    }

    private String getDisplayName(Uri uri) {
        String result = "audio.wav";
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int index = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) result = c.getString(index);
            }
        } catch (Exception ignored) {}
        return result;
    }

    private void transcribeSelected() {
        if (busy) return;
        if (recording) {
            toast("סיים את ההקלטה לפני התמלול.");
            return;
        }
        if (selectedWav == null || !selectedWav.exists()) {
            toast("בחר קובץ שמע או הקלט שיעור קודם.");
            return;
        }
        busy = true;
        transcribeButton.setEnabled(false);
        recordButton.setEnabled(false);
        selectedAudioText.setText("מכין את מודל התמלול המקומי בפעם הראשונה…");
        new Thread(() -> {
            try {
                File model = new File(getFilesDir(), "ggml-tiny.bin");
                if (!model.exists() || model.length() < 20_000_000L) {
                    try (InputStream in = new BufferedInputStream(getAssets().open("ggml-tiny.bin"));
                         OutputStream out = new BufferedOutputStream(new FileOutputStream(model))) {
                        byte[] buf = new byte[1024 * 1024];
                        int n;
                        long copied = 0;
                        while ((n = in.read(buf)) >= 0) {
                            out.write(buf, 0, n);
                            copied += n;
                            final long progress = copied;
                            runOnUiThread(() -> selectedAudioText.setText("מעתיק מודל מקומי… " + (progress / (1024 * 1024)) + "MB"));
                        }
                    }
                }
                if (!nativeAvailable) throw new IllegalStateException("מנוע התמלול אינו זמין בגרסת הבנייה הזו.");
                runOnUiThread(() -> selectedAudioText.setText("מתמלל בעברית במכשיר…"));
                String raw = nativeTranscribe(selectedWav.getAbsolutePath(), model.getAbsolutePath(), "he");
                if (raw == null || raw.trim().isEmpty()) throw new IllegalStateException("לא התקבל תמלול. בדוק שקובץ ה־WAV הוא PCM 16-bit ושיש בו דיבור ברור.");
                String result = applyDictionary(raw.trim());
                runOnUiThread(() -> {
                    transcriptEditor.setText(result);
                    transcriptEditor.setSelection(transcriptEditor.length());
                    selectedAudioText.setText("התמלול הסתיים • ניתן לערוך ולייצא");
                    toast("התמלול הושלם. מומלץ לעבור על שמות ומונחים תורניים.");
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "שגיאה לא צפויה" : e.getMessage();
                runOnUiThread(() -> {
                    selectedAudioText.setText("התמלול לא הושלם.");
                    toast(msg.contains("ggml-tiny.bin") || msg.contains("assets") ?
                        "מודל התמלול אינו כלול בבנייה הזו. הרץ את תהליך Build APK שבמאגר GitHub." : "לא ניתן לתמלל: " + msg);
                });
            } finally {
                busy = false;
                runOnUiThread(() -> {
                    transcribeButton.setEnabled(selectedWav != null && selectedWav.exists());
                    recordButton.setEnabled(!recording);
                });
            }
        }, "KolTorahTranscriber").start();
    }

    private LinkedHashMap<String, String> readDictionary() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        String raw = prefs.getString("dictionary", "{}");
        try {
            org.json.JSONObject object = new org.json.JSONObject(raw);
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = object.optString(key, "");
                if (!key.trim().isEmpty() && !value.trim().isEmpty()) result.put(key, value);
            }
        } catch (Exception ignored) {}
        return result;
    }

    private void saveDictionary(Map<String, String> dictionary) {
        org.json.JSONObject object = new org.json.JSONObject();
        try {
            for (Map.Entry<String, String> entry : dictionary.entrySet()) object.put(entry.getKey(), entry.getValue());
        } catch (Exception ignored) {}
        prefs.edit().putString("dictionary", object.toString()).apply();
    }

    private String applyDictionary(String input) {
        String result = input;
        for (Map.Entry<String, String> rule : readDictionary().entrySet()) {
            String pattern = "(?<![\\p{L}\\p{N}])" + Pattern.quote(rule.getKey()) + "(?![\\p{L}\\p{N}])";
            result = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                    .matcher(result).replaceAll(Matcher.quoteReplacement(rule.getValue()));
        }
        return result;
    }

    private void showDictionary() {
        final LinkedHashMap<String, String> dictionary = readDictionary();
        LinearLayout box = column();
        pad(box, 16, 12, 16, 4);
        TextView intro = text("הוסף כלל לתיקון מילים שחוזרות בתמלול. ההחלפה מתבצעת אחרי הזיהוי, ואינה מאמנת או משנה את מודל הדיבור.", 13, MUTED, false);
        intro.setGravity(Gravity.RIGHT | Gravity.TOP);
        box.addView(intro, lp(-1, -2));
        EditText from = new EditText(this);
        from.setSingleLine(true);
        from.setHint("כך המילה זוהתה");
        from.setTextDirection(View.TEXT_DIRECTION_RTL);
        box.addView(from, lp(-1, 52));
        EditText to = new EditText(this);
        to.setSingleLine(true);
        to.setHint("כך יש לכתוב אותה");
        to.setTextDirection(View.TEXT_DIRECTION_RTL);
        box.addView(to, lp(-1, 52));
        TextView list = text(dictionarySummary(dictionary), 13, INK, false);
        list.setGravity(Gravity.RIGHT | Gravity.TOP);
        box.addView(list, lp(-1, 150));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("מילון החלפות אישי")
                .setView(box)
                .setPositiveButton("סיום", (d, w) -> saveDictionary(dictionary))
                .setNeutralButton("הוסף כלל", null)
                .setNegativeButton("ניקוי מילון", (d, w) -> {
                    saveDictionary(new LinkedHashMap<>());
                    toast("מילון ההחלפות נוקה.");
                }).create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                String a = from.getText().toString().trim();
                String b = to.getText().toString().trim();
                if (a.isEmpty() || b.isEmpty()) {
                    toast("יש למלא גם את המילה המקורית וגם את התוצאה.");
                    return;
                }
                dictionary.put(a, b);
                saveDictionary(dictionary);
                list.setText(dictionarySummary(dictionary));
                from.setText("");
                to.setText("");
                toast("כלל ההחלפה נשמר.");
            });
        });
        dialog.show();
    }

    private String dictionarySummary(Map<String, String> dictionary) {
        if (dictionary.isEmpty()) return "אין עדיין כללים. לדוגמה: ״גמרא״ → ״גמרא״ או כתיב מועדף למונח תורני.";
        StringBuilder b = new StringBuilder("כללים שמורים:\n");
        int n = 0;
        for (Map.Entry<String, String> e : dictionary.entrySet()) {
            b.append("• ").append(e.getKey()).append("  ←  ").append(e.getValue()).append("\n");
            if (++n >= 8) {
                if (dictionary.size() > n) b.append("ועוד ").append(dictionary.size() - n).append(" כללים");
                break;
            }
        }
        return b.toString();
    }

    private void showSettings() {
        LinearLayout box = column();
        pad(box, 18, 10, 18, 6);
        box.addView(text("גופן", 14, INK, true), lp(-1, 28));
        Spinner fontSpinner = new Spinner(this);
        String[] names = {"נקי — Sans Serif", "ספרי — Serif", "מונוספייס", "צר וקומפקטי"};
        String[] values = {"sans-serif", "serif", "monospace", "sans-serif-condensed"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        fontSpinner.setAdapter(adapter);
        int selected = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(preferredFont)) selected = i;
        fontSpinner.setSelection(selected);
        box.addView(fontSpinner, lp(-1, 48));
        box.addView(text("גודל טקסט", 14, INK, true), lp(-1, 30));
        TextView sizeValue = text(preferredSize + " נקודות", 14, TEAL, true);
        box.addView(sizeValue, lp(-1, 28));
        SeekBar sizeBar = new SeekBar(this);
        sizeBar.setMax(20);
        sizeBar.setProgress(preferredSize - 14);
        box.addView(sizeBar, lp(-1, 44));
        sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                sizeValue.setText((progress + 14) + " נקודות");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        box.addView(text("סגנון מסמך", 14, INK, true), lp(-1, 30));
        Spinner styleSpinner = new Spinner(this);
        String[] styles = {"קלאסי", "נקי ומרווח", "גדול וקריא"};
        styleSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, styles));
        styleSpinner.setSelection(prefs.getInt("doc_style", 0));
        box.addView(styleSpinner, lp(-1, 48));
        new AlertDialog.Builder(this)
                .setTitle("הגדרות עיצוב")
                .setView(box)
                .setPositiveButton("שמירה", (d, w) -> {
                    preferredFont = values[Math.max(0, fontSpinner.getSelectedItemPosition())];
                    preferredSize = sizeBar.getProgress() + 14;
                    prefs.edit().putString("font", preferredFont)
                            .putInt("text_size", preferredSize)
                            .putInt("doc_style", styleSpinner.getSelectedItemPosition()).apply();
                    transcriptEditor.setTextSize(preferredSize);
                    transcriptEditor.setTypeface(Typeface.create(preferredFont, Typeface.NORMAL));
                    toast("הגדרות העיצוב נשמרו.");
                })
                .setNegativeButton("ביטול", null)
                .show();
    }

    private void saveDraft() {
        prefs.edit().putString("draft", transcriptEditor.getText().toString()).apply();
        toast("הטיוטה נשמרה במכשיר.");
    }

    private void startDocxExport() {
        String text = transcriptEditor.getText().toString();
        if (text.trim().isEmpty()) {
            toast("אין עדיין טקסט לייצוא.");
            return;
        }
        pendingExportText = text;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        i.putExtra(Intent.EXTRA_TITLE, "שיעור-תורה.docx");
        startActivityForResult(i, REQUEST_DOCX);
    }

    private void writeDocx(Uri destination, String text) {
        try (OutputStream out = getContentResolver().openOutputStream(destination);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            if (out == null) throw new java.io.IOException("לא ניתן לפתוח קובץ לשמירה");
            addZipEntry(zip, "[Content_Types].xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                    "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
                    "</Types>");
            addZipEntry(zip, "_rels/.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
                    "</Relationships>");
            addZipEntry(zip, "word/_rels/document.xml.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
                    "</Relationships>");
            addZipEntry(zip, "word/styles.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                    "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\" w:cs=\"Arial\"/>" +
                    "<w:sz w:val=\"" + (preferredSize * 2) + "\"/><w:lang w:val=\"he-IL\"/></w:rPr></w:rPrDefault></w:docDefaults>" +
                    "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/>" +
                    "<w:pPr><w:bidi/><w:jc w:val=\"right\"/></w:pPr></w:style></w:styles>");
            StringBuilder doc = new StringBuilder();
            doc.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                    .append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");
            String[] lines = text.split("\\r?\\n", -1);
            for (String line : lines) {
                doc.append("<w:p><w:pPr><w:bidi/><w:jc w:val=\"right\"/></w:pPr><w:r><w:rPr>")
                        .append("<w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\" w:cs=\"Arial\"/>")
                        .append("<w:rtl/><w:sz w:val=\"").append(preferredSize * 2).append("\"/>")
                        .append("<w:lang w:val=\"he-IL\"/></w:rPr><w:t xml:space=\"preserve\">")
                        .append(xml(line)).append("</w:t></w:r></w:p>");
            }
            doc.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>")
                    .append("<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/>")
                    .append("</w:sectPr></w:body></w:document>");
            addZipEntry(zip, "word/document.xml", doc.toString());
            toast("מסמך Word נשמר. אפשר לפתוח אותו ב־Word או בעורך תואם DOCX.");
        } catch (Exception e) {
            toast("לא ניתן ליצור את המסמך. נסה לבחור מיקום שמירה אחר.");
        }
    }

    private String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private void addZipEntry(ZipOutputStream zip, String name, String data) throws java.io.IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override protected void onPause() {
        super.onPause();
        if (transcriptEditor != null) prefs.edit().putString("draft", transcriptEditor.getText().toString()).apply();
    }

    @Override protected void onDestroy() {
        if (recording) stopRecording();
        super.onDestroy();
    }
}

package org.koltorah.app;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Converts common local audio files to Whisper's required 16 kHz mono PCM WAV format. */
final class AudioConverter {
    private AudioConverter() {}

    static void prepare(Context context, Uri uri, String displayName, File target) throws Exception {
        if (displayName != null && displayName.toLowerCase(java.util.Locale.ROOT).endsWith(".wav")) {
            try (InputStream input = new BufferedInputStream(context.getContentResolver().openInputStream(uri));
                 java.io.OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
                if (input == null) throw new java.io.IOException("לא ניתן לפתוח את קובץ השמע");
                byte[] bytes = new byte[65536];
                int n;
                while ((n = input.read(bytes)) != -1) output.write(bytes, 0, n);
            }
            if (!isPcmWav16(target)) {
                throw new java.io.IOException("קובץ WAV זה אינו PCM ‏16-bit. נסה לייבא MP3/M4A או WAV רגיל.");
            }
            return;
        }
        decode(context, uri, target);
        if (!isPcmWav16(target)) throw new java.io.IOException("המרת השמע לא יצרה קובץ PCM תקין.");
    }

    private static boolean isPcmWav16(File file) {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            if (in.length() < 44) return false;
            byte[] h = new byte[12];
            in.readFully(h);
            if (h[0] != 'R' || h[1] != 'I' || h[2] != 'F' || h[3] != 'F' ||
                h[8] != 'W' || h[9] != 'A' || h[10] != 'V' || h[11] != 'E') return false;
            int format = 0, channels = 0, bits = 0, rate = 0;
            boolean hasData = false;
            while (in.getFilePointer() + 8 <= in.length()) {
                byte[] ch = new byte[8];
                in.readFully(ch);
                String tag = new String(ch, 0, 4, StandardCharsets.US_ASCII);
                long size = ((long) ch[4] & 255) | (((long) ch[5] & 255) << 8) |
                    (((long) ch[6] & 255) << 16) | (((long) ch[7] & 255) << 24);
                long payload = in.getFilePointer();
                if (payload + size > in.length()) return false;
                if ("fmt ".equals(tag)) {
                    if (size < 16) return false;
                    byte[] fmt = new byte[16];
                    in.readFully(fmt);
                    format = (fmt[0] & 255) | ((fmt[1] & 255) << 8);
                    channels = (fmt[2] & 255) | ((fmt[3] & 255) << 8);
                    rate = (fmt[4] & 255) | ((fmt[5] & 255) << 8) |
                        ((fmt[6] & 255) << 16) | ((fmt[7] & 255) << 24);
                    bits = (fmt[14] & 255) | ((fmt[15] & 255) << 8);
                } else if ("data".equals(tag)) {
                    hasData = size > 0;
                }
                in.seek(payload + size + (size & 1));
                if (hasData && format != 0) break;
            }
            return hasData && format == 1 && bits == 16 && channels >= 1 && channels <= 2 &&
                rate >= 8000 && rate <= 192000;
        } catch (Exception e) {
            return false;
        }
    }

    private static void decode(Context context, Uri uri, File target) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        BufferedOutputStream output = null;
        boolean started = false;
        long dataBytes = 0;
        try {
            extractor.setDataSource(context, uri, null);
            int track = -1;
            MediaFormat sourceFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    sourceFormat = candidate;
                    break;
                }
            }
            if (track < 0 || sourceFormat == null) throw new java.io.IOException("לא נמצא ערוץ שמע בקובץ");
            extractor.selectTrack(track);
            String mime = sourceFormat.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(sourceFormat, null, null, 0);
            decoder.start();
            started = true;

            output = new BufferedOutputStream(new FileOutputStream(target), 1024 * 1024);
            output.write(new byte[44]);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            int sampleRate = sourceFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = sourceFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
            long phase = 0;
            byte[] littleEndian = new byte[2];

            while (!outputDone) {
                if (!inputDone) {
                    int index = decoder.dequeueInputBuffer(10000);
                    if (index >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(index);
                        if (buffer == null) throw new java.io.IOException("מנוע השמע לא סיפק חוצץ קלט");
                        buffer.clear();
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(index, 0, size, Math.max(0, extractor.getSampleTime()), 0);
                            extractor.advance();
                        }
                    }
                }

                int outIndex = decoder.dequeueOutputBuffer(info, 10000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat format = decoder.getOutputFormat();
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (Build.VERSION.SDK_INT >= 24 && format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEncoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                    if (sampleRate <= 0 || channels <= 0 || channels > 8) throw new java.io.IOException("מאפייני השמע אינם נתמכים");
                    if (pcmEncoding != AudioFormat.ENCODING_PCM_16BIT &&
                        pcmEncoding != AudioFormat.ENCODING_PCM_8BIT &&
                        pcmEncoding != AudioFormat.ENCODING_PCM_FLOAT) {
                        throw new java.io.IOException("קידוד השמע מפוענח לפורמט שאינו נתמך במכשיר");
                    }
                    continue;
                }
                if (outIndex >= 0) {
                    ByteBuffer pcm = decoder.getOutputBuffer(outIndex);
                    if (pcm != null && info.size > 0) {
                        pcm.position(info.offset);
                        pcm.limit(info.offset + info.size);
                        pcm.order(ByteOrder.LITTLE_ENDIAN);
                        int bytesPerSample = pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 :
                            pcmEncoding == AudioFormat.ENCODING_PCM_8BIT ? 1 : 2;
                        int frameSize = bytesPerSample * channels;
                        while (pcm.remaining() >= frameSize) {
                            float sum = 0f;
                            for (int channel = 0; channel < channels; channel++) {
                                float value;
                                if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                    value = pcm.getFloat();
                                } else if (pcmEncoding == AudioFormat.ENCODING_PCM_8BIT) {
                                    value = ((pcm.get() & 255) - 128) / 128.0f;
                                } else {
                                    value = pcm.getShort() / 32768.0f;
                                }
                                sum += value;
                            }
                            float mono = Math.max(-1f, Math.min(1f, sum / channels));
                            short sample = (short) (mono * (mono < 0 ? 32768 : 32767));
                            phase += 16000;
                            while (phase >= sampleRate) {
                                littleEndian[0] = (byte) (sample & 255);
                                littleEndian[1] = (byte) ((sample >>> 8) & 255);
                                output.write(littleEndian);
                                dataBytes += 2;
                                phase -= sampleRate;
                            }
                        }
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    decoder.releaseOutputBuffer(outIndex, false);
                    if (eos) outputDone = true;
                }
            }
            output.flush();
            output.close();
            output = null;
            try (RandomAccessFile wav = new RandomAccessFile(target, "rw")) {
                writeHeader(wav, dataBytes, 16000, 1, 16);
            }
            if (dataBytes < 3200) throw new java.io.IOException("לא נמצא מספיק שמע בקובץ");
        } finally {
            if (output != null) try { output.close(); } catch (Exception ignored) {}
            if (decoder != null) {
                if (started) try { decoder.stop(); } catch (Exception ignored) {}
                try { decoder.release(); } catch (Exception ignored) {}
            }
            try { extractor.release(); } catch (Exception ignored) {}
        }
    }

    private static void writeHeader(RandomAccessFile out, long dataBytes, int rate, int channels, int bits) throws java.io.IOException {
        out.seek(0);
        out.writeBytes("RIFF");
        writeInt(out, (int) (36 + dataBytes));
        out.writeBytes("WAVEfmt ");
        writeInt(out, 16);
        writeShort(out, 1);
        writeShort(out, channels);
        writeInt(out, rate);
        writeInt(out, rate * channels * bits / 8);
        writeShort(out, channels * bits / 8);
        writeShort(out, bits);
        out.writeBytes("data");
        writeInt(out, (int) dataBytes);
    }

    private static void writeInt(RandomAccessFile out, int n) throws java.io.IOException {
        out.write(n & 255); out.write((n >>> 8) & 255); out.write((n >>> 16) & 255); out.write((n >>> 24) & 255);
    }
    private static void writeShort(RandomAccessFile out, int n) throws java.io.IOException {
        out.write(n & 255); out.write((n >>> 8) & 255);
    }
}

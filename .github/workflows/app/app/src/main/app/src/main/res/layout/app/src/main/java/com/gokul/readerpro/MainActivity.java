package com.gokul.readerpro;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private TextView tvStatus;
    private TextView tvContent;
    private Button btnPlayPause;

    private TextToSpeech tts;
    private boolean isTtsReady = false;
    private boolean isReading = false;
    private float speechRate = 1.0f;

    private final List<String> paragraphs = new ArrayList<>();
    private int currentIndex = 0;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String[]> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    try {
                        getContentResolver().takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ignored) {
                    }
                    loadDocumentFromUri(uri);
                } else {
                    announceStatus("कोणतीही फाईल निवडली नाही.");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        PDFBoxResourceLoader.init(getApplicationContext());

        tvStatus = findViewById(R.id.tvStatus);
        tvContent = findViewById(R.id.tvContent);
        btnPlayPause = findViewById(R.id.btnPlayPause);

        Button btnOpenFile = findViewById(R.id.btnOpenFile);
        Button btnPrev = findViewById(R.id.btnPrev);
        Button btnNext = findViewById(R.id.btnNext);
        Button btnSpeedDown = findViewById(R.id.btnSpeedDown);
        Button btnSpeedUp = findViewById(R.id.btnSpeedUp);
        Button btnStop = findViewById(R.id.btnStop);
        Button btnTtsSettings = findViewById(R.id.btnTtsSettings);

        tts = new TextToSpeech(this, this);

        btnOpenFile.setOnClickListener(v ->
                filePickerLauncher.launch(new String[]{"application/pdf", "text/plain"}));

        btnPlayPause.setOnClickListener(v -> togglePlayPause());

        btnPrev.setOnClickListener(v -> {
            if (paragraphs.isEmpty()) {
                announceStatus("आधी पीडीएफ किंवा मजकूर फाईल निवडा.");
                return;
            }
            if (currentIndex > 0) {
                currentIndex--;
                isReading = true;
                updatePlayPauseLabel();
                speakCurrentParagraph();
            } else {
                announceStatus("हा पहिलाच परिच्छेद आहे.");
            }
        });

        btnNext.setOnClickListener(v -> {
            if (paragraphs.isEmpty()) {
                announceStatus("आधी पीडीएफ किंवा मजकूर फाईल निवडा.");
                return;
            }
            if (currentIndex < paragraphs.size() - 1) {
                currentIndex++;
                isReading = true;
                updatePlayPauseLabel();
                speakCurrentParagraph();
            } else {
                announceStatus("हा शेवटचा परिच्छेद आहे.");
            }
        });

        btnSpeedDown.setOnClickListener(v -> adjustSpeed(-0.2f));
        btnSpeedUp.setOnClickListener(v -> adjustSpeed(0.2f));

        btnStop.setOnClickListener(v -> {
            stopReading();
            currentIndex = 0;
            announceStatus("वाचन पूर्ण बंद केले आहे.");
        });

        btnTtsSettings.setOnClickListener(v -> {
            stopReading();
            try {
                Intent intent = new Intent("com.android.settings.TTS_SETTINGS");
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                announceStatus("TTS सेटिंग उघडता आली नाही.");
            }
        });

        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action)) {
            Uri uri = intent.getData();
            if (uri != null) {
                loadDocumentFromUri(uri);
            }
        } else if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) {
                loadDocumentFromUri(uri);
            } else {
                String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
                if (sharedText != null && !sharedText.trim().isEmpty()) {
                    processExtractedText("शेअर केलेला मजकूर", sharedText);
                }
            }
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int langResult = tts.setLanguage(new Locale("mr", "IN"));
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.getDefault());
            }
            tts.setSpeechRate(speechRate);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                }

                @Override
                public void onDone(String utteranceId) {
                    if ("PARA_READ".equals(utteranceId) && isReading) {
                        runOnUiThread(() -> {
                            if (currentIndex < paragraphs.size() - 1) {
                                currentIndex++;
                                speakCurrentParagraph();
                            } else {
                                isReading = false;
                                updatePlayPauseLabel();
                                announceStatus("संपूर्ण दस्तऐवज वाचून पूर्ण झाले आहे.");
                            }
                        });
                    }
                }

                @Override
                public void onError(String utteranceId) {
                    runOnUiThread(() -> {
                        isReading = false;
                        updatePlayPauseLabel();
                        announceStatus("आवाज वाचताना तांत्रिक अडचण आली.");
                    });
                }
            });
            isTtsReady = true;
        } else {
            announceStatus("TTS इंजिन सुरू होऊ शकले नाही. कृपया TTS सेटिंग तपासा.");
        }
    }

    private void loadDocumentFromUri(Uri uri) {
        stopReading();
        String fileName = getFileName(uri);
        announceStatus(fileName + " उघडत आहे, कृपया थोडा वेळ थांबा...");

        executor.execute(() -> {
            try {
                String mimeType = getContentResolver().getType(uri);
                boolean isPdf = (mimeType != null && mimeType.contains("pdf"))
                        || fileName.toLowerCase(Locale.ROOT).endsWith(".pdf");

                String extractedText;
                if (isPdf) {
                    try (InputStream inputStream = getContentResolver().openInputStream(uri);
                         PDDocument document = PDDocument.load(inputStream)) {
                        PDFTextStripper stripper = new PDFTextStripper();
                        stripper.setSortByPosition(true);
                        extractedText = stripper.getText(document);
                    }
                } else {
                    StringBuilder sb = new StringBuilder();
                    try (InputStream inputStream = getContentResolver().openInputStream(uri);
                         BufferedReader reader = new BufferedReader(
                                 new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line).append("\n");
                        }
                    }
                    extractedText = sb.toString();
                }

                final String finalResult = extractedText;
                runOnUiThread(() -> processExtractedText(fileName, finalResult));

            } catch (Exception e) {
                runOnUiThread(() -> {
                    tvContent.setText("फाईल वाचता आली नाही: " + e.getMessage());
                    announceStatus("ही फाईल उघडता आली नाही किंवा ती सुरक्षित पासवर्ड लॉक असू शकते.");
                });
            }
        });
    }

    private void processExtractedText(String fileName, String rawText) {
        paragraphs.clear();
        currentIndex = 0;

        if (rawText != null) {
            String[] lines = rawText.split("\\r?\\n");
            StringBuilder currentPara = new StringBuilder();

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    if (currentPara.length() > 0) {
                        paragraphs.add(currentPara.toString().trim());
                        currentPara.setLength(0);
                    }
                } else {
                    if (currentPara.length() > 0) {
                        currentPara.append(" ");
                    }
                    currentPara.append(trimmed);
                    if (trimmed.endsWith(".") || trimmed.endsWith("।") || trimmed.endsWith("?") || trimmed.endsWith("!")) {
                        if (currentPara.length() > 140) {
                            paragraphs.add(currentPara.toString().trim());
                            currentPara.setLength(0);
                        }
                    }
                }
            }
            if (currentPara.length() > 0) {
                paragraphs.add(currentPara.toString().trim());
            }
        }

        if (paragraphs.isEmpty()) {
            tvContent.setText("या फाईलमध्ये वाचण्यायोग्य मजकूर सापडला नाही (ही स्कॅन केलेली इमेज पीडीएफ असू शकते).");
            announceStatus("फाईलमध्ये कोणताही मजकूर आढळला नाही.");
            return;
        }

        StringBuilder fullDisplay = new StringBuilder();
        for (int i = 0; i < paragraphs.size(); i++) {
            fullDisplay.append("[").append(i + 1).append("] ")
                    .append(paragraphs.get(i)).append("\n\n");
        }
        tvContent.setText(fullDisplay.toString());

        announceStatus(fileName + " तयार आहे. एकूण " + paragraphs.size() + " परिच्छेद आहेत. वाचन सुरू करत आहे.");
        isReading = true;
        updatePlayPauseLabel();
        speakCurrentParagraph();
    }

    private void togglePlayPause() {
        if (paragraphs.isEmpty()) {
            announceStatus("आधी पीडीएफ किंवा मजकूर फाईल निवडा.");
            return;
        }
        if (isReading) {
            stopReading();
            announceStatus("वाचन थांबवले आहे. परिच्छेद " + (currentIndex + 1));
        } else {
            isReading = true;
            updatePlayPauseLabel();
            speakCurrentParagraph();
        }
    }

    private void speakCurrentParagraph() {
        if (!isTtsReady || paragraphs.isEmpty() || currentIndex < 0 || currentIndex >= paragraphs.size()) {
            return;
        }
        String textToRead = paragraphs.get(currentIndex);
        tvStatus.setText("वाचत आहे: परिच्छेद " + (currentIndex + 1) + " / " + paragraphs.size());
        tvContent.setText("परिच्छेद " + (currentIndex + 1) + " / " + paragraphs.size() + ":\n\n" + textToRead);

        Bundle params = new Bundle();
        tts.setSpeechRate(speechRate);
        tts.speak(textToRead, TextToSpeech.QUEUE_FLUSH, params, "PARA_READ");
    }

    private void stopReading() {
        isReading = false;
        updatePlayPauseLabel();
        if (tts != null) {
            tts.stop();
        }
    }

    private void adjustSpeed(float delta) {
        speechRate = Math.max(0.5f, Math.min(3.0f, Math.round((speechRate + delta) * 10f) / 10f));
        if (tts != null) {
            tts.setSpeechRate(speechRate);
        }
        String msg = "वाचनाचा वेग " + speechRate + " केला आहे.";
        announceStatus(msg);
        if (isReading) {
            speakCurrentParagraph();
        }
    }

    private void updatePlayPauseLabel() {
        btnPlayPause.setText(isReading ? "थांबा (Pause)" : "वाचा (Play)");
    }

    private void announceStatus(String message) {
        tvStatus.setText(message);
        tvStatus.announceForAccessibility(message);
        if (isTtsReady && !isReading) {
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "STATUS_MSG");
        }
    }

    private String getFileName(Uri uri) {
        String result = "दस्तऐवज";
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        result = cursor.getString(nameIndex);
                    }
                }
            } catch (Exception ignored) {
            }
        } else if (uri.getPath() != null) {
            int cut = uri.getPath().lastIndexOf('/');
            if (cut != -1) {
                result = uri.getPath().substring(cut + 1);
            }
        }
        return result;
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        executor.shutdownNow();
        super.onDestroy();
    }
}


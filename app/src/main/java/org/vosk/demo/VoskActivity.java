// Copyright 2019 Alpha Cephei Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.vosk.demo;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.renderscript.ScriptGroup;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ToggleButton;

import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.SpeakerModel;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.SpeechStreamService;
import org.vosk.android.StorageService;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import static org.vosk.android.StorageService.sync;

public class VoskActivity extends Activity implements
        RecognitionListener {

    static private final int STATE_START = 0;
    static private final int STATE_READY = 1;
    static private final int STATE_DONE = 2;
    static private final int STATE_FILE = 3;
    static private final int STATE_MIC = 4;

    /* Used to handle permission request */
    private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;

    private Model model;
    private SpeakerModel spkMod;
    private SpeechService speechService;
    private SpeechStreamService speechStreamService;
    private TextView resultView;
    private StorageService ss;

    Map<String, Double[]> dataMap = new HashMap<String, Double[]>();
    ArrayList<String> names = new ArrayList<String>();
    File dataDir;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.main);

        // Setup layout
        resultView = findViewById(R.id.result_text);
        setUiState(STATE_START);

        findViewById(R.id.recognize_file).setOnClickListener(view -> recognizeFile());
        findViewById(R.id.recognize_mic).setOnClickListener(view -> recognizeMicrophone());
        ((ToggleButton) findViewById(R.id.pause)).setOnCheckedChangeListener((view, isChecked) -> pause(isChecked));

        LibVosk.setLogLevel(LogLevel.INFO);

        // Check if user has given permission to record audio, init the model after permission is granted
        int permissionCheck = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.RECORD_AUDIO);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
        } else {
            initModel();
            initSpk();
        }
        dataDir = new File(getFilesDir(), "dataDir");
        System.out.println(dataDir.getAbsolutePath().toString());
    }

    private void initModel() {
        ss.unpack(this, "model-en-us", "model",
                (model) -> {
                    this.model = model;
                    setUiState(STATE_READY);
                },
                (exception) -> setErrorState("Failed to unpack the model" + exception.getMessage()));
    }

    private void initSpk() {
        unpackS(this, "spk-model", "spkMod",
                (spkMod) -> {
                    this.spkMod = spkMod;
                    setUiState(STATE_READY);
                },
                (exception) -> setErrorState("Failed to unpack the model" + exception.getMessage()));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Recognizer initialization is a time-consuming and it involves IO,
                // so we execute it in async task
                initModel();
                initSpk();
            } else {
                finish();
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (speechService != null) {
            speechService.stop();
            speechService.shutdown();
        }

        if (speechStreamService != null) {
            speechStreamService.stop();
        }
    }


    //this method is for getting results after using the microphone
    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public void onResult(String hypothesis) {
        resultView.append(hypothesis + "\n");
        if (hypothesis.contains("\"spk\"")) {
            hypothesis = hypothesis.replaceAll(" ", "");
            String hypothesis2 = hypothesis.substring(hypothesis.indexOf("\"spk\""), hypothesis.lastIndexOf("]") + 1);
            String[] stringDouble = hypothesis2.substring(hypothesis2.indexOf("[") + 1, hypothesis2.lastIndexOf("]")).split(",");
            System.out.println(Arrays.toString(stringDouble));
            Double[] doubleValues = Arrays.stream(stringDouble).map(Double::valueOf).toArray(Double[]::new);

            File[] speakerFiles = dataDir.listFiles();
            if (speakerFiles.length == 0) {
                System.out.println("There are no registered speakers!");
            } else {
                for (File spkFile : speakerFiles) {
                    try {
                        BufferedReader reader = new BufferedReader(new FileReader(spkFile));
                        String line = reader.readLine();
                        System.out.println(line);
                        if (line.contains("[") && line.contains("]")) {
                            String[] dirDoubles = line.substring(line.indexOf("[") + 1, line.lastIndexOf("]")).split(",");
                            Double[] dirDoubleValues = Arrays.stream(dirDoubles).map(Double::valueOf).toArray(Double[]::new);
                            double diff = cosineSimilarity(dirDoubleValues, doubleValues);
                            if (diff > 0.27) {
                                System.out.println("The Speaker is " + spkFile.getName() + " - Similarity = " + diff);
                                resultView.append("The Speaker is " + spkFile.getName() + " - Similarity = " + diff + "\n");
                            }
                        }
                    } catch (IOException e) {
                        setErrorState(e.getMessage());
                    }
                }
            }
        }
    }


    //this method is for getting results after reading in a file
    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public void onFinalResult(String hypothesis) {
        resultView.append(hypothesis + "\n");
        if (hypothesis.contains("\"spk\"")) {
            hypothesis = hypothesis.replaceAll(" ", "");
            String hypothesis2 = hypothesis.substring(hypothesis.indexOf("\"spk\""), hypothesis.lastIndexOf("]") + 1);
            System.out.println(hypothesis2);
            String[] stringDouble = hypothesis2.substring(hypothesis2.indexOf("[") + 1, hypothesis2.lastIndexOf("]")).split(",");
            Double[] doubleValues = Arrays.stream(stringDouble).map(Double::valueOf).toArray(Double[]::new);
            if (names.size() >= 1) {
                checkExistingSignatures(dataDir, doubleValues, names.remove(names.size()-1));
            }
        }
        setUiState(STATE_DONE);
        if (speechStreamService != null) {
            speechStreamService = null;
        }
    }

    @Override
    public void onPartialResult(String hypothesis) {
        resultView.append(hypothesis + "\n");
    }

    @Override
    public void onError(Exception e) {
        setErrorState(e.getMessage());
    }

    @Override
    public void onTimeout() {
        setUiState(STATE_DONE);
    }

    private void setUiState(int state) {
        switch (state) {
            case STATE_START:
                resultView.setText(R.string.preparing);
                resultView.setMovementMethod(new ScrollingMovementMethod());
                findViewById(R.id.recognize_file).setEnabled(false);
                findViewById(R.id.recognize_mic).setEnabled(false);
                findViewById(R.id.pause).setEnabled((false));
                break;
            case STATE_READY:
                resultView.setText(R.string.ready);
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
                findViewById(R.id.recognize_file).setEnabled(true);
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.pause).setEnabled((false));
                break;
            case STATE_DONE:
                ((Button) findViewById(R.id.recognize_file)).setText(R.string.recognize_file);
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
                findViewById(R.id.recognize_file).setEnabled(true);
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.pause).setEnabled((false));
                break;
            case STATE_FILE:
                ((Button) findViewById(R.id.recognize_file)).setText(R.string.stop_file);
                resultView.setText(getString(R.string.starting));
                findViewById(R.id.recognize_mic).setEnabled(false);
                findViewById(R.id.recognize_file).setEnabled(true);
                findViewById(R.id.pause).setEnabled((false));
                break;
            case STATE_MIC:
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.stop_microphone);
                resultView.setText(getString(R.string.say_something));
                findViewById(R.id.recognize_file).setEnabled(false);
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.pause).setEnabled((true));
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + state);
        }
    }

    private void setErrorState(String message) {
        resultView.setText(message);
        ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
        findViewById(R.id.recognize_file).setEnabled(false);
        findViewById(R.id.recognize_mic).setEnabled(false);
    }

    private void recognizeFile() {
        if (speechStreamService != null) {
            setUiState(STATE_DONE);
            speechStreamService.stop();
            speechStreamService = null;
        } else {
            setUiState(STATE_FILE);
            try {
                AssetManager assetMan = getApplicationContext().getAssets();
                String[] s = assetMan.list("");
                for (String str : s) {
                    if (str.contains(".wav")) {
                        names.add(str);
                    }
                    Recognizer rec = new Recognizer(model, spkMod, 16000.f);
                    InputStream ais = getApplicationContext().getAssets().open(str);
                    if (ais.skip(44) != 44) throw new IOException("File too short");
                    speechStreamService = new SpeechStreamService(rec, ais, 16000);
                    speechStreamService.start(this);
                }
            } catch (IOException e) {
                setErrorState(e.getMessage());
            }
        }
    }

    private void recognizeMicrophone() {
        if (speechService != null) {
            setUiState(STATE_DONE);
            speechService.stop();
            speechService = null;
        } else {
            setUiState(STATE_MIC);
            try {
                Recognizer rec = new Recognizer(model, spkMod, 16000.0f);
                speechService = new SpeechService(rec, 16000.0f);
                speechService.startListening(this);
            } catch (IOException e) {
                setErrorState(e.getMessage());
            }
        }
    }

    public static double cosineSimilarity(Double[] vectorA, Double[] vectorB) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        if (vectorA != null & vectorB != null && vectorA.length == vectorB.length) {
            for (int i = 0; i < vectorA.length; i++) {
                dotProduct += vectorA[i] * vectorB[i];
                normA += Math.pow(vectorA[i], 2);
                normB += Math.pow(vectorB[i], 2);
            }
            return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
        } else {
            return -1;
        }
    }

    private void pause(boolean checked) {
        if (speechService != null) {
            speechService.setPause(checked);
        }
    }

    protected static final String TAG = StorageService.class.getSimpleName();

    public interface Callback<R> {
        void onComplete(R result);
    }

    public static void unpackS(Context context, String sourcePath, final String targetPath, final StorageService.Callback<SpeakerModel> completeCallback, final StorageService.Callback<IOException> errorCallback) {
        Executor executor = Executors.newSingleThreadExecutor(); // change according to your requirements
        Handler handler = new Handler(Looper.getMainLooper());
        executor.execute(() -> {
            try {
                final String outputPath = sync(context, sourcePath, targetPath);
                SpeakerModel model = new SpeakerModel(outputPath);
                handler.post(() -> completeCallback.onComplete(model));
            } catch (final IOException e) {
                handler.post(() -> errorCallback.onComplete(e));
            }
        });
    }

    private void writeToFile(String hypo, String spkName) {
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        try {
            File spkFile = new File(dataDir, spkName + ".txt");
            FileWriter fOut = new FileWriter(spkFile);
            fOut.write(hypo);
            fOut.flush();
            fOut.close();
        } catch (Exception e) {
            setErrorState("writeToFile error");
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void checkExistingSignatures(File spkDir, Double[] vector, String speakerName) {
        if (!spkDir.isDirectory()) {
            writeToFile(vector.toString(), speakerName);
        } else {
            System.out.println(spkDir.getAbsolutePath());
            File[] speakerFiles = spkDir.listFiles();
            if (speakerFiles.length == 0) {
                writeToFile(Arrays.toString(vector), speakerName);
            }
            for (File theFile : speakerFiles) {
                try {
                    BufferedReader reader = new BufferedReader(new FileReader(theFile));
                    String line = reader.readLine();
                    System.out.println(line);
                    if (line.contains("[") && line.contains("]")) {
                        String[] stringDouble = line.substring(line.indexOf("[") + 1, line.lastIndexOf("]")).split(",");
                        Double[] doubleValues = Arrays.stream(stringDouble).map(Double::valueOf).toArray(Double[]::new);
                        double diff = cosineSimilarity(doubleValues, vector);
                        if (diff > 0.27) {
                            System.out.println("This speaker already exists! Updating the speaker file.");
                            resultView.append("This speaker already exists! Updating the speaker file.");
                        } else {
                            System.out.println("Writing to file.");
                            resultView.append("Writing to file.");
                            writeToFile(Arrays.toString(vector), speakerName);
                        }
                    }
                } catch (IOException e) {
                    setErrorState("checkExistingSignatures error");
                }
            }
        }
    }
}

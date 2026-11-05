package com.grinya.voicesampleprep;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.grinya.voicesampleprep.audio.Recorder;
import com.grinya.voicesampleprep.model.AudioClip;
import com.grinya.voicesampleprep.model.Bank;
import com.grinya.voicesampleprep.model.BankManager;

import java.util.Locale;

public class RecordActivity extends AppCompatActivity implements Recorder.Listener {

    public static Bank currentBank;

    private static final int REQ_MIC = 2001;

    private Recorder recorder;
    private BankManager bankManager;
    private Button btnRec, btnDone;
    private TextView txtTime;
    private ProgressBar levelBar;

    private float[] recordedPcm;
    private long recStartTime;
    private final Handler timer = new Handler(Looper.getMainLooper());
    private final Runnable timerTick = new Runnable() {
        @Override public void run() {
            if (recorder != null && recorder.isRecording()) {
                long elapsed = System.currentTimeMillis() - recStartTime;
                txtTime.setText(formatMs((int) elapsed));
                timer.postDelayed(this, 50);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_record);

        if (currentBank == null) { finish(); return; }
        bankManager = new BankManager(this);

        btnRec = findViewById(R.id.btnRec);
        btnDone = findViewById(R.id.btnDone);
        txtTime = findViewById(R.id.txtTime);
        levelBar = findViewById(R.id.levelBar);

        recorder = new Recorder();
        recorder.setListener(this);

        btnRec.setOnClickListener(v -> {
            if (recorder.isRecording()) stopRecording();
            else ensurePermissionAndStart();
        });
        btnDone.setOnClickListener(v -> saveAndFinish());
    }

    private void ensurePermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        startRecording();
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req == REQ_MIC && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording();
        } else if (req == REQ_MIC) {
            Toast.makeText(this, "Нужен доступ к микрофону", Toast.LENGTH_LONG).show();
        }
    }

    private void startRecording() {
        btnRec.setText("■ STOP");
        btnRec.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF444444));
        btnDone.setVisibility(View.GONE);
        recordedPcm = null;
        recStartTime = System.currentTimeMillis();
        timer.post(timerTick);
        recorder.start();
    }

    private void stopRecording() {
        recorder.stop();
        timer.removeCallbacks(timerTick);
        btnRec.setText("● REC");
        btnRec.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFF3B30));
    }

    private void saveAndFinish() {
        if (recordedPcm == null || recordedPcm.length == 0) { finish(); return; }
        try {
            AudioClip clip = new AudioClip("rec", recordedPcm, 48000);
            bankManager.saveClipToBank(currentBank, clip, "rec");
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ---------- Recorder.Listener ----------

    @Override public void onLevel(float rms) {
        int p = (int) Math.min(1000, rms * 5000);
        levelBar.setProgress(p);
    }

    @Override public void onFinished(float[] pcm, int elapsedMs) {
        recordedPcm = pcm;
        txtTime.setText(formatMs(elapsedMs));
        levelBar.setProgress(0);
        btnDone.setVisibility(View.VISIBLE);
    }

    @Override public void onError(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        btnRec.setText("● REC");
    }

    private static String formatMs(int ms) {
        int m = ms / 60000;
        int s = (ms % 60000) / 1000;
        int r = ms % 1000;
        return String.format(Locale.US, "%02d:%02d.%03d", m, s, r);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recorder != null) { recorder.stop(); recorder.release(); }
        timer.removeCallbacks(timerTick);
    }
}
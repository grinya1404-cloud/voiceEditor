package com.grinya.voicesampleprep;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.grinya.voicesampleprep.audio.WavWriter;
import com.grinya.voicesampleprep.edit.EditActivityHolder;
import com.grinya.voicesampleprep.model.AudioClip;
import com.grinya.voicesampleprep.model.AudioMeta;
import com.grinya.voicesampleprep.model.Bank;
import com.grinya.voicesampleprep.model.BankManager;
import com.grinya.voicesampleprep.model.ExportSettings;
import com.grinya.voicesampleprep.util.AudioOps;
import com.grinya.voicesampleprep.util.ExportUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ExportToBankActivity extends AppCompatActivity {

    private BankManager bankManager;
    private AudioClip clip;
    private RecyclerView rv;
    private BankAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_export_to_bank);

        clip = EditActivityHolder.current;
        if (clip == null) { finish(); return; }

        bankManager = new BankManager(this);
        rv = findViewById(R.id.rvBanks);
        adapter = new BankAdapter();
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnNewBank).setOnClickListener(v -> showCreateBankDialog());

        // Если маркеров меньше 2, отключаем режим "по маркерам"
        if (clip.markers.size() < 2) {
            ((RadioButton) findViewById(R.id.rbByMarkers)).setEnabled(false);
            ((RadioButton) findViewById(R.id.rbByMarkers)).setText("По маркерам (нужно ≥ 2 маркера)");
        }

        reloadBanks();
    }

    private void reloadBanks() {
        List<Bank> banks = bankManager.listBanks();
        adapter.setData(banks);
    }

    private void showCreateBankDialog() {
        EditText input = new EditText(this);
        input.setHint("Имя банка");

        new AlertDialog.Builder(this)
                .setTitle("Новый банк")
                .setView(input)
                .setPositiveButton("Создать", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) name = "bank";
                    bankManager.createBank(name);
                    reloadBanks();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void exportToBank(Bank targetBank) {
        boolean byMarkers = ((RadioButton) findViewById(R.id.rbByMarkers)).isChecked();
        List<int[]> ranges = ExportUtils.buildRanges(clip, byMarkers, -1, -1);
        if (ranges.isEmpty()) {
            Toast.makeText(this, "Нечего экспортировать", Toast.LENGTH_SHORT).show();
            return;
        }

        final int[] saved = {0};
        final int[] failed = {0};

        new Thread(() -> {
            for (int idx = 0; idx < ranges.size(); idx++) {
                int[] r = ranges.get(idx);
                try {
                    float[] rangePcm = AudioOps.trim(clip.pcm, r[0], r[1]);

                    String baseName = ExportUtils.stripExt(clip.name);
                    String nameNoExt = baseName + "_" + String.format(java.util.Locale.US, "%03d", idx + 1);

                    File outFile = new File(targetBank.dir, nameNoExt + ".wav");
                    int counter = 1;
                    while (outFile.exists()) {
                        outFile = new File(targetBank.dir, nameNoExt + "_" + counter + ".wav");
                        counter++;
                    }

                    // Внутренний формат банка: 16-bit, 48к, стерео
                    WavWriter.write(outFile, rangePcm, clip.sampleRate, clip.sampleRate, 16, false, false, false, false, false);

                    // .meta
                    AudioMeta meta = new AudioMeta();
                    meta.sampleRate = clip.sampleRate;
                    meta.channels = clip.channels;
                    meta.bits = 16;
                    meta.format = "wav";
                    AudioMeta.save(outFile, meta);

                    saved[0]++;
                } catch (Exception e) {
                    android.util.Log.e("EXPORT_BANK", "Error", e);
                    failed[0]++;
                }
            }

            runOnUiThread(() -> {
                String msg = "Сохранено в банк: " + saved[0];
                if (failed[0] > 0) msg += " (ошибок: " + failed[0] + ")";
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                if (saved[0] > 0) finish();
            });
        }).start();
    }

    // --- Adapter ---

    private class BankAdapter extends RecyclerView.Adapter<BankAdapter.VH> {
        private List<Bank> data = new ArrayList<>();

        void setData(List<Bank> d) {
            data = d;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_bank_picker, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Bank b = data.get(pos);
            h.name.setText(b.name);
            h.info.setText(b.clips.size() + " клипов");
            h.itemView.setOnClickListener(v -> exportToBank(b));
        }

        @Override
        public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView name, info;
            VH(View v) {
                super(v);
                name = v.findViewById(R.id.txtBankName);
                info = v.findViewById(R.id.txtBankInfo);
            }
        }
    }
}
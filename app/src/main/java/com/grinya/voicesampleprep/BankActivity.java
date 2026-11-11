package com.grinya.voicesampleprep;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.grinya.voicesampleprep.audio.WavReader;
import com.grinya.voicesampleprep.edit.EditActivity;
import com.grinya.voicesampleprep.edit.EditActivityHolder;
import com.grinya.voicesampleprep.model.AudioClip;
import com.grinya.voicesampleprep.model.Bank;
import com.grinya.voicesampleprep.model.BankManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import android.widget.EditText;
import com.grinya.voicesampleprep.util.AudioInfoDialog;

public class BankActivity extends AppCompatActivity {

  public static Bank currentBank;

  private static final int REQ_IMPORT = 1001;

  private BankManager bankManager;
  private RecyclerView rv;
  private TextView txtEmpty;
  private ClipAdapter adapter;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_bank);

    if (currentBank == null) {
      finish();
      return;
    }

    bankManager = new BankManager(this);
    rv = findViewById(R.id.rvClips);
    txtEmpty = findViewById(R.id.txtEmpty);
    adapter = new ClipAdapter();
    rv.setLayoutManager(new LinearLayoutManager(this));
    rv.setAdapter(adapter);

    ((TextView) findViewById(R.id.txtBankTitle)).setText(currentBank.name);
    ((ImageButton) findViewById(R.id.btnBack)).setOnClickListener(v -> finish());

    findViewById(R.id.fabRecord).setOnClickListener(v -> {
      RecordActivity.currentBank = currentBank;
      startActivity(new Intent(this, RecordActivity.class));
    });
    findViewById(R.id.fabImport).setOnClickListener(v -> {
      Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
      i.addCategory(Intent.CATEGORY_OPENABLE);
      i.setType("*/*");
      i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] { "audio/wav", "audio/x-wav", "audio/mpeg",
          "audio/mp4", "audio/aac", "audio/flac", "audio/x-flac", "audio/*" });
      startActivityForResult(i, REQ_IMPORT);
    });
  }

  @Override
  protected void onResume() {
    super.onResume();
    reload();
  }

  private void reload() {
    currentBank = bankManager.listBanks().stream().filter(b -> b.name.equals(currentBank.name))
        .findFirst().orElse(currentBank);
    adapter.setData(currentBank.clips);
    txtEmpty.setVisibility(currentBank.clips.isEmpty() ? View.VISIBLE : View.GONE);
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == REQ_IMPORT && resultCode == RESULT_OK && data != null) {
      Uri uri = data.getData();
      if (uri == null)
        return;
      new Thread(() -> {
        try {
          File tmp = new File(getCacheDir(), "import_tmp");
          try (InputStream in = getContentResolver().openInputStream(uri);
              FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
              out.write(buf, 0, n);
          }
          String displayName = "imported";
          android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
          if (c != null) {
            int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
            if (c.moveToFirst() && idx >= 0)
              displayName = c.getString(idx);
            c.close();
          }
          File renamed = new File(getCacheDir(), displayName);
          tmp.renameTo(renamed);
          bankManager.importFile(currentBank, renamed);
          renamed.delete();
          runOnUiThread(() -> {
            reload();
            Toast.makeText(this, "Импортировано", Toast.LENGTH_SHORT).show();
          });
        } catch (Exception e) {
          runOnUiThread(
              () -> Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
      }).start();
    }
  }

  private void openClip(AudioClip clip) {
    new Thread(() -> {
      try {
        AudioClip full = bankManager.loadClip(clip.dir);
        runOnUiThread(() -> {
          EditActivityHolder.current = full;
          startActivity(new Intent(this, EditActivity.class));
        });
      } catch (Exception e) {
        runOnUiThread(
            () -> Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show());
      }
    }).start();
  }

  // ---------- adapter ----------

  private class ClipAdapter extends RecyclerView.Adapter<ClipAdapter.VH> {
    private List<AudioClip> data = new java.util.ArrayList<>();

    void setData(List<AudioClip> d) {
      data = d;
      notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
      View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_clip, parent, false);
      return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
      AudioClip c = data.get(pos);
      h.name.setText(c.name);
      h.info.setText(c.dir != null ? (c.dir.length() / 1024) + " KB" : "");
      h.itemView.setOnClickListener(v -> openClip(c));
      h.itemView.setOnLongClickListener(v -> {
        new AlertDialog.Builder(BankActivity.this).setTitle(c.name)
            .setItems(new String[] { "Переименовать", "Инфо", "Удалить" }, (d, which) -> {
              if (which == 0) {
                // --- ПЕРЕИМЕНОВАТЬ ---
                EditText input = new EditText(BankActivity.this);
                String nameOnly = c.name.endsWith(".wav") ? c.name.substring(0, c.name.length() - 4)
                    : c.name;
                input.setText(nameOnly);
                input.setSelection(input.getText().length()); // Курсор в конец

                new AlertDialog.Builder(BankActivity.this).setTitle("Новое имя").setView(input)
                    .setPositiveButton("OK", (dd, ww) -> {
                      String newName = input.getText().toString().trim();
                      if (!newName.isEmpty() && c.dir != null) {
                        String safe = newName.replaceAll("[\\\\/:*?\"<>|]", "_");
                        File newFile = new File(c.dir.getParent(), safe + ".wav");

                        // Проверяем, не существует ли уже такой файл
                        if (newFile.exists()) {
                          Toast.makeText(BankActivity.this, "Файл с таким именем уже есть",
                              Toast.LENGTH_SHORT).show();
                          return;
                        }

                        if (c.dir.renameTo(newFile)) {
                          // Переименовываем и .meta файл
                          File oldMeta = new File(c.dir.getAbsolutePath() + ".meta");
                          File newMeta = new File(newFile.getAbsolutePath() + ".meta");
                          if (oldMeta.exists())
                            oldMeta.renameTo(newMeta);

                          reload(); // Обновить список
                        } else {
                          Toast.makeText(BankActivity.this, "Ошибка переименования",
                              Toast.LENGTH_SHORT).show();
                        }
                      }
                    }).setNegativeButton("Отмена", null).show();

              } else if (which == 1) {
                // --- ИНФО ---
                new Thread(() -> {
                  try {
                    AudioClip full = bankManager.loadClip(c.dir);
                    runOnUiThread(() -> AudioInfoDialog.show(BankActivity.this, full, -1, -1));
                  } catch (Exception e) {
                    runOnUiThread(() -> Toast
                        .makeText(BankActivity.this, "Ошибка загрузки", Toast.LENGTH_SHORT).show());
                  }
                }).start();
              } else {
                // --- УДАЛИТЬ ---
                new AlertDialog.Builder(BankActivity.this).setTitle("Удалить клип?")
                    .setMessage(c.name).setPositiveButton("Удалить", (dd, ww) -> {
                      if (c.dir != null)
                        c.dir.delete();
                      // Удаляем и .meta
                      File meta = new File(c.dir.getAbsolutePath() + ".meta");
                      if (meta.exists())
                        meta.delete();
                      reload();
                    }).setNegativeButton("Отмена", null).show();
              }
            }).show();
        return true;
      });
    }

    @Override
    public int getItemCount() {
      return data.size();
    }

    class VH extends RecyclerView.ViewHolder {
      TextView name, info;

      VH(View v) {
        super(v);
        name = v.findViewById(R.id.txtClipName);
        info = v.findViewById(R.id.txtClipInfo);
      }
    }
  }
}
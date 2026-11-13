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
    findViewById(R.id.btnExportBank).setOnClickListener(v -> {
      if (currentBank == null || currentBank.clips.isEmpty()) {
        Toast.makeText(this, "Банк пуст", Toast.LENGTH_SHORT).show();
        return;
      }
      ExportActivity.setBankSource(currentBank.clips, currentBank.name);
      startActivity(new Intent(this, ExportActivity.class));
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
      importFromUri(data.getData());
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

  private void copyFile(File src, File dst) throws Exception {
    try (java.io.FileInputStream in = new java.io.FileInputStream(src);
        java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) > 0) {
        out.write(buf, 0, n);
      }
    }
  }

  private void renameClip(AudioClip clip) {
    EditText input = new EditText(this);
    String nameOnly = clip.name.endsWith(".wav") ? clip.name.substring(0, clip.name.length() - 4)
        : clip.name;
    input.setText(nameOnly);
    input.setSelection(input.getText().length());

    new AlertDialog.Builder(this).setTitle("Новое имя").setView(input)
        .setPositiveButton("OK", (d, w) -> {
          String newName = input.getText().toString().trim();
          if (newName.isEmpty() || clip.dir == null)
            return;

          String safe = newName.replaceAll("[\\\\/:*?\"<>|]", "_");
          File newFile = new File(clip.dir.getParent(), safe + ".wav");

          if (newFile.exists()) {
            Toast.makeText(this, "Файл с таким именем уже есть", Toast.LENGTH_SHORT).show();
            return;
          }

          if (clip.dir.renameTo(newFile)) {
            File oldMeta = new File(clip.dir.getAbsolutePath() + ".meta");
            File newMeta = new File(newFile.getAbsolutePath() + ".meta");
            if (oldMeta.exists())
              oldMeta.renameTo(newMeta);
            reload();
          } else {
            Toast.makeText(this, "Ошибка переименования", Toast.LENGTH_SHORT).show();
          }
        }).setNegativeButton("Отмена", null).show();
  }

  private void moveClip(AudioClip clip) {
    showBankPicker("Переместить в банк", (targetBank) -> {
      if (currentBank != null && targetBank.name.equals(currentBank.name)) {
        Toast.makeText(this, "Это текущий банк", Toast.LENGTH_SHORT).show();
        return;
      }
      new Thread(() -> {
        try {
          File srcWav = clip.dir;
          File srcMeta = new File(srcWav.getAbsolutePath() + ".meta");
          File dstWav = new File(targetBank.dir, srcWav.getName());
          File dstMeta = new File(targetBank.dir, srcMeta.getName());

          if (srcWav.renameTo(dstWav)) {
            if (srcMeta.exists())
              srcMeta.renameTo(dstMeta);
            runOnUiThread(() -> {
              Toast.makeText(this, "Перемещено в " + targetBank.name, Toast.LENGTH_SHORT).show();
              reload();
            });
          } else {
            runOnUiThread(
                () -> Toast.makeText(this, "Ошибка перемещения", Toast.LENGTH_SHORT).show());
          }
        } catch (Exception e) {
          runOnUiThread(
              () -> Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
      }).start();
    });
  }

  private void copyClip(AudioClip clip) {
    showBankPicker("Копировать в банк", (targetBank) -> {
      new Thread(() -> {
        try {
          File srcWav = clip.dir;
          File srcMeta = new File(srcWav.getAbsolutePath() + ".meta");

          String baseName = srcWav.getName();
          if (baseName.toLowerCase().endsWith(".wav")) {
            baseName = baseName.substring(0, baseName.length() - 4);
          }

          boolean isSameBank = currentBank != null && targetBank.name.equals(currentBank.name);

          String newName = baseName;
          if (isSameBank) {
            // Если копируем в тот же банк, генерируем новое имя
            int counter = 1;
            File testFile = new File(targetBank.dir, newName + "_copy.wav");
            while (testFile.exists()) {
              counter++;
              testFile = new File(targetBank.dir, newName + "_copy" + counter + ".wav");
            }
            newName = newName + "_copy" + (counter > 1 ? String.valueOf(counter) : "");
          }

          File dstWav = new File(targetBank.dir, newName + ".wav");
          File dstMeta = new File(targetBank.dir, newName + ".wav.meta");

          // Если в целевом банке уже есть файл с таким именем (при копировании в другой банк)
          if (!isSameBank && dstWav.exists()) {
            int counter = 1;
            File testFile = new File(targetBank.dir, baseName + "_" + counter + ".wav");
            while (testFile.exists()) {
              counter++;
              testFile = new File(targetBank.dir, baseName + "_" + counter + ".wav");
            }
            dstWav = testFile;
            dstMeta = new File(targetBank.dir, testFile.getName() + ".meta");
          }

          copyFile(srcWav, dstWav);
          if (srcMeta.exists())
            copyFile(srcMeta, dstMeta);

          final String finalName = dstWav.getName();
          runOnUiThread(() -> {
            Toast.makeText(this, "Скопировано: " + finalName, Toast.LENGTH_SHORT).show();
            reload(); // Обновить список (если копировали в текущий банк)
          });
        } catch (Exception e) {
          runOnUiThread(
              () -> Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
      }).start();
    });
  }

  private void showClipInfo(AudioClip clip) {
    new Thread(() -> {
      try {
        AudioClip full = bankManager.loadClip(clip.dir);
        runOnUiThread(() -> AudioInfoDialog.show(this, full, -1, -1));
      } catch (Exception e) {
        runOnUiThread(() -> Toast.makeText(this, "Ошибка загрузки", Toast.LENGTH_SHORT).show());
      }
    }).start();
  }

  private void deleteClip(AudioClip clip) {
    new AlertDialog.Builder(this).setTitle("Удалить клип?").setMessage(clip.name)
        .setPositiveButton("Удалить", (d, w) -> {
          if (clip.dir != null) {
            clip.dir.delete();
            File meta = new File(clip.dir.getAbsolutePath() + ".meta");
            if (meta.exists())
              meta.delete();
          }
          reload();
        }).setNegativeButton("Отмена", null).show();
  }

  private void showBankPicker(String title, BankPickerCallback callback) {
    List<Bank> allBanks = bankManager.listBanks();
    if (allBanks.isEmpty()) {
      Toast.makeText(this, "Нет банков", Toast.LENGTH_SHORT).show();
      return;
    }

    String[] names = new String[allBanks.size()];
    for (int i = 0; i < allBanks.size(); i++) {
      Bank b = allBanks.get(i);
      names[i] = b.name
          + (currentBank != null && b.name.equals(currentBank.name) ? " (текущий)" : "");
    }

    new AlertDialog.Builder(this).setTitle(title).setItems(names, (d, which) -> {
      callback.onBankSelected(allBanks.get(which));
    }).setNegativeButton("Отмена", null).show();
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
            .setItems(new String[] { "Переименовать", "Переместить в банк", "Копировать в банк",
                "Инфо", "Удалить" }, (d, which) -> {
                  switch (which) {
                    case 0:
                      renameClip(c);
                      break;
                    case 1:
                      moveClip(c);
                      break;
                    case 2:
                      copyClip(c);
                      break;
                    case 3:
                      showClipInfo(c);
                      break;
                    case 4:
                      deleteClip(c);
                      break;
                  }
                })
            .show();
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

  public interface BankPickerCallback {
    void onBankSelected(Bank bank);
  }

  private void importFromUri(Uri uri) {
    if (uri == null)
      return;
    android.util.Log.d("IMPORT", "Start import: " + uri);

    android.app.ProgressDialog progress = new android.app.ProgressDialog(this);
    progress.setMessage("Импорт...");
    progress.setCancelable(false);
    progress.show();

    new Thread(() -> {
      try {
        android.util.Log.d("IMPORT", "Copying file...");
        File tmp = new File(getCacheDir(), "import_tmp");
        try (InputStream in = getContentResolver().openInputStream(uri);
            FileOutputStream out = new FileOutputStream(tmp)) {
          byte[] buf = new byte[8192];
          int n;
          while ((n = in.read(buf)) > 0)
            out.write(buf, 0, n);
        }
        android.util.Log.d("IMPORT", "File copied, size=" + tmp.length());

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
        android.util.Log.d("IMPORT", "Decoding: " + renamed.getName());

        bankManager.importFile(currentBank, renamed);
        android.util.Log.d("IMPORT", "Import success");
        renamed.delete();

        runOnUiThread(() -> {
          progress.dismiss();
          reload();
          Toast.makeText(this, "Импортировано", Toast.LENGTH_SHORT).show();
        });
      } catch (Throwable e) {
        android.util.Log.e("IMPORT", "Error", e);
        runOnUiThread(() -> {
          progress.dismiss();
          Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
        });
      }
    }).start();
  }

}
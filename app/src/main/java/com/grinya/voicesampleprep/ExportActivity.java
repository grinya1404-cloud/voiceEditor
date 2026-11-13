package com.grinya.voicesampleprep;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.grinya.voicesampleprep.audio.WavWriter;
import com.grinya.voicesampleprep.model.AudioClip;
import com.grinya.voicesampleprep.model.AudioMeta;
import com.grinya.voicesampleprep.model.BankManager;
import com.grinya.voicesampleprep.util.AudioOps;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import android.widget.Button;

public class ExportActivity extends AppCompatActivity {

  private static final int REQ_TREE = 3001;

  private static java.util.List<com.grinya.voicesampleprep.model.AudioClip> bankClips;
  private static String bankName;

  private static AudioClip source;
  private static int selStart = -1;
  private static int selEnd = -1;
  private AudioMeta originalMeta;
  private boolean isDefaultMode = false;
  private boolean isOriginalMode = false;
  private final java.util.ArrayList<String> recentActions = new java.util.ArrayList<>();

  public static void setSource(AudioClip clip, int s, int e) {
    source = clip;
    selStart = s;
    selEnd = e;
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    if (source == null && (bankClips == null || bankClips.isEmpty())) {
      finish();
      return;
    }
    setContentView(R.layout.activity_export);

    if (source != null) {
      bankClips = null; // сбрасываем режим банка
      // Загружаем .meta для умного экспорта
      originalMeta = AudioMeta.load(source.dir);

      // Автоматически ставим рекомендуемые параметры
      if (originalMeta.channels == 1) {
        ((RadioButton) findViewById(R.id.rbTrueMono)).setChecked(true);
      } else if (originalMeta.channels == 2) {
        ((RadioButton) findViewById(R.id.rbStereo)).setChecked(true);
      }
      if (originalMeta.sampleRate == 44100) {
        ((RadioButton) findViewById(R.id.rb441)).setChecked(true);
      } else if (originalMeta.sampleRate == 48000) {
        ((RadioButton) findViewById(R.id.rb48)).setChecked(true);
      }
      if (originalMeta.bits == 24) {
        ((RadioButton) findViewById(R.id.rb24)).setChecked(true);
      } else if (originalMeta.bits == 16) {
        ((RadioButton) findViewById(R.id.rb16)).setChecked(true);
      }

      // Показываем подсказку, если параметры отличаются от дефолтов
      if (originalMeta.sampleRate != 0 && (originalMeta.sampleRate != 48000
          || originalMeta.channels != 2 || originalMeta.bits != 16)) {
        String hint = "Исходный файл: "
            + (originalMeta.sampleRate == 44100 ? "44.1 кГц" : originalMeta.sampleRate + " Гц")
            + ", " + (originalMeta.channels == 1 ? "моно" : "стерео")
            + (originalMeta.bits > 0 ? ", " + originalMeta.bits + "-bit" : "")
            + ". Рекомендуется экспортировать так же.";
        Toast.makeText(this, hint, Toast.LENGTH_LONG).show();
      }

      // Настройка для одного файла
      if (selStart >= 0 && selEnd > selStart) {
        int durationMs = (selEnd - selStart) * 1000 / source.sampleRate;
        int m = durationMs / 60000;
        int s = (durationMs % 60000) / 1000;
        int r = durationMs % 1000;
        String timeStr = String.format(java.util.Locale.US, "%02d:%02d.%03d", m, s, r);
        ((RadioButton) findViewById(R.id.rbSingle))
            .setText("Один файл (выделение " + timeStr + ")");
      } else {
        ((RadioButton) findViewById(R.id.rbSingle)).setText("Один файл (весь клип)");
      }

      if (source.markers.size() < 2) {
        ((RadioButton) findViewById(R.id.rbByMarkers)).setEnabled(false);
        ((RadioButton) findViewById(R.id.rbByMarkers)).setText("По маркерам (нужно ≥ 2 маркера)");
      }
    } else {
      // Режим банка
      originalMeta = new AudioMeta(); // Пустая мета для безопасности
      ((RadioButton) findViewById(R.id.rbSingle)).setText("Один файл (весь клип)");
      // При экспорте банка "По маркерам" доступно (там будут проверяться маркеры каждого клипа)
    }

    findViewById(R.id.btnExport).setOnClickListener(v -> startExport());
    findViewById(R.id.btnCancel).setOnClickListener(v -> finish());

    findViewById(R.id.btnDefaults).setOnClickListener(v -> {
      ((RadioButton) findViewById(R.id.rb48)).setChecked(true);
      ((RadioButton) findViewById(R.id.rbStereo)).setChecked(true);
      ((RadioButton) findViewById(R.id.rb16)).setChecked(true);
      isDefaultMode = true;
      isOriginalMode = false;
      updateButtonHighlights();
    });

    findViewById(R.id.btnOriginal).setOnClickListener(v -> {
      if (originalMeta.sampleRate == 0 && originalMeta.channels == 0) {
        Toast.makeText(this, "Исходные параметры неизвестны", Toast.LENGTH_SHORT).show();
        return;
      }
      if (originalMeta.sampleRate == 44100)
        ((RadioButton) findViewById(R.id.rb441)).setChecked(true);
      else
        ((RadioButton) findViewById(R.id.rb48)).setChecked(true);

      if (originalMeta.channels == 1)
        ((RadioButton) findViewById(R.id.rbTrueMono)).setChecked(true);
      else if (originalMeta.channels == 2)
        ((RadioButton) findViewById(R.id.rbStereo)).setChecked(true);

      if (originalMeta.bits == 24)
        ((RadioButton) findViewById(R.id.rb24)).setChecked(true);
      else if (originalMeta.bits == 16)
        ((RadioButton) findViewById(R.id.rb16)).setChecked(true);

      isOriginalMode = true;
      isDefaultMode = false;
      updateButtonHighlights();
    });
    // Слушатели для сброса подсветки при ручном изменении
    android.widget.RadioGroup.OnCheckedChangeListener resetListener = (group, checkedId) -> {
      isDefaultMode = false;
      isOriginalMode = false;
      updateButtonHighlights();
    };

    ((android.widget.RadioGroup) findViewById(R.id.rgBits))
        .setOnCheckedChangeListener(resetListener);
    ((android.widget.RadioGroup) findViewById(R.id.rgRate))
        .setOnCheckedChangeListener(resetListener);
    ((android.widget.RadioGroup) findViewById(R.id.rgChannels))
        .setOnCheckedChangeListener(resetListener);
  }

  private void startExport() {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    startActivityForResult(i, REQ_TREE);
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == REQ_TREE && resultCode == Activity.RESULT_OK && data != null) {
      Uri treeUri = data.getData();
      if (treeUri == null)
        return;
      doExport(treeUri);
    }
  }

  private void doExport(Uri treeUri) {
    ExportSettings settings = readSettings();

    if (bankClips != null && !bankClips.isEmpty()) {
      // Режим экспорта всего банка
      exportBank(treeUri, settings);
      return;
    }

    // Режим одного файла (как было)
    List<int[]> ranges = buildRanges(settings.byMarkers);
    if (ranges.isEmpty())
      return;

    final String baseName = stripExt(source.name);
    final List<String> written = new ArrayList<>();
    final int[] failed = { 0 };
    androidx.documentfile.provider.DocumentFile dir = androidx.documentfile.provider.DocumentFile
        .fromTreeUri(this, treeUri);

    new Thread(() -> {
      for (int idx = 0; idx < ranges.size(); idx++) {
        int[] r = ranges.get(idx);
        String fileName = "";
        try {
          fileName = exportSingleRangeFromClip(treeUri, dir, source, r, idx + 1, settings);
          if (fileName != null)
            written.add(fileName);
          else
            failed[0]++;
        } catch (Exception e) {
          android.util.Log.e("EXPORT", "Failed for " + fileName, e);
          failed[0]++;
        }
      }
      runOnUiThread(() -> showExportResult(written.size(), failed[0]));
    }).start();
  }

  private void exportBank(Uri treeUri, ExportSettings settings) {
    final List<String> written = new ArrayList<>();
    final int[] failed = { 0 };
    androidx.documentfile.provider.DocumentFile dir = androidx.documentfile.provider.DocumentFile
        .fromTreeUri(this, treeUri);

    new Thread(() -> {
      for (AudioClip clip : bankClips) {
        try {
          // Если у клипа есть маркеры и выбран режим "по маркерам"
          if (settings.byMarkers && clip.markers != null && clip.markers.size() >= 2) {
            // Загружаем клип (если нужно, т.к. в списке банка только метаданные)
            AudioClip fullClip = new BankManager(this).loadClip(clip.dir);
            List<Integer> all = new ArrayList<>(fullClip.markers);
            java.util.TreeSet<Integer> ts = new java.util.TreeSet<>(all);
            Integer[] arr = ts.toArray(new Integer[0]);

            for (int i = 0; i < arr.length - 1; i++) {
              if (arr[i + 1] > arr[i]) {
                int[] r = { arr[i], arr[i + 1] };
                String fileName = exportSingleRangeFromClip(treeUri, dir, fullClip, r, i + 1,
                    settings);
                if (fileName != null)
                  written.add(fileName);
                else
                  failed[0]++;
              }
            }
          } else {
            // Экспортируем весь клип как один файл
            AudioClip fullClip = new BankManager(this).loadClip(clip.dir);
            int[] r = { 0, fullClip.frameCount() };
            String fileName = exportSingleRangeFromClip(treeUri, dir, fullClip, r, 1, settings);
            if (fileName != null)
              written.add(fileName);
            else
              failed[0]++;
          }
        } catch (Exception e) {
          android.util.Log.e("EXPORT", "Failed for " + clip.name, e);
          failed[0]++;
        }
      }
      runOnUiThread(() -> showExportResult(written.size(), failed[0]));
    }).start();
  }

  // --- Вспомогательный класс для настроек ---
  private static class ExportSettings {
    boolean byMarkers;
    int bits;
    int rate;
    boolean stereoMono;
    boolean trueMono;
    boolean normalize;
    boolean fade;
    boolean trimSilence;
    boolean denoise;
    String nameTemplate;
  }

  private ExportSettings readSettings() {
    ExportSettings s = new ExportSettings();
    s.byMarkers = ((RadioButton) findViewById(R.id.rbByMarkers)).isChecked();
    s.bits = ((RadioButton) findViewById(R.id.rb24)).isChecked() ? 24 : 16;
    s.rate = ((RadioButton) findViewById(R.id.rb441)).isChecked() ? 44100 : 48000;
    s.stereoMono = ((RadioButton) findViewById(R.id.rbStereoMono)).isChecked();
    s.trueMono = ((RadioButton) findViewById(R.id.rbTrueMono)).isChecked();
    s.normalize = ((CheckBox) findViewById(R.id.cbNormalize)).isChecked();
    s.fade = ((CheckBox) findViewById(R.id.cbFade)).isChecked();
    s.trimSilence = ((CheckBox) findViewById(R.id.cbTrimSilence)).isChecked();
    // Если добавишь чекбокс Denoise:
    s.denoise = findViewById(R.id.cbDenoise) != null
        && ((CheckBox) findViewById(R.id.cbDenoise)).isChecked();

    String raw = ((EditText) findViewById(R.id.edName)).getText().toString().trim();
    s.nameTemplate = raw.isEmpty() ? "{name}_{index}" : raw;
    return s;
  }

  private List<int[]> buildRanges(boolean byMarkers) {
    List<int[]> ranges = new ArrayList<>();
    if (byMarkers) {
      List<Integer> all = new ArrayList<>(source.markers);
      if (all.size() < 2) {
        Toast.makeText(this, "Нужно минимум 2 маркера", Toast.LENGTH_SHORT).show();
        return ranges;
      }
      java.util.TreeSet<Integer> ts = new java.util.TreeSet<>(all);
      Integer[] arr = ts.toArray(new Integer[0]);
      for (int i = 0; i < arr.length - 1; i++) {
        if (arr[i + 1] > arr[i])
          ranges.add(new int[] { arr[i], arr[i + 1] });
      }
    } else {
      if (selStart >= 0 && selEnd > selStart) {
        ranges.add(new int[] { selStart, selEnd });
      } else {
        ranges.add(new int[] { 0, source.frameCount() });
      }
    }
    return ranges;
  }

  private String exportSingleRangeFromClip(Uri treeUri,
      androidx.documentfile.provider.DocumentFile dir, AudioClip clip, int[] r, int index,
      ExportSettings s) throws Exception {
    float[] rangePcm = com.grinya.voicesampleprep.util.AudioOps.trim(clip.pcm, r[0], r[1]);

    String nameNoExt = buildName(s.nameTemplate, stripExt(clip.name), index);
    if (nameNoExt.toLowerCase().endsWith(".wav")) {
      nameNoExt = nameNoExt.substring(0, nameNoExt.length() - 4);
    }
    String fileName = getUniqueName(dir, nameNoExt, ".wav");

    Uri fileUri = createFile(treeUri, fileName);
    if (fileUri == null)
      return null;

    java.io.File tmp = new java.io.File(getCacheDir(), "exp_tmp.wav");
    com.grinya.voicesampleprep.audio.WavWriter.write(tmp, rangePcm, clip.sampleRate, s.rate, s.bits,
        s.stereoMono, s.trueMono, s.normalize, s.fade, s.trimSilence, s.denoise);

    try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(fileUri, "w");
        FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor());
        java.io.FileInputStream in = new java.io.FileInputStream(tmp)) {
      byte[] buf = new byte[65536];
      int n;
      while ((n = in.read(buf)) > 0)
        out.write(buf, 0, n);
    }
    tmp.delete();
    return fileName;
  }

  private void showExportResult(int written, int failed) {
    String msg = "Экспортировано: " + written;
    if (failed > 0)
      msg += " (ошибок: " + failed + ")";
    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    if (written > 0)
      finish();
  }

  private Uri createFile(Uri treeUri, String name) {
    try {
      androidx.documentfile.provider.DocumentFile dir = androidx.documentfile.provider.DocumentFile
          .fromTreeUri(this, treeUri);
      if (dir == null)
        return null;
      androidx.documentfile.provider.DocumentFile f = dir.createFile("audio/wav", name);
      return f != null ? f.getUri() : null;
    } catch (Exception e) {
      return null;
    }
  }

  private static String buildName(String template, String base, int index) {
    String out = template.replace("{name}", base).replace("{index}",
        String.format(java.util.Locale.US, "%03d", index));
    return sanitize(out);
  }

  private static String stripExt(String s) {
    if (s == null)
      return "clip";
    int dot = s.lastIndexOf('.');
    return dot > 0 ? s.substring(0, dot) : s;
  }

  private static String sanitize(String s) {
    if (s == null || s.trim().isEmpty())
      return "clip";
    return s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
  }

  private String getUniqueName(androidx.documentfile.provider.DocumentFile dir, String baseName,
      String ext) {
    if (dir == null)
      return baseName + ext;

    String name = baseName + ext;
    int counter = 1;
    // Пока файл с таким именем существует в папке — увеличиваем счетчик
    while (dir.findFile(name) != null) {
      name = baseName + "_" + counter + ext;
      counter++;
    }
    return name;
  }

  public static void setBankSource(java.util.List<com.grinya.voicesampleprep.model.AudioClip> clips,
      String name) {
    bankClips = clips;
    bankName = name;
    source = null; // сбрасываем одиночный источник
  }

  private void updateButtonHighlights() {
    Button btnDef = findViewById(R.id.btnDefaults);
    Button btnMeta = findViewById(R.id.btnOriginal);

    // Def
    if (isDefaultMode) {
      btnDef.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFFB300));
      btnDef.setTextColor(0xFF0E0E10);
    } else {
      btnDef.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF2A2A2E));
      btnDef.setTextColor(0xFFE8E8EA);
    }

    // Meta
    if (isOriginalMode) {
      btnMeta.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFFB300));
      btnMeta.setTextColor(0xFF0E0E10);
    } else {
      btnMeta.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF2A2A2E));
      btnMeta.setTextColor(0xFFB0B0B0);
    }
  }

}
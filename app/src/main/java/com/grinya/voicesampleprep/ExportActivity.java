package com.grinya.voicesampleprep;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.grinya.voicesampleprep.audio.WavWriter;
import com.grinya.voicesampleprep.model.AudioClip;
import com.grinya.voicesampleprep.util.AudioOps;
import com.grinya.voicesampleprep.model.AudioMeta;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import android.widget.RadioGroup;

public class ExportActivity extends AppCompatActivity {

  private static final int REQ_TREE = 3001;

  private static AudioClip source;
  private static int selStart = -1;
  private static int selEnd = -1;
  private AudioMeta originalMeta;

  public static void setSource(AudioClip clip, int s, int e) {
    source = clip;
    selStart = s;
    selEnd = e;
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    if (source == null) {
      finish();
      return;
    }
    setContentView(R.layout.activity_export);
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
          + (originalMeta.sampleRate == 44100 ? "44.1 кГц" : originalMeta.sampleRate + " Гц") + ", "
          + (originalMeta.channels == 1 ? "моно" : "стерео")
          + (originalMeta.bits > 0 ? ", " + originalMeta.bits + "-bit" : "")
          + ". Рекомендуется экспортировать так же.";
      Toast.makeText(this, hint, Toast.LENGTH_LONG).show();
    }

    if (selStart >= 0 && selEnd > selStart) {
      // Считаем длительность выделения в миллисекундах
      int durationMs = (selEnd - selStart) * 1000 / source.sampleRate;
      int m = durationMs / 60000;
      int s = (durationMs % 60000) / 1000;
      int r = durationMs % 1000;
      String timeStr = String.format(java.util.Locale.US, "%02d:%02d.%03d", m, s, r);

      ((RadioButton) findViewById(R.id.rbSingle)).setText("Один файл (выделение " + timeStr + ")");
    } else {
      ((RadioButton) findViewById(R.id.rbSingle)).setText("Один файл (весь клип)");
    }

    if (source.markers.size() < 2) {
      ((RadioButton) findViewById(R.id.rbByMarkers)).setEnabled(false);
      ((RadioButton) findViewById(R.id.rbByMarkers)).setText("По маркерам (нужно ≥ 2 маркера)");
    }

    findViewById(R.id.btnExport).setOnClickListener(v -> startExport());
    findViewById(R.id.btnCancel).setOnClickListener(v -> finish());
    findViewById(R.id.btnDefaults).setOnClickListener(v -> {
      // Наши дефолты: 48к, Stereo, 16-bit
      ((RadioButton) findViewById(R.id.rb48)).setChecked(true);
      ((RadioButton) findViewById(R.id.rbStereo)).setChecked(true);
      ((RadioButton) findViewById(R.id.rb16)).setChecked(true);
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
    });
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
    boolean byMarkers = ((RadioButton) findViewById(R.id.rbByMarkers)).isChecked();
    int bits = ((RadioButton) findViewById(R.id.rb24)).isChecked() ? 24 : 16;
    int rate = ((RadioButton) findViewById(R.id.rb441)).isChecked() ? 44100 : 48000;

    // Читаем три варианта каналов
    boolean isStereoMono = ((RadioButton) findViewById(R.id.rbStereoMono)).isChecked();
    boolean isTrueMono = ((RadioButton) findViewById(R.id.rbTrueMono)).isChecked();

    boolean denoise = ((CheckBox) findViewById(R.id.cbDenoise)).isChecked();
    final boolean finalDenoise = denoise;
    boolean normalize = ((CheckBox) findViewById(R.id.cbNormalize)).isChecked();
    boolean fade = ((CheckBox) findViewById(R.id.cbFade)).isChecked();
    boolean trimSilence = ((CheckBox) findViewById(R.id.cbTrimSilence)).isChecked();
    String nameTemplateRaw = ((EditText) findViewById(R.id.edName)).getText().toString().trim();
    final String nameTemplate = nameTemplateRaw.isEmpty() ? "{name}_{index}" : nameTemplateRaw;

    // собираем список диапазонов [start, end)
    List<int[]> ranges = new ArrayList<>();
    if (byMarkers) {
      // Берем только маркеры (без начала и конца файла)
      List<Integer> all = new ArrayList<>(source.markers);

      // Если маркеров меньше 2, экспортировать нечего (нельзя построить интервал)
      if (all.size() < 2) {
        Toast.makeText(this, "Нужно минимум 2 маркера", Toast.LENGTH_SHORT).show();
        return;
      }

      java.util.TreeSet<Integer> ts = new java.util.TreeSet<>(all);
      Integer[] arr = ts.toArray(new Integer[0]);

      // Строим интервалы между соседними маркерами
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

    if (ranges.isEmpty()) {
      Toast.makeText(this, "Нечего экспортировать", Toast.LENGTH_SHORT).show();
      return;
    }

    final String baseName = stripExt(source.name);
    final List<String> written = new ArrayList<>();
    final int[] failed = { 0 };

    androidx.documentfile.provider.DocumentFile dir = androidx.documentfile.provider.DocumentFile
        .fromTreeUri(this, treeUri);

    // Финализируем переменные для захвата в поток
    final int finalBits = bits;
    final int finalRate = rate;
    final boolean finalStereoMono = isStereoMono;
    final boolean finalTrueMono = isTrueMono;
    final boolean finalNormalize = normalize;
    final boolean finalFade = fade;
    final boolean finalTrimSilence = trimSilence;

    new Thread(() -> {
      for (int idx = 0; idx < ranges.size(); idx++) {
        int[] r = ranges.get(idx);
        String fileName = "";
        try {
          float[] rangePcm = AudioOps.trim(source.pcm, r[0], r[1]);

          // Формируем имя
          String nameNoExt = buildName(nameTemplate, baseName, idx + 1);
          if (nameNoExt.toLowerCase().endsWith(".wav")) {
            nameNoExt = nameNoExt.substring(0, nameNoExt.length() - 4);
          }
          fileName = getUniqueName(dir, nameNoExt, ".wav");

          Uri fileUri = createFile(treeUri, fileName);
          if (fileUri == null) {
            failed[0]++;
            continue;
          }

          // Временный файл
          java.io.File tmp = new java.io.File(getCacheDir(), "exp_tmp.wav");

          android.util.Log.d("EXPORT",
              "Writing: bits=" + finalBits + " rate=" + finalRate + " stereoMono=" + finalStereoMono
                  + " trueMono=" + finalTrueMono + " normalize=" + finalNormalize + " fade="
                  + finalFade + " trimSilence=" + finalTrimSilence + " pcm.length="
                  + rangePcm.length);

          // Всё делает WavWriter: ресемплинг, сведение, эффекты, запись
          WavWriter.write(tmp, rangePcm, source.sampleRate, finalRate, finalBits, finalStereoMono,
              finalTrueMono, finalNormalize, finalFade, finalTrimSilence);

          // Копируем во временное через SAF
          try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(fileUri, "w");
              FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor());
              java.io.FileInputStream in = new java.io.FileInputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0)
              out.write(buf, 0, n);
          }
          tmp.delete();
          written.add(fileName);

        } catch (Exception e) {
          android.util.Log.e("EXPORT", "Failed for " + fileName, e);
          failed[0]++;
        }
      }

      runOnUiThread(() -> {
        String msg = "Экспортировано: " + written.size();
        if (failed[0] > 0)
          msg += " (ошибок: " + failed[0] + ")";
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        if (written.size() > 0)
          finish();
      });
    }).start();
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
}
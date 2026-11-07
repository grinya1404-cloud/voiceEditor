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

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ExportActivity extends AppCompatActivity {

  private static final int REQ_TREE = 3001;

  private static AudioClip source;
  private static int selStart = -1;
  private static int selEnd = -1;

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

    if (selStart >= 0 && selEnd > selStart) {
      // если есть выделение — по умолчанию «один файл»
      ((RadioButton) findViewById(R.id.rbSingle))
          .setText("Один файл (выделение " + selStart + "–" + selEnd + ")");
    }

    if (source.markers.size() < 2) {
      ((RadioButton) findViewById(R.id.rbByMarkers)).setEnabled(false);
      ((RadioButton) findViewById(R.id.rbByMarkers)).setText("По маркерам (нужно ≥ 2 маркера)");
    }

    findViewById(R.id.btnExport).setOnClickListener(v -> startExport());
    findViewById(R.id.btnCancel).setOnClickListener(v -> finish());
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
    boolean mono = ((RadioButton) findViewById(R.id.rbMono)).isChecked();
    boolean normalize = ((CheckBox) findViewById(R.id.cbNormalize)).isChecked();
    boolean fade = ((CheckBox) findViewById(R.id.cbFade)).isChecked();
    boolean trimSilence = ((CheckBox) findViewById(R.id.cbTrimSilence)).isChecked();
    String nameTemplateRaw = ((EditText) findViewById(R.id.edName)).getText().toString().trim();
    final String nameTemplate = nameTemplateRaw.isEmpty() ? "{name}_{index}" : nameTemplateRaw;

    // собираем список диапазонов [start, end)
    List<int[]> ranges = new ArrayList<>();
    if (byMarkers) {
      List<Integer> all = new ArrayList<>();
      all.add(0);
      all.addAll(source.markers);
      all.add(source.frameCount());
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

    if (ranges.isEmpty()) {
      Toast.makeText(this, "Нечего экспортировать", Toast.LENGTH_SHORT).show();
      return;
    }

    final String baseName = stripExt(source.name);
    final List<String> written = new ArrayList<>();
    final int[] failed = { 0 };

    // Получаем папку один раз перед циклом
    androidx.documentfile.provider.DocumentFile dir = androidx.documentfile.provider.DocumentFile
        .fromTreeUri(this, treeUri);

    new Thread(() -> {
      for (int idx = 0; idx < ranges.size(); idx++) {
        int[] r = ranges.get(idx);
        String fileName = ""; // Выносим наверх, чтобы catch видел

        try {
          float[] rangePcm = AudioOps.trim(source.pcm, r[0], r[1]);

          if (trimSilence)
            rangePcm = trimSilence(rangePcm, 0.003f);
          if (normalize)
            rangePcm = AudioOps.normalizePeak(rangePcm, 0.9f);
          if (fade) {
            int ff = source.sampleRate * 5 / 1000;
            rangePcm = AudioOps.fadeIn(rangePcm, ff);
            rangePcm = AudioOps.fadeOut(rangePcm, ff);
          }

          // Формируем базовое имя без .wav
          String nameNoExt = buildName(nameTemplate, baseName, idx + 1);
          if (nameNoExt.toLowerCase().endsWith(".wav")) {
            nameNoExt = nameNoExt.substring(0, nameNoExt.length() - 4);
          }

          // Ищем уникальное имя (name.wav, name_1.wav...)
          fileName = getUniqueName(dir, nameNoExt, ".wav");

          Uri fileUri = createFile(treeUri, fileName);
          if (fileUri == null) {
            failed[0]++;
            continue;
          }

          // WavWriter пишет в File — обходной путь
          java.io.File tmp = new java.io.File(getCacheDir(), "exp_tmp.wav");
          android.util.Log.d("EXPORT", "Writing: bits=" + bits + " rate=" + rate + " mono=" + mono
              + " pcm.length=" + rangePcm.length);

          WavWriter.write(tmp, rangePcm, source.sampleRate, rate, bits, mono);

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

  /** Простая обрезка тишины по краям с порогом по амплитуде. */
  private static float[] trimSilence(float[] pcm, float threshold) {
    int ch = 2;
    int total = pcm.length / ch;
    int s = 0, e = total;
    while (s < total && Math.abs(pcm[s * ch]) < threshold && Math.abs(pcm[s * ch + 1]) < threshold)
      s++;
    while (e > s && Math.abs(pcm[(e - 1) * ch]) < threshold
        && Math.abs(pcm[(e - 1) * ch + 1]) < threshold)
      e--;
    if (e <= s)
      return pcm;
    float[] out = new float[(e - s) * ch];
    System.arraycopy(pcm, s * ch, out, 0, out.length);
    return out;
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
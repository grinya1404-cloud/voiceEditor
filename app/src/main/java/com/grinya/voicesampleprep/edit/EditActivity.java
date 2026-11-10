package com.grinya.voicesampleprep.edit;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.grinya.voicesampleprep.ExportActivity;
import com.grinya.voicesampleprep.R;
import com.grinya.voicesampleprep.SettingsActivity;
import com.grinya.voicesampleprep.audio.AudioDecoder;
import com.grinya.voicesampleprep.audio.AudioPlayer;
import com.grinya.voicesampleprep.model.AudioClip;
// import com.grinya.voicesampleprep.model.MarkerStore;
import com.grinya.voicesampleprep.model.AudioMeta;
import java.util.Locale;

public class EditActivity extends AppCompatActivity
    implements AudioPlayer.Listener, WaveformView.Listener {

  public static final String EXTRA_CLIP = "clip";

  private AudioClip clip;
  private AudioPlayer player;
  private WaveformView wave;
  private boolean isPaused = false;

  private ImageButton btnPlay, btnPause, btnStop;
  private TextView txtName, txtTime;
  private boolean isChanged = false;

  private static final int REQ_INSERT = 5001;

  private boolean isStereoMode = false;
  private boolean isLeftEnabled = true;
  private boolean isRightEnabled = true;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_edit);

    // Перехват свайпа назад
    getOnBackPressedDispatcher().addCallback(this,
        new androidx.activity.OnBackPressedCallback(true) {
          @Override
          public void handleOnBackPressed() {
            confirmExit();
          }
        });

    Object o = getIntent().getSerializableExtra(EXTRA_CLIP);
    // AudioClip не Serializable — передаём через singleton-holder
    clip = EditActivityHolder.current;

    if (clip == null) {
      finish();
      return;
    }

    // Загружаем метки с диска (только если clip не null)
    if (clip.dir != null) {
      AudioMeta meta = AudioMeta.load(clip.dir);
      clip.markers.clear();
      clip.markers.addAll(meta.markers);
    }

    wave = findViewById(R.id.wave);
    txtName = findViewById(R.id.txtName);
    txtTime = findViewById(R.id.txtTime);
    btnPlay = findViewById(R.id.btnPlay);
    btnPause = findViewById(R.id.btnPause);
    btnStop = findViewById(R.id.btnStop);

    txtName.setText(clip.name);

    player = new AudioPlayer();
    player.setListener(this);
    player.setPcm(clip.pcm);

    wave.setListener(this);
    wave.setData(clip.pcm, clip.sampleRate, clip.markers);

    // android.content.SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS,MODE_PRIVATE);
    // boolean showStereo = prefs.getBoolean(SettingsActivity.KEY_STEREO_WAVES, false);
    // wave.setStereoView(showStereo);

    android.content.SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS,
        MODE_PRIVATE);
    isStereoMode = prefs.getBoolean(SettingsActivity.KEY_STEREO_WAVES, false);
    wave.setStereoView(isStereoMode);
    wave.setZeroCrossingSnap(true);
    player.setChannelMask(isLeftEnabled, isRightEnabled);

    wireToolbar();
    wireTransport();
    wireTools();
    btnPause.setEnabled(false);
  }

  private void wireToolbar() {
    findViewById(R.id.btnBack).setOnClickListener(v -> confirmExit());
    findViewById(R.id.btnUndo).setOnClickListener(v -> {
      if (clip.canUndo()) {
        clip.undo();
        refreshAfterChange();
      }
    });
    findViewById(R.id.btnRedo).setOnClickListener(v -> {
      if (clip.canRedo()) {
        clip.redo();
        refreshAfterChange();
      }
    });
    findViewById(R.id.btnSave).setOnClickListener(v -> {
      try {
        clip.save(); // Сохраняет PCM в WAV
        // MarkerStore.save(clip.dir, clip.markers); // Сохраняет маркеры
        isChanged = false;
        Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show();
      } catch (Exception e) {
        Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
      }
    });
    findViewById(R.id.btnInfo).setOnClickListener(v -> {
      com.grinya.voicesampleprep.util.AudioInfoDialog.show(this, clip);
    });
    findViewById(R.id.btnExport).setOnClickListener(v -> {
      int selStart = wave.hasSelection() ? wave.getSelStart() : -1;
      int selEnd = wave.hasSelection() ? wave.getSelEnd() : -1;
      ExportActivity.setSource(clip, selStart, selEnd);
      startActivity(new Intent(this, ExportActivity.class));
    });
    findViewById(R.id.btnMarker).setOnClickListener(v -> {
      int f = player.getCurrentFrame();
      clip.addMarker(f);
      wave.updateMarkers(clip.markers);
      isChanged = true; // Маркеры изменились
    });
  }

  private void wireTransport() {
    btnPlay.setOnClickListener(v -> {
      int startFrame;
      int endFrame;

      if (wave.hasSelection()) {
        // Играем только выделение
        startFrame = wave.getSelStart();
        endFrame = wave.getSelEnd();
      } else {
        // Играем от курсора до конца
        startFrame = isPaused ? wave.getActivePlayFrame() : wave.getPlayheadFrame();
        endFrame = clip.frameCount();
      }

      player.setPlayRange(startFrame, endFrame);
      player.seekToFrame(startFrame);
      player.play();
      isPaused = false;
    });

    btnPause.setOnClickListener(v -> {
      if (player.isPlaying()) {
        player.pause();
        isPaused = true;
        btnPlay.setEnabled(true);
        btnPause.setEnabled(false);
      }
    });

    btnStop.setOnClickListener(v -> {
      player.stop();
      isPaused = false;
      int ph = wave.getPlayheadFrame();
      wave.snapActivePlayheadFrame(ph);
      wave.setPlayheadFrame(ph);
      updateTimeLabel();
      btnPlay.setEnabled(true);
      btnPause.setEnabled(false);
    });

    findViewById(R.id.btnZoomIn).setOnClickListener(v -> {
      wave.zoomInAroundCenter(2.0);
    });
    findViewById(R.id.btnZoomOut).setOnClickListener(v -> {
      wave.zoomInAroundCenter(0.5);
    });
  }

  private void wireTools() {
    findViewById(R.id.btnTrim).setOnClickListener(v -> {
      if (!requireSelection())
        return;
      clip.trim(wave.getSelStart(), wave.getSelEnd());
      wave.clearSelection(); // снятие выделения
      refreshAfterChange();
    });
    findViewById(R.id.btnCut).setOnClickListener(v -> {
      if (!requireSelection())
        return;
      clip.delete(wave.getSelStart(), wave.getSelEnd());
      wave.clearSelection(); // снятие выделения
      refreshAfterChange();
    });
    findViewById(R.id.btnInsert).setOnClickListener(v -> {
      Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
      i.addCategory(Intent.CATEGORY_OPENABLE);
      i.setType("*/*");
      i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] { "audio/wav", "audio/x-wav", "audio/mpeg",
          "audio/mp4", "audio/aac", "audio/flac", "audio/x-flac", "audio/*" });
      startActivityForResult(i, REQ_INSERT);
    });
    findViewById(R.id.btnSilence).setOnClickListener(v -> {
      if (!requireSelection())
        return;
      clip.silence(wave.getSelStart(), wave.getSelEnd(), applyL(), applyR());
      refreshAfterChange();
    });
    findViewById(R.id.btnGain).setOnClickListener(v -> showGainDialog());
    findViewById(R.id.btnNormalize).setOnClickListener(v -> showNormalizeDialog());
    findViewById(R.id.btnDenoise).setOnClickListener(v -> {
      if (clip.pcm == null || clip.pcm.length == 0)
        return;

      boolean hasSel = wave.hasSelection();

      new AlertDialog.Builder(this)
          .setTitle(R.string.denoise_dialog_title)
          .setItems(new String[] {
              hasSel ? R.string.denoise_apply_selection : R.string.denoise_apply_whole,
              hasSel ? R.string.denoise_apply_whole : R.string.denoise_apply_selection
          }, (d, which) -> {
            boolean applyWholeFile = (which == 1);
            int start = applyWholeFile ? 0 : wave.getSelStart();
            int end = applyWholeFile ? clip.frameCount() : wave.getSelEnd();

            Toast.makeText(this, "Обработка Denoise...", Toast.LENGTH_SHORT).show();

            new Thread(() -> {
              try {
                if (applyWholeFile) {
                  clip.denoise(applyL(), applyR());
                } else {
                  clip.denoiseRange(start, end, applyL(), applyR());
                }
                runOnUiThread(() -> {
                  refreshAfterChange();
                  Toast.makeText(this, "Denoise готов", Toast.LENGTH_SHORT).show();
                });
              } catch (Exception e) {
                runOnUiThread(() -> Toast
                    .makeText(this, "Ошибка Denoise: " + e.getMessage(), Toast.LENGTH_LONG)
                    .show());
              }
            }).start();
          })
          .setNegativeButton(R.string.cancel, null)
          .show();
    });
    findViewById(R.id.btnFadeIn).setOnClickListener(v -> showFadeDialog(true));
    findViewById(R.id.btnFadeOut).setOnClickListener(v -> showFadeDialog(false));
    findViewById(R.id.btnReverse).setOnClickListener(v -> {
      if (wave.hasSelection()) {
        clip.reverseRange(wave.getSelStart(), wave.getSelEnd(), applyL(), applyR());
      } else {
        clip.reverse(applyL(), applyR());
      }
      refreshAfterChange();
    });
    findViewById(R.id.btnInvert).setOnClickListener(v -> {
      if (!wave.hasSelection()) {
        Toast.makeText(this, "Выделите фрагмент", Toast.LENGTH_SHORT).show();
        return;
      }
      clip.invertPhaseRange(wave.getSelStart(), wave.getSelEnd(), applyL(), applyR());
      refreshAfterChange();
    });
    findViewById(R.id.btnMarker).setOnClickListener(v -> {
      if (wave.hasSelection()) {
        clip.addMarker(wave.getSelStart());
        clip.addMarker(wave.getSelEnd());
      } else {
        int f = player.getCurrentFrame();
        if (wave.isZeroCrossingSnap() && clip.pcm != null) {
          f = com.grinya.voicesampleprep.util.AudioOps.nearestZeroCrossing(clip.pcm, f);
        }
        clip.addMarker(f);
      }
      wave.updateMarkers(clip.markers);
      isChanged = true;
    });
    findViewById(R.id.btnMarker).setOnLongClickListener(v -> {
      showMarkerList();
      return true;
    });
  }
  // ---------- диалоги ----------

  private void showGainDialog() {
    EditText input = new EditText(this);
    input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
        | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    input.setText("0");

    new AlertDialog.Builder(this).setTitle(R.string.gain_dialog_title).setView(input)
        .setPositiveButton(R.string.ok, (d, w) -> {
          float dB;
          try {
            dB = Float.parseFloat(input.getText().toString().trim());
          } catch (Exception e) {
            return;
          }
          if (wave.hasSelection()) {
            clip.gainRange(wave.getSelStart(), wave.getSelEnd(), dB, applyL(), applyR());
          } else {
            // Для всего файла используем тот же метод (от 0 до конца)
            clip.gainRange(0, clip.frameCount(), dB, applyL(), applyR());
          }
          refreshAfterChange();
        }).setNegativeButton(R.string.cancel, null).show();
  }

  private void showNormalizeDialog() {
    EditText input = new EditText(this);
    input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    input.setText("0.9");

    final boolean useSel = wave.hasSelection();
    new AlertDialog.Builder(this)
        .setTitle(R.string.normalize_dialog_title + (useSel ? " (к выделению)" : " (весь файл)"))
        .setView(input).setPositiveButton(R.string.ok, (d, w) -> {
          float target;
          try {
            target = Float.parseFloat(input.getText().toString().trim());
          } catch (Exception e) {
            return;
          }
          if (target <= 0f || target > 1f)
            target = 0.9f;
          if (useSel) {
            clip.normalizeRangePeak(wave.getSelStart(), wave.getSelEnd(), target, applyL(),
                applyR());
          } else {
            clip.normalizePeak(target, applyL(), applyR());
          }
          refreshAfterChange();
        }).setNegativeButton(R.string.cancel, null).show();
  }

  private void showFadeDialog(boolean fadeIn) {
    EditText input = new EditText(this);
    input.setInputType(InputType.TYPE_CLASS_NUMBER);
    input.setText("20");

    final boolean useSel = wave.hasSelection();
    new AlertDialog.Builder(this)
        .setTitle(R.string.fade_dialog_title + (useSel ? " (к выделению)" : " (весь файл)"))
        .setView(input).setPositiveButton(R.string.ok, (d, w) -> {
          int ms;
          try {
            ms = Integer.parseInt(input.getText().toString().trim());
          } catch (Exception e) {
            return;
          }
          int frames = Math.max(1, ms * clip.sampleRate / 1000);
          if (useSel) {
            if (fadeIn)
              clip.fadeInRange(wave.getSelStart(), wave.getSelEnd(), frames, applyL(), applyR());
            else
              clip.fadeOutRange(wave.getSelStart(), wave.getSelEnd(), frames, applyL(), applyR());
          } else {
            if (fadeIn)
              clip.fadeIn(frames, applyL(), applyR());
            else
              clip.fadeOut(frames, applyL(), applyR());
          }
          refreshAfterChange();
        }).setNegativeButton(R.string.cancel, null).show();
  }

  // ---------- helpers ----------

  private boolean requireSelection() {
    if (!wave.hasSelection()) {
      Toast.makeText(this, R.string.no_selection, Toast.LENGTH_SHORT).show();
      return false;
    }
    return true;
  }

  private void refreshAfterChange() {
    isChanged = true; // Помечаем, что были изменения
    boolean wasPlaying = player.isPlaying();
    if (wasPlaying)
      player.pause();

    player.setPcm(clip.pcm);
    wave.updatePcm(clip.pcm);
    // wave.clearSelection(); // снятие выделения

    // после удаления позиция playhead могла выйти за пределы
    int f = Math.min(player.getCurrentFrame(), clip.frameCount());
    wave.setPlayheadFrame(f);
    updateTimeLabel();
    wave.updateMarkers(clip.markers);
  }

  private void updateTimeLabel() {
    int curMs = player.getCurrentFrame() * 1000 / clip.sampleRate;
    int totalMs = clip.frameCount() * 1000 / clip.sampleRate;
    txtTime.setText(formatMs(curMs) + " / " + formatMs(totalMs));
  }

  private static String formatMs(int ms) {
    int m = ms / 60000;
    int s = (ms % 60000) / 1000;
    int r = ms % 1000;
    return String.format(Locale.US, "%02d:%02d.%03d", m, s, r);
  }

  private boolean applyL() {
    return !isStereoMode || isLeftEnabled;
  }

  private boolean applyR() {
    return !isStereoMode || isRightEnabled;
  }

  // ---------- AudioPlayer.Listener ----------

  @Override
  public void onPosition(int positionMs) {
    // Двигаем только зелёную линию, красная остаётся на месте
    wave.setActivePlayheadFrame(player.getCurrentFrame());
    updateTimeLabel();
  }

  @Override
  public void onEnded() {
    btnPlay.setEnabled(true);
    btnPause.setEnabled(false);
    isPaused = false;

    // Возвращаем плеер в режим "весь файл"
    player.setPlayRange(0, clip.frameCount());

    wave.setActivePlayheadFrame(clip.frameCount());
    updateTimeLabel();
  }

  @Override
  public void onStateChanged(boolean playing) {
    btnPlay.setEnabled(!playing);
    btnPause.setEnabled(playing);
    updateTransportIcons();
  }

  private void updateTransportIcons() {
    // Play — акцентный янтарный, если доступен; серый, если нет
    btnPlay.setColorFilter(btnPlay.isEnabled() ? 0xFFFFB300 // accent
        : 0xFF555555); // dim
    // Pause — тоже акцент, когда активна, серый когда нет
    btnPause.setColorFilter(btnPause.isEnabled() ? 0xFFFFB300 : 0xFF555555);
  }

  // ---------- WaveformView.Listener ----------

  @Override
  public void onSelectionChanged(int startFrame, int endFrame) {
    // пока ничего
  }

  @Override
  public void onTap(int frame) {
    int snapped = frame;
    if (wave.isZeroCrossingSnap() && clip.pcm != null) {
      snapped = com.grinya.voicesampleprep.util.AudioOps.nearestZeroCrossing(clip.pcm, frame);
    }
    player.seekToFrame(snapped);
    wave.setPlayheadFrame(snapped);
    wave.snapActivePlayheadFrame(snapped);
    updateTimeLabel();
  }

  @Override
  public void onPlayheadDragged(int frame) {
    player.seekToFrame(frame);
    wave.setActivePlayheadFrame(frame);
    updateTimeLabel();
  }

  @Override
  protected void onPause() {
    super.onPause();
    if (player != null && player.isPlaying())
      player.pause();
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == REQ_INSERT && resultCode == RESULT_OK && data != null) {
      Uri uri = data.getData();
      if (uri == null)
        return;

      new Thread(() -> {
        try {
          // Копируем выбранный файл во временный
          java.io.File tmp = new java.io.File(getCacheDir(), "insert_tmp");
          try (java.io.InputStream in = getContentResolver().openInputStream(uri);
              java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
              out.write(buf, 0, n);
          }

          // Декодируем в PCM стерео 48кГц
          com.grinya.voicesampleprep.audio.AudioDecoder.Result res = com.grinya.voicesampleprep.audio.AudioDecoder
              .decode(tmp);
          tmp.delete();

          final float[] newPcm = res.pcm;
          final int atFrame = player.getCurrentFrame();
          final int selStart = wave.getSelStart();
          final int selEnd = wave.getSelEnd();

          runOnUiThread(() -> {
            clip.insert(newPcm, atFrame, selStart, selEnd);
            wave.clearSelection();
            refreshAfterChange();
            Toast.makeText(this, "Вставлено", Toast.LENGTH_SHORT).show();
          });
        } catch (Exception e) {
          runOnUiThread(() -> Toast
              .makeText(this, "Ошибка вставки: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
      }).start();
    }
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    if (player != null)
      player.release();
  }

  @Override
  public void onMarkerTapped(int frame) {
    // Обычный тап — просто переход
    player.seekToFrame(frame);
    wave.setPlayheadFrame(frame);
    updateTimeLabel();
  }

  @Override
  public void onMarkerLongPressed(int frame) {
    String time = formatMs(frame * 1000 / clip.sampleRate);
    new AlertDialog.Builder(this).setTitle("Маркер @" + time)
        .setPositiveButton("Удалить", (d, which) -> {
          clip.removeMarker(frame);
          wave.updateMarkers(clip.markers);
          isChanged = true; // Маркеры изменились
        }).setNegativeButton("Отмена", null).show();
  }

  private void showMarkerList() {
    if (clip.markers.isEmpty()) {
      Toast.makeText(this, "Маркеров нет", Toast.LENGTH_SHORT).show();
      return;
    }
    java.util.List<String> items = new java.util.ArrayList<>();
    for (int m : clip.markers) {
      items.add(formatMs(m * 1000 / clip.sampleRate));
    }
    new AlertDialog.Builder(this).setTitle("Маркеры (" + clip.markers.size() + ")")
        .setItems(items.toArray(new String[0]), (d, which) -> {
          int frame = clip.markers.get(which);
          player.seekToFrame(frame);
          wave.setPlayheadFrame(frame);
          updateTimeLabel();
        }).setNegativeButton("Удалить все", (d, w) -> {
          clip.clearMarkers();
          wave.updateMarkers(clip.markers);
        }).setNeutralButton("Закрыть", null).show();
  }

  private void showMarkerActions(int frame) {
    // для тапа по маркеру прямо на волне — просто перейти
    player.seekToFrame(frame);
    wave.setPlayheadFrame(frame);
    updateTimeLabel();
  }

  private void confirmExit() {
    // времянка удалить
    android.util.Log.d("EXIT", "confirmExit, isChanged=" + isChanged);
    if (isChanged) {
      new AlertDialog.Builder(this).setTitle("Выйти без сохранения?")
          .setMessage("В файле есть несохранённые изменения.")
          .setPositiveButton("Сохранить", (d, w) -> {
            try {
              clip.save();
              // MarkerStore.save(clip.dir, clip.markers);
              isChanged = false;
              finish();
            } catch (Exception e) {
              Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
          }).setNegativeButton("Выйти без сохранения", (d, w) -> finish())
          .setNeutralButton("Отмена", null).show();
    } else {
      finish();
    }
  }

  @Override
  public void onBackPressed() {
    // времянка удалить
    android.util.Log.d("EXIT", "onBackPressed, isChanged=" + isChanged);
    confirmExit();
  }

  @Override
  public void onChannelToggled(boolean left, boolean right) {
    isLeftEnabled = left;
    isRightEnabled = right;
    player.setChannelMask(left, right);
    if (player.isPlaying()) {
      player.pause();
      btnPlay.setEnabled(true);
      btnPause.setEnabled(false);
    }
  }

}
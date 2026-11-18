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
import com.grinya.voicesampleprep.util.AudioInfoDialog;
import java.util.Locale;
import android.view.View;
import android.widget.Button;
import com.grinya.voicesampleprep.ExportToBankActivity;

public class EditActivity extends AppCompatActivity
    implements AudioPlayer.Listener, WaveformView.Listener {

  public static final String EXTRA_CLIP = "clip";

  private AudioClip clip;
  private AudioPlayer player;
  private WaveformView wave;
  private boolean isPaused = false;

  private ImageButton btnPlay, btnStop;
  private TextView txtName, txtTime;
  private boolean isChanged = false;

  private static final int REQ_INSERT = 5001;

  private boolean isStereoMode = false;
  private boolean isLeftEnabled = true;
  private boolean isRightEnabled = true;
  private final java.util.ArrayList<String> recentActions = new java.util.ArrayList<>();
  private float[] clipboard = null;
  private int pendingStartMarker = -1;
  private static int forcedStart = -1;
  private static int forcedEnd = -1;
  private int forcedStartLocal = -1;
  private int forcedEndLocal = -1;

  public static void setForcedInterval(int start, int end) {
    forcedStart = start;
    forcedEnd = end;
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_edit);

    // Перехват свайпа назад (F2: единственный перехватчик)
    getOnBackPressedDispatcher().addCallback(this,
        new androidx.activity.OnBackPressedCallback(true) {
          @Override
          public void handleOnBackPressed() {
            confirmExit();
          }
        });

    clip = EditActivityHolder.current;

    if (clip == null) {
      finish();
      return;
    }

    wave = findViewById(R.id.wave);
    txtName = findViewById(R.id.txtName);
    txtTime = findViewById(R.id.txtTime);
    btnPlay = findViewById(R.id.btnPlay);
    btnStop = findViewById(R.id.btnStop);

    txtName.setText(clip.name);

    player = new AudioPlayer();
    player.setListener(this);
    player.setPcm(clip.pcm);

    wave.setListener(this);
    wave.setData(clip.pcm, clip.sampleRate, clip.markers);
    wave.updateExportIntervals(clip.exportIntervals);

    android.content.SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS,
        MODE_PRIVATE);
    isStereoMode = prefs.getBoolean(SettingsActivity.KEY_STEREO_WAVES, false);
    wave.setStereoView(isStereoMode);
    boolean zeroCrossing = prefs.getBoolean("zero_crossing", true);
    wave.setZeroCrossingSnap(zeroCrossing);
    player.setChannelMask(isLeftEnabled, isRightEnabled);

    wireToolbar();
    wireTransport();
    wireTools();
    updateDynamicButtons();
  }

  private void wireToolbar() {
    findViewById(R.id.btnBack).setOnClickListener(v -> confirmExit());
    findViewById(R.id.btnUndo).setOnClickListener(v -> {
      if (clip.canUndo()) {
        clip.undo();
        refreshAfterChange();
        wave.updateExportIntervals(clip.exportIntervals);
      }
    });
    findViewById(R.id.btnRedo).setOnClickListener(v -> {
      if (clip.canRedo()) {
        clip.redo();
        refreshAfterChange();
        wave.updateExportIntervals(clip.exportIntervals);
      }
    });

    findViewById(R.id.btnMenu).setOnClickListener(v -> {
      androidx.appcompat.widget.PopupMenu popup = new androidx.appcompat.widget.PopupMenu(this, v);
      popup.getMenu().add(0, 1, 0, "Сохранить");
      popup.getMenu().add(0, 2, 0, "Инфо");
      popup.getMenu().add(0, 3, 0, "Экспорт");
      popup.getMenu().add(0, 4, 0, "Экспорт в банк");

      popup.setOnMenuItemClickListener(item -> {
        switch (item.getItemId()) {
          case 1: // Сохранить
            try {
              clip.save();
              isChanged = false;
              Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
              Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            return true;
          case 2: // Инфо
            int start = wave.hasSelection() ? wave.getSelStart() : -1;
            int end = wave.hasSelection() ? wave.getSelEnd() : -1;
            AudioInfoDialog.show(this, clip, start, end);
            return true;
          case 3: // Экспорт
            int selStart = wave.hasSelection() ? wave.getSelStart() : -1;
            int selEnd = wave.hasSelection() ? wave.getSelEnd() : -1;
            ExportActivity.setSource(clip, selStart, selEnd);
            startActivity(new Intent(this, ExportActivity.class));
            return true;
          case 4: // Экспорт в банк
            EditActivityHolder.current = clip;
            startActivity(new Intent(this, ExportToBankActivity.class));
            return true;
        }
        return false;
      });
      popup.show();
    });
  }

  private void updateDynamicButtons() {
    TextView btn1 = findViewById(R.id.btnDynamic1);
    TextView btn2 = findViewById(R.id.btnDynamic2);

    if (recentActions.size() > 0) {
      final String a1 = recentActions.get(0);
      btn1.setText(a1);
      btn1.setVisibility(View.VISIBLE);
      btn1.setOnClickListener(v -> executeActionByName(a1));
    } else {
      btn1.setVisibility(View.GONE);
      btn1.setOnClickListener(null);
    }

    if (recentActions.size() > 1) {
      final String a2 = recentActions.get(1);
      btn2.setText(a2);
      btn2.setVisibility(View.VISIBLE);
      btn2.setOnClickListener(v -> executeActionByName(a2));
    } else {
      btn2.setVisibility(View.GONE);
      btn2.setOnClickListener(null);
    }
  }

  private void executeActionByName(String actionName) {
    switch (actionName) {
      case "Insert":
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] { "audio/wav", "audio/x-wav", "audio/mpeg",
            "audio/mp4", "audio/aac", "audio/flac", "audio/x-flac", "audio/*" });
        startActivityForResult(i, REQ_INSERT);
        break;

      case "Silence":
        if (!requireSelection())
          return;
        clip.silence(wave.getSelStart(), wave.getSelEnd(), applyL(), applyR());
        refreshAfterChange();
        break;

      case "Gain":
        showGainDialog();
        break;

      case "Normalize":
        showNormalizeDialog();
        break;

      case "Fade In":
        showFadeDialog(true);
        break;

      case "Fade Out":
        showFadeDialog(false);
        break;

      case "Reverse":
        if (wave.hasSelection())
          clip.reverseRange(wave.getSelStart(), wave.getSelEnd(), applyL(), applyR());
        else
          clip.reverse(applyL(), applyR());
        refreshAfterChange();
        break;

      case "Invert":
        if (wave.hasSelection())
          clip.invertPhase(wave.getSelStart(), wave.getSelEnd(), applyL(), applyR());
        else
          clip.invertPhase(0, clip.frameCount(), applyL(), applyR());
        refreshAfterChange();
        break;

      case "To Mono":
        if (wave.hasSelection())
          clip.mixToMonoRange(wave.getSelStart(), wave.getSelEnd());
        else
          clip.mixToMonoRange(0, clip.frameCount());
        refreshAfterChange();
        break;

      case "Auto-Trim":
        showAutoTrimDialog();
        break;

      case "High-Pass":
        showHighPassDialog();
        break;

      case "Denoise":
        runDenoise();
        break;

      case "Channels":
        runChannels();
        break;
    }
  }

  private void runDenoise() {
    if (clip.pcm == null || clip.pcm.length == 0)
      return;
    int start = wave.hasSelection() ? wave.getSelStart() : 0;
    int end = wave.hasSelection() ? wave.getSelEnd() : clip.frameCount();
    float[] range = com.grinya.voicesampleprep.util.AudioOps.trim(clip.pcm, start, end);
    Toast.makeText(this, "Обработка Denoise...", Toast.LENGTH_SHORT).show();
    new Thread(() -> {
      try {
        float[] denoised = com.grinya.voicesampleprep.util.Denoiser.denoiseStereo(range);
        runOnUiThread(() -> {
          clip.insert(denoised, start, start, end);
          refreshAfterChange();
          Toast.makeText(this, "Denoise готов", Toast.LENGTH_SHORT).show();
        });
      } catch (Throwable e) {
        runOnUiThread(() -> Toast
            .makeText(this, "Ошибка Denoise: " + e.getMessage(), Toast.LENGTH_LONG).show());
      }
    }).start();
  }

  private void runChannels() {
    boolean l = applyL();
    boolean r = applyR();
    if (l && r) {
      if (wave.hasSelection())
        clip.swapChannels(wave.getSelStart(), wave.getSelEnd());
      else
        clip.swapChannels(0, clip.frameCount());
      Toast.makeText(this, "L ↔ R", Toast.LENGTH_SHORT).show();
    } else if (l) {
      if (wave.hasSelection())
        clip.copyRtoL(wave.getSelStart(), wave.getSelEnd());
      else
        clip.copyRtoL(0, clip.frameCount());
      Toast.makeText(this, "R → L", Toast.LENGTH_SHORT).show();
    } else if (r) {
      if (wave.hasSelection())
        clip.copyLtoR(wave.getSelStart(), wave.getSelEnd());
      else
        clip.copyLtoR(0, clip.frameCount());
      Toast.makeText(this, "L → R", Toast.LENGTH_SHORT).show();
    }
    refreshAfterChange();
  }

  private void wireTransport() {
    findViewById(R.id.btnPlay).setOnClickListener(v -> {
      if (player.isPlaying()) {
        player.pause();
        isPaused = true;
      } else {
        int startFrame = isPaused ? wave.getActivePlayFrame() : wave.getPlayheadFrame();
        player.seekToFrame(startFrame);
        player.play();
        isPaused = false;
      }
    });

    findViewById(R.id.btnStop).setOnClickListener(v -> {
      player.stop();
      isPaused = false;
      int ph = wave.getPlayheadFrame();
      wave.snapActivePlayheadFrame(ph);
      wave.setPlayheadFrame(ph);
      updateTimeLabel();
    });

    findViewById(R.id.btnZoomIn).setOnClickListener(v -> wave.zoomInAroundCenter(2.0));
    findViewById(R.id.btnZoomOut).setOnClickListener(v -> wave.zoomInAroundCenter(0.5));
  }

  private void wireTools() {
    findViewById(R.id.btnTrim).setOnClickListener(v -> {
      if (!requireSelection())
        return;
      clip.trim(wave.getSelStart(), wave.getSelEnd());
      wave.clearSelection();
      refreshAfterChange();
    });

    findViewById(R.id.btnCut).setOnClickListener(v -> {
      if (!requireSelection())
        return;
      int s = wave.getSelStart();
      int e = wave.getSelEnd();
      clipboard = com.grinya.voicesampleprep.util.AudioOps.trim(clip.pcm, s, e);
      clip.delete(s, e);
      wave.clearSelection();
      refreshAfterChange();
      Toast.makeText(this, "Вырезано", Toast.LENGTH_SHORT).show();
    });

    findViewById(R.id.btnCopy).setOnClickListener(v -> {
      if (!requireSelection())
        return;
      clipboard = com.grinya.voicesampleprep.util.AudioOps.trim(clip.pcm, wave.getSelStart(),
          wave.getSelEnd());
      Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show();
    });

    findViewById(R.id.btnPaste).setOnClickListener(v -> {
      if (clipboard == null || clipboard.length == 0) {
        Toast.makeText(this, "Буфер пуст", Toast.LENGTH_SHORT).show();
        return;
      }
      int start = player.getCurrentFrame();
      int selStart = wave.hasSelection() ? wave.getSelStart() : start;
      int selEnd = wave.hasSelection() ? wave.getSelEnd() : start;
      clip.insert(clipboard, start, selStart, selEnd);
      wave.clearSelection();
      refreshAfterChange();
      Toast.makeText(this, "Вставлено", Toast.LENGTH_SHORT).show();
    });

    findViewById(R.id.btnMore).setOnClickListener(v -> {
      androidx.appcompat.widget.PopupMenu popup = new androidx.appcompat.widget.PopupMenu(this, v);
      popup.getMenu().add(0, 1, 0, "Denoise");
      popup.getMenu().add(0, 2, 0, "Auto-Trim");
      popup.getMenu().add(0, 3, 0, "Channels");
      popup.getMenu().add(0, 4, 0, "Insert");
      popup.getMenu().add(0, 5, 0, "Silence");
      popup.getMenu().add(0, 6, 0, "Gain");
      popup.getMenu().add(0, 7, 0, "Normalize");
      popup.getMenu().add(0, 8, 0, "Fade In");
      popup.getMenu().add(0, 9, 0, "Fade Out");
      popup.getMenu().add(0, 10, 0, "Reverse");
      popup.getMenu().add(0, 11, 0, "Invert");
      popup.getMenu().add(0, 12, 0, "To Mono");
      popup.getMenu().add(0, 13, 0, "High-Pass");

      popup.setOnMenuItemClickListener(item -> {
        String actionName = item.getTitle().toString();
        if (!recentActions.contains(actionName)) {
          recentActions.add(0, actionName);
          if (recentActions.size() > 2)
            recentActions.remove(2);
          updateDynamicButtons();
        }
        executeActionByName(actionName);
        return true;
      });
      popup.show();
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
      wave.updateExportIntervals(clip.exportIntervals);
      isChanged = true;
    });

    findViewById(R.id.btnMarker).setOnLongClickListener(v -> {
      showMarkerList();
      return true;
    });
  }

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

  private void showAutoTrimDialog() {
    EditText input = new EditText(this);
    input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    input.setText("0.001");
    input.setHint("Порог амплитуды (0..1)");

    new AlertDialog.Builder(this).setTitle("Auto-Trim (порог тишины)")
        .setMessage("Например: 0.001 (тихо) ... 0.01 (громче)").setView(input)
        .setPositiveButton("OK", (d, w) -> {
          float threshold;
          try {
            threshold = Float.parseFloat(input.getText().toString().trim());
          } catch (Exception e) {
            return;
          }
          if (threshold <= 0 || threshold >= 1) {
            Toast.makeText(this, "Порог должен быть от 0 до 1", Toast.LENGTH_SHORT).show();
            return;
          }

          if (wave.hasSelection()) {
            clip.autoTrim(wave.getSelStart(), wave.getSelEnd(), threshold, applyL(), applyR());
          } else {
            clip.autoTrim(0, clip.frameCount(), threshold, applyL(), applyR());
          }
          wave.clearSelection();
          refreshAfterChange();
          Toast.makeText(this, "Тишина обрезана", Toast.LENGTH_SHORT).show();
        }).setNegativeButton("Отмена", null).show();
  }

  private void showHighPassDialog() {
    EditText input = new EditText(this);
    input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    input.setText("80");
    input.setHint("Частота среза (Гц)");

    new AlertDialog.Builder(this).setTitle("High-Pass Filter (Гц)").setView(input)
        .setPositiveButton("OK", (d, w) -> {
          float cutoff;
          try {
            cutoff = Float.parseFloat(input.getText().toString().trim());
          } catch (Exception e) {
            return;
          }
          if (cutoff <= 0 || cutoff >= 20000) {
            Toast.makeText(this, "Некорректная частота", Toast.LENGTH_SHORT).show();
            return;
          }
          if (wave.hasSelection()) {
            clip.highPass(wave.getSelStart(), wave.getSelEnd(), cutoff, applyL(), applyR());
          } else {
            clip.highPass(0, clip.frameCount(), cutoff, applyL(), applyR());
          }
          refreshAfterChange();
          Toast.makeText(this, "High-Pass применён", Toast.LENGTH_SHORT).show();
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
          clip.clearExportIntervals();
          wave.updateMarkers(clip.markers);
          wave.updateExportIntervals(clip.exportIntervals);
          isChanged = true;
        }).setNeutralButton("Закрыть", null).show();
  }

  private boolean requireSelection() {
    if (!wave.hasSelection()) {
      Toast.makeText(this, R.string.no_selection, Toast.LENGTH_SHORT).show();
      return false;
    }
    return true;
  }

  private void refreshAfterChange() {
    isChanged = true;

    int prevFrame = player.getCurrentFrame();

    boolean wasPlaying = player.isPlaying();
    if (wasPlaying)
      player.pause();

    player.setPcm(clip.pcm);
    wave.updatePcm(clip.pcm);

    int f = Math.max(0, Math.min(prevFrame, clip.frameCount()));
    player.seekToFrame(f);
    wave.setPlayheadFrame(f);
    updateTimeLabel();
    wave.updateMarkers(clip.markers);
    wave.updateExportIntervals(clip.exportIntervals);
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
    wave.setActivePlayheadFrame(player.getCurrentFrame());
    updateTimeLabel();
  }

  @Override
  public void onEnded() {
    isPaused = false;
    player.setPlayRange(0, clip.frameCount());
    wave.setActivePlayheadFrame(clip.frameCount());
    updateTimeLabel();
  }

  @Override
  public void onStateChanged(boolean playing) {
    btnPlay.setImageResource(
        playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
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
  public void onMarkerTapped(int frame) {
    player.seekToFrame(frame);
    wave.setPlayheadFrame(frame);
    updateTimeLabel();
  }

  @Override
  public void onMarkerLongPressed(int frame) {
    if (pendingStartMarker == frame) {
      pendingStartMarker = -1;
      Toast.makeText(this, "Соединение отменено", Toast.LENGTH_SHORT).show();
      return;
    }

    if (pendingStartMarker != -1) {
      int start = Math.min(pendingStartMarker, frame);
      int end = Math.max(pendingStartMarker, frame);

      boolean exists = false;
      for (int[] i : clip.exportIntervals) {
        if (i[0] == start && i[1] == end) {
          exists = true;
          break;
        }
      }

      if (exists) {
        Toast.makeText(this, "Этот интервал уже существует", Toast.LENGTH_SHORT).show();
      } else if (clip.addExportInterval(start, end)) {
        Toast.makeText(this, "Интервал создан", Toast.LENGTH_SHORT).show();
        wave.updateExportIntervals(clip.exportIntervals);
        isChanged = true;
      } else {
        Toast.makeText(this, "Не удалось создать интервал", Toast.LENGTH_SHORT).show();
      }
      pendingStartMarker = -1;
      return;
    }

    String time = formatMs(frame * 1000 / clip.sampleRate);

    boolean hasInterval = false;
    for (int[] i : clip.exportIntervals) {
      if (i[0] == frame || i[1] == frame) {
        hasInterval = true;
        break;
      }
    }

    java.util.ArrayList<String> options = new java.util.ArrayList<>();
    options.add("Соединить");
    if (hasInterval)
      options.add("Отменить соединение");
    options.add("Удалить маркер");

    new AlertDialog.Builder(this).setTitle("Маркер @" + time)
        .setItems(options.toArray(new String[0]), (d, which) -> {
          String choice = options.get(which);
          if (choice.equals("Соединить")) {
            pendingStartMarker = frame;
            Toast.makeText(this, "Выберите второй маркер (долгий тап)", Toast.LENGTH_SHORT).show();
          } else if (choice.equals("Отменить соединение")) {
            clip.removeExportIntervalsWithMarker(frame);
            wave.updateExportIntervals(clip.exportIntervals);
            isChanged = true;
            Toast.makeText(this, "Соединение отменено", Toast.LENGTH_SHORT).show();
          } else if (choice.equals("Удалить маркер")) {
            clip.removeExportIntervalsWithMarker(frame);
            clip.removeMarker(frame);
            wave.updateMarkers(clip.markers);
            wave.updateExportIntervals(clip.exportIntervals);
            isChanged = true;
          }
        }).setNegativeButton("Отмена", null).show();
  }

  @Override
  public void onChannelToggled(boolean left, boolean right) {
    isLeftEnabled = left;
    isRightEnabled = right;
    player.setChannelMask(left, right);
    if (player.isPlaying()) {
      player.pause();
      btnPlay.setEnabled(true);
    }
  }

  @Override
  public void onIntervalLongPressed(int startFrame, int endFrame) {
    String timeStart = formatMs(startFrame * 1000 / clip.sampleRate);
    String timeEnd = formatMs(endFrame * 1000 / clip.sampleRate);

    new AlertDialog.Builder(this).setTitle("Интервал " + timeStart + " – " + timeEnd)
        .setItems(new String[] { "Проиграть", "Удалить", "Экспортировать", "В банк", "Инфо" },
            (d, which) -> {
              switch (which) {
                case 0: // Проиграть
                  player.setPlayRange(startFrame, endFrame);
                  player.seekToFrame(startFrame);
                  player.play();
                  break;
                case 1: // Удалить
                  clip.removeExportInterval(startFrame, endFrame);
                  wave.updateExportIntervals(clip.exportIntervals);
                  isChanged = true;
                  if (clip.exportIntervals.isEmpty()) {
                    wave.setIntervalsEditMode(false);
                  }
                  break;
                case 2: // Экспортировать
                  ExportActivity.setForcedInterval(startFrame, endFrame);
                  ExportActivity.setSource(clip, -1, -1);
                  startActivity(new Intent(this, ExportActivity.class));
                  break;
                case 3: // В банк
                  ExportToBankActivity.setForcedInterval(startFrame, endFrame);
                  EditActivityHolder.current = clip;
                  startActivity(new Intent(this, ExportToBankActivity.class));
                  break;
                case 4: // Инфо
                  AudioInfoDialog.showInterval(this, clip, startFrame, endFrame);
                  break;
              }
            })
        .setNegativeButton("Отмена", null).show();
  }

  @Override
  public void onIntervalsModeChanged(boolean enabled) {
    // ничего
  }

  @Override
  public void onIntervalTapped(int startFrame, int endFrame) {
    if (!wave.isIntervalsEditMode()) {
      wave.setIntervalsEditMode(true);
    }
  }

  private void confirmExit() {
    if (isChanged) {
      new AlertDialog.Builder(this).setTitle("Выйти без сохранения?")
          .setMessage("В файле есть несохранённые изменения.")
          .setPositiveButton("Сохранить", (d, w) -> {
            try {
              clip.save();
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
  protected void onPause() {
    super.onPause();
    if (player != null && player.isPlaying())
      player.pause();
    pendingStartMarker = -1;
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    if (player != null)
      player.release();
    pendingStartMarker = -1;
    if (EditActivityHolder.current == clip) {
      EditActivityHolder.clear();
    }
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
          java.io.File tmp = new java.io.File(getCacheDir(), "insert_tmp");
          try (java.io.InputStream in = getContentResolver().openInputStream(uri);
              java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
              out.write(buf, 0, n);
          }

          AudioDecoder.Result res = AudioDecoder.decode(tmp);
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
}
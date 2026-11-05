package com.grinya.voicesampleprep.edit;

import com.grinya.voicesampleprep.ExportActivity;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.grinya.voicesampleprep.R;
import com.grinya.voicesampleprep.audio.AudioPlayer;
import com.grinya.voicesampleprep.model.AudioClip;

import java.util.Locale;

public class EditActivity extends AppCompatActivity
    implements AudioPlayer.Listener, WaveformView.Listener {

  public static final String EXTRA_CLIP = "clip";

  private AudioClip clip;
  private AudioPlayer player;
  private WaveformView wave;

  private ImageButton btnPlay, btnStop;
  private TextView txtName, txtTime;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_edit);

    Object o = getIntent().getSerializableExtra(EXTRA_CLIP);
    // AudioClip не Serializable — передаём через singleton-holder
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

    wireToolbar();
    wireTransport();
    wireTools();
  }

  private void wireToolbar() {
    findViewById(R.id.btnBack).setOnClickListener(v -> finish());
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
        clip.save();
        Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show();
      } catch (Exception e) {
        Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
      }
    });
    findViewById(R.id.btnExport).setOnClickListener(v -> {
      int selStart = wave.hasSelection() ? wave.getSelStart() : -1;
      int selEnd = wave.hasSelection() ? wave.getSelEnd() : -1;
      ExportActivity.setSource(clip, selStart, selEnd);
      startActivity(new Intent(this, ExportActivity.class));
    });
  }

  private void wireTransport() {
    btnPlay.setOnClickListener(v -> {
      if (player.isPlaying())
        player.pause();
      else
        player.play();
    });
    btnStop.setOnClickListener(v -> {
      player.stop();
      wave.setPlayheadFrame(0);
    });
    findViewById(R.id.btnZoomIn).setOnClickListener(v -> {
      // эмулируем pinch зум: уменьшаем fpp в 2 раза вокруг центра
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
      refreshAfterChange();
    });
    findViewById(R.id.btnCut).setOnClickListener(v -> {
      if (!requireSelection())
        return;
      clip.delete(wave.getSelStart(), wave.getSelEnd());
      refreshAfterChange();
    });
    findViewById(R.id.btnSilence).setOnClickListener(v -> {
      if (!requireSelection())
        return;
      clip.silence(wave.getSelStart(), wave.getSelEnd());
      refreshAfterChange();
    });
    findViewById(R.id.btnGain).setOnClickListener(v -> showGainDialog());
    findViewById(R.id.btnNormalize).setOnClickListener(v -> showNormalizeDialog());
    findViewById(R.id.btnFadeIn).setOnClickListener(v -> showFadeDialog(true));
    findViewById(R.id.btnFadeOut).setOnClickListener(v -> showFadeDialog(false));
    findViewById(R.id.btnReverse).setOnClickListener(v -> {
      clip.reverse();
      refreshAfterChange();
    });
    findViewById(R.id.btnMarker).setOnClickListener(v -> {
      int f = player.getCurrentFrame();
      clip.addMarker(f);
      wave.updateMarkers(clip.markers); // перерисовать маркеры
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
            clip.gainRange(wave.getSelStart(), wave.getSelEnd(), dB);
          } else {
            clip.gain(dB);
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
          if (useSel)
            clip.normalizeRangePeak(wave.getSelStart(), wave.getSelEnd(), target);
          else
            clip.normalizePeak(target);
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
              clip.fadeInRange(wave.getSelStart(), wave.getSelEnd(), frames);
            else
              clip.fadeOutRange(wave.getSelStart(), wave.getSelEnd(), frames);
          } else {
            if (fadeIn)
              clip.fadeIn(frames);
            else
              clip.fadeOut(frames);
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
    boolean wasPlaying = player.isPlaying();
    if (wasPlaying)
      player.pause();

    player.setPcm(clip.pcm);
    wave.updatePcm(clip.pcm);
    wave.clearSelection();

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

  // ---------- AudioPlayer.Listener ----------

  @Override
  public void onPosition(int positionMs) {
    wave.setPlayheadFrame(player.getCurrentFrame());
    updateTimeLabel();
  }

  @Override
  public void onEnded() {
    btnPlay.setImageResource(android.R.drawable.ic_media_play);
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
    // тап по волне — перемещаем playhead
    player.seekToFrame(frame);
    wave.setPlayheadFrame(frame);
    updateTimeLabel();
  }

  @Override
  public void onPlayheadDragged(int frame) {
    player.seekToFrame(frame);
    updateTimeLabel();
  }

  @Override
  protected void onPause() {
    super.onPause();
    if (player != null && player.isPlaying())
      player.pause();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    if (player != null)
      player.release();
  }

  @Override
  public void onMarkerTapped(int frame) {
    player.seekToFrame(frame);
    wave.setPlayheadFrame(frame);
    updateTimeLabel();
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
}
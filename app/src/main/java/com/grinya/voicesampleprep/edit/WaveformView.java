package com.grinya.voicesampleprep.edit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.List;

public class WaveformView extends View {

  public interface Listener {
    void onSelectionChanged(int startFrame, int endFrame);

    void onTap(int frame);

    void onPlayheadDragged(int frame);

    void onMarkerTapped(int frame);

    void onMarkerLongPressed(int frame);

    void onChannelToggled(boolean left, boolean right);

    void onIntervalLongPressed(int startFrame, int endFrame);

    void onIntervalsModeChanged(boolean enabled);

    void onIntervalTapped(int startFrame, int endFrame);
  }

  // ---------- цвета ----------
  private int colorBg = 0xFF0E0E10;
  private int colorWave = 0xFF9AA0A6;
  private int colorCenterLine = 0xFF2A2A2E;
  private int colorPlayhead = 0xFFFF3B30;
  private int colorActivePlayhead = 0xFF4CAF50;
  private int colorSelection = 0x33FFB300;
  private int colorSelectionBorder = 0xFFFFB300;
  private int colorMarker = 0xFF4FC3F7;
  private int colorGrid = 0xFF1F1F22;
  private int colorRuler = 0xFF2A2A2E;
  private int colorRulerText = 0xFF808080;
  private int colorCheck = 0xFFFFB300;

  private static final int RULER_HEIGHT_DP = 22;
  private static final int HANDLE_HALF_DP = 10;
  private static final int MARKER_FLAG_W_DP = 10;
  private static final int MARKER_FLAG_H_DP = 14;

  private float[] pcm;
  private int totalFrames = 0;
  private int sampleRate = 48000;
  private List<Integer> markers;
  private List<int[]> exportIntervals;
  private static final int DRAG_PLAYHEAD = 6;

  private double viewStartFrame = 0;
  private double viewFramesPerPixel = 1;
  private int playheadFrame = 0;
  private int activePlayFrame = 0;
  private final Paint pWaveFill = new Paint(Paint.ANTI_ALIAS_FLAG);

  private int selStart = -1;
  private int selEnd = -1;
  private int paddingPx;

  private boolean stereoView = false;
  private boolean channelL = true;
  private boolean channelR = true;
  private float checkLx, checkLy, checkRx, checkRy;
  private int checkSize;

  private final Paint pWave = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pCenter = new Paint();
  private final Paint pPlayhead = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pActivePlayhead = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pSel = new Paint();
  private final Paint pSelBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pMarker = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pMarkerFill = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pGrid = new Paint();
  private final Paint pRuler = new Paint();
  private final Paint pRulerText = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pCheck = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pCheckBg = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pChannelText = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pScaleBg = new Paint();
  private final Paint pEmpty = new Paint();
  private final Path wavePath = new Path();
  private final Paint pIntervalFillNormal = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pCheckboxBgOn = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pCheckboxBgOff = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pCheckboxBorderOn = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pCheckboxBorderOff = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final android.graphics.RectF tmpRect = new android.graphics.RectF();

  private GestureDetector gestureDetector;
  private ScaleGestureDetector scaleDetector;
  private Listener listener;

  private static final int DRAG_NONE = 0;
  private static final int DRAG_SCROLL = 1;
  private static final int DRAG_SEL_START = 2;
  private static final int DRAG_SEL_END = 3;
  private static final int DRAG_NEW_SELECTION = 4;
  private int dragMode = DRAG_NONE;
  private float lastTouchX, downX, downY;
  private int downSelStart, downSelEnd;
  private boolean longPressActivated = false;
  private int downMarkerFrame = -1;
  private boolean zeroCrossingSnap = true; // включён по умолчанию

  // Шкала амплитуды
  private final Paint pAmpScale = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pAmpText = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pHGrid = new Paint();
  private int ampScaleWidthPx;

  private boolean intervalsEditMode = false;
  private int intervalPage = 0;
  private int intervalPageSize = 6;
  private int selectedIntervalIndex = -1; // индекс выбранного интервала (для обводки)
  private final Paint pIntervalFill = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pIntervalStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pIntervalText = new Paint(Paint.ANTI_ALIAS_FLAG);
  private int lastWaveTop = 0;
  private int lastWaveBottom = 0;
  private int lastWaveWidth = 0;
  private int downIntervalStart = -1;
  private int downIntervalEnd = -1;

  private float[] peakMaxMono; // max(L,R) по пикселям
  private float[] peakMinMono; // min(L,R) по пикселям

  private final Runnable longPressRunnable = new Runnable() {
    @Override
    public void run() {
      if (downIntervalStart != -1) {
        if (listener != null)
          listener.onIntervalLongPressed(downIntervalStart, downIntervalEnd);
        longPressActivated = true;
        downIntervalStart = -1;
        downIntervalEnd = -1;
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        return;
      }
      if (downMarkerFrame != -1) {
        if (listener != null)
          listener.onMarkerLongPressed(downMarkerFrame);
        longPressActivated = true;
        downMarkerFrame = -1;
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        return;
      }
      if (dragMode == DRAG_SCROLL) {
        longPressActivated = true;
        dragMode = DRAG_NEW_SELECTION;
        int f = xToFrame(downX);
        downSelStart = f;
        selStart = f;
        selEnd = f;
        invalidate();
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
      }
    }
  };

  private int rulerHeightPx;
  private int handleHalfPx;
  private int markerFlagW;
  private int markerFlagH;

  private float[] peakMin;
  private float[] peakMax;
  private float[] peakMinR;
  private float[] peakMaxR;
  private int peakCacheWidth = 0;
  private double peakCacheStart = 0;
  private double peakCacheFpp = 0;

  public WaveformView(Context c) {
    super(c);
    init(c);
  }

  public WaveformView(Context c, @Nullable AttributeSet a) {
    super(c, a);
    init(c);
  }

  public WaveformView(Context c, @Nullable AttributeSet a, int d) {
    super(c, a, d);
    init(c);
  }

  private void init(Context c) {
    float density = c.getResources().getDisplayMetrics().density;
    rulerHeightPx = (int) (RULER_HEIGHT_DP * density);
    handleHalfPx = (int) (HANDLE_HALF_DP * density);
    markerFlagW = (int) (MARKER_FLAG_W_DP * density);
    markerFlagH = (int) (MARKER_FLAG_H_DP * density);
    checkSize = (int) (32 * density);
    paddingPx = (int) (25 * density); // Отступ по 40dp с каждой стороны

    pWave.setColor(colorWave);
    pWave.setStrokeWidth(1.5f);
    pWave.setStyle(Paint.Style.STROKE);

    pWaveFill.setColor(0x55FFFFFF); // белый с прозрачностью 33% (или другой оттенок)
    pWaveFill.setStyle(Paint.Style.FILL);

    pCenter.setColor(colorCenterLine);
    pCenter.setStrokeWidth(1f);

    pPlayhead.setColor(colorPlayhead);
    pPlayhead.setStrokeWidth(2f * density);

    pActivePlayhead.setColor(colorActivePlayhead);
    pActivePlayhead.setStrokeWidth(3f * density);

    pSel.setColor(colorSelection);
    pSel.setStyle(Paint.Style.FILL);

    pSelBorder.setColor(colorSelectionBorder);
    pSelBorder.setStrokeWidth(1.5f * density);
    pSelBorder.setStyle(Paint.Style.STROKE);

    pMarker.setColor(colorMarker);
    pMarker.setStrokeWidth(2f * density);

    pMarkerFill.setColor(colorMarker);
    pMarkerFill.setStyle(Paint.Style.FILL);

    pGrid.setColor(colorGrid);
    pGrid.setStrokeWidth(1f);

    pEmpty.setColor(0xAA000000); // Полупрозрачный черный
    pEmpty.setStyle(Paint.Style.FILL);

    pIntervalFill.setColor(0x884FC3F7); // Полупрозрачный голубой
    pIntervalFill.setStyle(Paint.Style.FILL);

    pIntervalStroke.setColor(0xFFFFB300); // Янтарная обводка (для выбранного)
    pIntervalStroke.setStrokeWidth(2f * density);
    pIntervalStroke.setStyle(Paint.Style.STROKE);

    pIntervalText.setColor(0xFF0E0E10); // Тёмный текст
    pIntervalText.setTextSize(11f * density);

    pIntervalFillNormal.setColor(0x884FC3F7);
    pIntervalFillNormal.setStyle(Paint.Style.FILL);

    pCheckboxBgOn.setColor(0xAAFFB300);
    pCheckboxBgOn.setStyle(Paint.Style.FILL);

    pCheckboxBgOff.setColor(0xAA1A1A1E);
    pCheckboxBgOff.setStyle(Paint.Style.FILL);

    pCheckboxBorderOn.setStyle(Paint.Style.STROKE);
    pCheckboxBorderOn.setStrokeWidth(2f);
    pCheckboxBorderOn.setColor(0xFFFFD54F);

    pCheckboxBorderOff.setStyle(Paint.Style.STROKE);
    pCheckboxBorderOff.setStrokeWidth(2f);
    pCheckboxBorderOff.setColor(0xFF444444);

    // Шкала амплитуды
    float density2 = c.getResources().getDisplayMetrics().density;
    ampScaleWidthPx = (int) (32 * density2); // ширина шкалы ~32dp
    pAmpScale.setColor(0xFF808080); // серый
    pAmpScale.setStrokeWidth(1f);
    pAmpText.setColor(0xFF808080);
    pAmpText.setTextSize(9f * density2);
    pHGrid.setColor(0x22FFFFFF); // очень прозрачный белый
    pHGrid.setStrokeWidth(1f);

    pScaleBg.setColor(0xAA0E0E10); // тёмный полупрозрачный фон
    pScaleBg.setStyle(Paint.Style.FILL);

    pRuler.setColor(colorRuler);
    pRuler.setStyle(Paint.Style.FILL);

    pRulerText.setColor(colorRulerText);
    pRulerText.setTextSize(10f * density);

    pCheck.setColor(colorCheck);
    pCheck.setStyle(Paint.Style.FILL);

    // pCheckBg.setColor(0xCC1A1A1E);
    pCheckBg.setColor(0xAA1A1A1E); // 66% непрозрачности вместо 80%
    pCheckBg.setStyle(Paint.Style.FILL);

    pChannelText.setColor(0xFF0E0E10); // Тёмный цвет (под цвет фона)
    pChannelText.setTextSize(16f * density);
    pChannelText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

    setBackgroundColor(colorBg);

    gestureDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
      @Override
      public boolean onDown(MotionEvent e) {
        return true;
      }

      @Override
      public boolean onDoubleTap(MotionEvent e) {
        zoomToFit();
        return true;
      }

      @Override
      public boolean onSingleTapUp(MotionEvent e) {
        return false;
      }
    });

    scaleDetector = new ScaleGestureDetector(c,
        new ScaleGestureDetector.SimpleOnScaleGestureListener() {
          @Override
          public boolean onScale(ScaleGestureDetector d) {
            if (totalFrames == 0)
              return true;
            double oldFpp = viewFramesPerPixel;
            double newFpp = oldFpp / d.getScaleFactor();
            double minFpp = 1.0 / 8.0;
            double maxFpp = Math.max(1, totalFrames);
            if (newFpp < minFpp)
              newFpp = minFpp;
            if (newFpp > maxFpp)
              newFpp = maxFpp;

            float fx = d.getFocusX();
            double focusFrame = viewStartFrame + fx * oldFpp;
            viewStartFrame = focusFrame - fx * newFpp;
            viewFramesPerPixel = newFpp;
            clampView();
            invalidatePeakCache();
            invalidate();
            return true;
          }
        });
  }

  public void setListener(Listener l) {
    this.listener = l;
  }

  public void setStereoView(boolean stereo) {
    this.stereoView = stereo;
    invalidatePeakCache();
    invalidate();
  }

  public void setData(float[] pcm, int sampleRate, List<Integer> markers) {
    this.pcm = pcm;
    this.sampleRate = sampleRate;
    this.markers = markers;
    this.totalFrames = pcm == null ? 0 : pcm.length / 2;
    this.viewStartFrame = 0;
    this.viewFramesPerPixel = 1;
    this.selStart = -1;
    this.selEnd = -1;
    this.playheadFrame = 0;
    this.activePlayFrame = 0;
    invalidatePeakCache();
    invalidate();
  }

  public void setZeroCrossingSnap(boolean enabled) {
    this.zeroCrossingSnap = enabled;
  }

  public void updatePcm(float[] newPcm) {
    this.pcm = newPcm;
    this.totalFrames = newPcm == null ? 0 : newPcm.length / 2;
    if (selStart > totalFrames)
      selStart = -1;
    if (selEnd > totalFrames)
      selEnd = -1;
    if (playheadFrame > totalFrames)
      playheadFrame = totalFrames;
    if (activePlayFrame > totalFrames)
      activePlayFrame = totalFrames;
    invalidatePeakCache();
    invalidate();
  }

  public void updateMarkers(List<Integer> newMarkers) {
    this.markers = newMarkers;
    invalidate();
  }

  public void setPlayheadFrame(int f) {
    playheadFrame = Math.max(0, Math.min(totalFrames, f));
    invalidate();
  }

  public void setActivePlayheadFrame(int f) {
    activePlayFrame = Math.max(0, Math.min(totalFrames, f));
    invalidate();
  }

  public void snapActivePlayheadFrame(int f) {
    activePlayFrame = Math.max(0, Math.min(totalFrames, f));
    invalidate();
  }

  public int getPlayheadFrame() {
    return playheadFrame;
  }

  public int getActivePlayFrame() {
    return activePlayFrame;
  }

  public void setSelection(int start, int end) {
    selStart = Math.max(0, Math.min(start, totalFrames));
    selEnd = Math.max(0, Math.min(end, totalFrames));
    if (selStart > selEnd) {
      int t = selStart;
      selStart = selEnd;
      selEnd = t;
    }
    if (selStart == selEnd) {
      selStart = -1;
      selEnd = -1;
    }
    invalidate();
    if (listener != null)
      listener.onSelectionChanged(selStart, selEnd);
  }

  public void clearSelection() {
    selStart = -1;
    selEnd = -1;
    invalidate();
    if (listener != null)
      listener.onSelectionChanged(-1, -1);
  }

  public int getSelStart() {
    return selStart;
  }

  public int getSelEnd() {
    return selEnd;
  }

  public boolean hasSelection() {
    return selStart >= 0 && selEnd > selStart;
  }

  public void zoomToFit() {
    int w = getWidth() - 2 * paddingPx; // Учитываем отступы
    if (w <= 0 || totalFrames <= 0)
      return;
    viewStartFrame = 0;
    viewFramesPerPixel = (double) totalFrames / w;
    clampView();
    invalidatePeakCache();
    invalidate();
  }

  public void zoomInAroundCenter(double factor) {
    if (totalFrames == 0)
      return;
    double oldFpp = viewFramesPerPixel;
    double newFpp = oldFpp / factor;
    double minFpp = 1.0 / 8.0;
    double maxFpp = Math.max(1, totalFrames);
    if (newFpp < minFpp)
      newFpp = minFpp;
    if (newFpp > maxFpp)
      newFpp = maxFpp;
    float cx = getWidth() / 2f;
    double focusFrame = viewStartFrame + cx * oldFpp;
    viewStartFrame = focusFrame - cx * newFpp;
    viewFramesPerPixel = newFpp;
    clampView();
    invalidatePeakCache();
    invalidate();
  }

  public void centerOnFrame(int frame) {
    int w = getWidth();
    if (w <= 0)
      return;
    viewStartFrame = frame - (w / 2.0) * viewFramesPerPixel;
    clampView();
    invalidatePeakCache();
    invalidate();
  }

  @Override
  public boolean onTouchEvent(MotionEvent e) {
    scaleDetector.onTouchEvent(e);
    gestureDetector.onTouchEvent(e);

    // Тап по линейке
    if (e.getActionMasked() == MotionEvent.ACTION_DOWN && e.getY() < rulerHeightPx) {
      playheadFrame = xToFrame(e.getX());
      invalidate();
      if (listener != null)
        listener.onPlayheadDragged(playheadFrame);
      dragMode = DRAG_NONE;
      return true;
    }

    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        return handleActionDown(e);
      case MotionEvent.ACTION_MOVE:
        return handleActionMove(e);
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        return handleActionUp(e);
    }
    return super.onTouchEvent(e);
  }

  private boolean handleActionDown(MotionEvent e) {
    lastTouchX = e.getX();
    downX = e.getX();
    downY = e.getY();
    downSelStart = selStart;
    downSelEnd = selEnd;

    // 0. Проверка тапа по интервалам (всегда, независимо от режима)
    // 0. Проверка тапа по интервалам (всегда, независимо от режима)
    if (exportIntervals != null && !exportIntervals.isEmpty()) {
      float[] layout = calcStripLayout();
      float startY = layout[0];
      float stripH = layout[1];
      int count = (int) layout[2];
      int startIdx = (int) layout[3];
      int endIdx = (int) layout[4];

      if (count > 0) {
        float stepY = stripH + 8f; // Шаг между полосками
        // Зона тапа: половина шага вверх и вниз от полоски
        // (в тонком режиме stepY = 16f, зона = 16f — касается соседних)
        float tapAbove = stepY / 2f;
        float tapBelow = stepY / 2f;

        float offsetY = startY;
        for (int i = startIdx; i < endIdx; i++) {
          int[] interval = exportIntervals.get(i);
          float x1 = frameToX(interval[0]);
          float x2 = frameToX(interval[1]);
          int waveW = getWidth() - 2 * paddingPx;
          float drawX1 = Math.max(0, x1) + paddingPx;
          float drawX2 = Math.min(waveW, x2) + paddingPx;

          // Зона тапа: от offsetY - tapAbove до offsetY + stripH + tapBelow
          float tapTop = offsetY - tapAbove;
          float tapBottom = offsetY + stripH + tapBelow;

          if (e.getX() >= drawX1 && e.getX() <= drawX2 && e.getY() >= tapTop
              && e.getY() <= tapBottom) {
            // Попали в полосу
            if (!intervalsEditMode) {
              // Вход в режим
              setIntervalsEditMode(true);
              if (listener != null)
                listener.onIntervalsModeChanged(true);
            } else {
              // Уже в режиме — просто выделяем (не выходим)
              selectedIntervalIndex = i;
              invalidate();
            }

            downIntervalStart = interval[0];
            downIntervalEnd = interval[1];
            longPressActivated = false;
            postDelayed(longPressRunnable, 350);
            return true;
          }
          offsetY += stepY;
        }
      }
    }

    // 1. Проверка чекбоксов L/R
    if (stereoView) {
      if (e.getX() >= checkLx && e.getX() <= checkLx + checkSize && e.getY() >= checkLy
          && e.getY() <= checkLy + checkSize) {
        if (!channelR)
          return true;
        channelL = !channelL;
        invalidate();
        if (listener != null)
          listener.onChannelToggled(channelL, channelR);
        return true;
      }
      if (e.getX() >= checkRx && e.getX() <= checkRx + checkSize && e.getY() >= checkRy
          && e.getY() <= checkRy + checkSize) {
        if (!channelL)
          return true;
        channelR = !channelR;
        invalidate();
        if (listener != null)
          listener.onChannelToggled(channelL, channelR);
        return true;
      }
    }

    // 2. Проверка ручек выделения
    if (hasSelection() && e.getY() >= rulerHeightPx) {
      float sxStart = frameToX(selStart) + paddingPx;
      float sxEnd = frameToX(selEnd) + paddingPx;
      if (Math.abs(e.getX() - sxStart) <= handleHalfPx * 2) {
        dragMode = DRAG_SEL_START;
        return true;
      }
      if (Math.abs(e.getX() - sxEnd) <= handleHalfPx * 2) {
        dragMode = DRAG_SEL_END;
        return true;
      }
    }

    // 3. Проверка маркеров
    downMarkerFrame = -1;
    if (markers != null) {
      for (int m : markers) {
        float mx = frameToX(m) + paddingPx;
        if (e.getX() >= mx - 20 && e.getX() <= mx + markerFlagW + 20) {
          downMarkerFrame = m;
          break;
        }
      }
    }

    // 4. Проверка playhead
    float playX = frameToX(playheadFrame) + paddingPx;
    boolean onPlayhead = Math.abs(e.getX() - playX) <= 20;

    if (downMarkerFrame != -1) {
      // На маркере. Если playhead тоже здесь — даём возможность тащить.
      dragMode = onPlayhead ? DRAG_PLAYHEAD : DRAG_SCROLL;
      longPressActivated = false;
      postDelayed(longPressRunnable, 350);
      return true;
    }

    if (onPlayhead) {
      dragMode = DRAG_PLAYHEAD;
      return true;
    }

    // 5. Иначе — скролл
    dragMode = DRAG_SCROLL;
    longPressActivated = false;
    postDelayed(longPressRunnable, 350);
    return true;
  }

  private boolean handleActionMove(MotionEvent e) {
    if (scaleDetector.isInProgress())
      return true;

    // Отмена long-press при движении
    if (!longPressActivated && Math.abs(e.getX() - downX) + Math.abs(e.getY() - downY) > 12) {
      removeCallbacks(longPressRunnable);
      downIntervalStart = -1;
      downIntervalEnd = -1;
    }

    if (dragMode == DRAG_PLAYHEAD) {
      int f = xToFrame(e.getX());
      int marker = nearestMarker(f, 15);
      if (marker != -1)
        f = marker;
      playheadFrame = f;
      invalidate();
      if (listener != null)
        listener.onPlayheadDragged(playheadFrame);
      return true;
    }

    if (dragMode == DRAG_SCROLL) {
      double dx = lastTouchX - e.getX();
      viewStartFrame += dx * viewFramesPerPixel;
      clampView();
      lastTouchX = e.getX();
      invalidatePeakCache();
      invalidate();
      return true;
    }

    if (dragMode == DRAG_SEL_START) {
      int f = xToFrame(e.getX());
      int newStart = Math.min(f, selEnd);
      int newEnd = Math.max(f, selEnd);
      if (newStart != selStart) {
        selStart = Math.max(0, newStart);
        if (listener != null)
          listener.onSelectionChanged(selStart, selEnd);
        invalidate();
      }
      return true;
    }

    if (dragMode == DRAG_SEL_END) {
      int f = xToFrame(e.getX());
      int newStart = Math.min(selStart, f);
      int newEnd = Math.max(selStart, f);
      if (newEnd != selEnd) {
        selEnd = Math.min(totalFrames, newEnd);
        if (listener != null)
          listener.onSelectionChanged(selStart, selEnd);
        invalidate();
      }
      return true;
    }

    if (dragMode == DRAG_NEW_SELECTION) {
      int f = xToFrame(e.getX());
      int a = Math.min(downSelStart, f);
      int b = Math.max(downSelStart, f);
      selStart = Math.max(0, a);
      selEnd = Math.min(totalFrames, b);
      if (listener != null)
        listener.onSelectionChanged(selStart, selEnd);
      invalidate();
      return true;
    }

    return true;
  }

  private boolean handleActionUp(MotionEvent e) {
    removeCallbacks(longPressRunnable);

    // Снап к нулю для выделения
    if (zeroCrossingSnap && pcm != null && hasSelection()) {
      int fStart = selStart;
      int fEnd = selEnd;
      if (fStart >= 0 && fStart < totalFrames) {
        selStart = nearestZeroCross(fStart);
      }
      if (fEnd >= 0 && fEnd < totalFrames) {
        selEnd = nearestZeroCross(fEnd);
      }
      if (selStart > selEnd) {
        int t = selStart;
        selStart = selEnd;
        selEnd = t;
      }
      invalidate();
      if (listener != null)
        listener.onSelectionChanged(selStart, selEnd);
    }
    // Выход из режима интервалов при повторном тапе по той же полосе
    if (intervalsEditMode && selectedIntervalIndex != -1 && !longPressActivated) {
      // Проверяем, что тап был коротким (не движение)
      if (Math.abs(e.getX() - downX) < 8 && Math.abs(e.getY() - downY) < 8) {
        float[] layout = calcStripLayout();
        float startY = layout[0];
        float stripH = layout[1];
        int count = (int) layout[2];
        int startIdx = (int) layout[3];
        int endIdx = (int) layout[4];
        float stepY = stripH + 8f;

        if (count > 0 && selectedIntervalIndex >= startIdx && selectedIntervalIndex < endIdx) {
          // Смещение выбранной полосы внутри видимой страницы
          float offsetY = startY + (selectedIntervalIndex - startIdx) * stepY;

          int[] interval = exportIntervals.get(selectedIntervalIndex);
          int waveW = getWidth() - 2 * paddingPx;
          float drawX1 = Math.max(0, frameToX(interval[0])) + paddingPx;
          float drawX2 = Math.min(waveW, frameToX(interval[1])) + paddingPx;

          // Зона тапа: полоса целиком (от offsetY до offsetY + stripH) + запас stepY/2 сверху/снизу
          float tapTop = offsetY - stepY / 2f;
          float tapBottom = offsetY + stripH + stepY / 2f;
          if (e.getX() >= drawX1 && e.getX() <= drawX2 && e.getY() >= tapTop
              && e.getY() <= tapBottom) {
            // Повторный тап по выбранной полосе — выход
            setIntervalsEditMode(false);
            if (listener != null)
              listener.onIntervalsModeChanged(false);
            return true;
          }
        }
      }
    }
    // Короткий тап (не долгий)
    if (e.getActionMasked() == MotionEvent.ACTION_UP && Math.abs(e.getX() - downX) < 8
        && Math.abs(e.getY() - downY) < 8 && e.getY() >= rulerHeightPx && !longPressActivated) {

      if (downMarkerFrame != -1) {
        if (listener != null)
          listener.onMarkerTapped(downMarkerFrame);
      } else {
        int frame = xToFrame(e.getX());
        int marker = nearestMarker(frame, 15);
        if (marker != -1)
          frame = marker;
        if (listener != null)
          listener.onTap(frame);
      }
    }

    dragMode = DRAG_NONE;
    downMarkerFrame = -1;
    longPressActivated = false;
    downIntervalStart = -1;
    downIntervalEnd = -1;
    return true;
  }

  private int xToFrame(float x) {
    // Вычитаем paddingPx, так как волна сдвинута вправо
    double f = viewStartFrame + (x - paddingPx) * viewFramesPerPixel;
    return Math.max(0, Math.min(totalFrames, (int) Math.round(f)));
  }

  private float frameToX(int frame) {
    return (float) ((frame - viewStartFrame) / viewFramesPerPixel);
  }

  private void clampView() {
    if (totalFrames <= 0) {
      viewStartFrame = 0;
      return;
    }

    int visibleWidth = getWidth() - 2 * paddingPx;
    if (visibleWidth <= 0)
      return;

    double visibleFrames = visibleWidth * viewFramesPerPixel;

    // Левый предел: разрешаем уехать в пустоту на пол-экрана
    double minStart = -visibleFrames / 2.0;

    // Правый предел: разрешаем уехать вправо на пол-экрана ЗА КОНЕЦ файла
    double baseMaxStart = Math.max(0, totalFrames - visibleFrames);
    double maxStart = baseMaxStart + (visibleFrames / 2.0);

    if (viewStartFrame < minStart)
      viewStartFrame = minStart;
    if (viewStartFrame > maxStart)
      viewStartFrame = maxStart;
  }

  @Override
  protected void onSizeChanged(int w, int h, int ow, int oh) {
    super.onSizeChanged(w, h, ow, oh);
    if (w > 0 && totalFrames > 0) {
      if (viewFramesPerPixel <= 1)
        zoomToFit();
    }
    updateCheckboxPositions(w, h);
    invalidatePeakCache();
  }

  /** Пересчитать позиции чекбоксов L/R по текущему размеру вьюхи. */
  private void updateCheckboxPositions(int w, int h) {
    int waveTop = rulerHeightPx;
    int waveH = h - waveTop;
    if (waveH <= 0 || w <= 0) {
      checkLx = checkLy = checkRx = checkRy = 0f;
      return;
    }
    checkLx = w - checkSize - 25;
    checkLy = waveTop + waveH / 2 - checkSize - 25;
    checkRx = w - checkSize - 25;
    checkRy = waveTop + waveH / 2 + 25;
  }

  private void invalidatePeakCache() {
    peakCacheWidth = 0;
  }

  private void buildPeakCache(int width) {
    if (pcm == null || totalFrames == 0 || width <= 0) {
      peakCacheWidth = width;
      return;
    }
    peakMin = new float[width];
    peakMax = new float[width];
    peakMinR = new float[width];
    peakMaxR = new float[width];
    peakMinMono = new float[width];
    peakMaxMono = new float[width];

    double start = viewStartFrame;
    double fpp = viewFramesPerPixel;

    for (int x = 0; x < width; x++) {
      int f0 = (int) Math.floor(start + x * fpp);
      int f1 = (int) Math.floor(start + (x + 1) * fpp);
      if (f1 <= f0)
        f1 = f0 + 1;
      if (f0 < 0)
        f0 = 0;
      if (f1 > totalFrames)
        f1 = totalFrames;

      float minL = 0f, maxL = 0f;
      float minR = 0f, maxR = 0f;

      // Адаптивный шаг: не больше 64 сэмплов на пиксель, но не меньше 1.
      // Второй проход без шага убран — он делал O(N) на каждый пиксель.
      int span = f1 - f0;
      int step = Math.max(1, span / 64);

      for (int f = f0; f < f1; f += step) {
        float l = pcm[f * 2];
        float r = pcm[f * 2 + 1];
        if (l < minL)
          minL = l;
        if (l > maxL)
          maxL = l;
        if (r < minR)
          minR = r;
        if (r > maxR)
          maxR = r;
      }

      // Гарантируем, что последний фрейм диапазона учтён
      // (важно для хвоста, если span не кратен step).
      if (f1 > f0) {
        int last = f1 - 1;
        float l = pcm[last * 2];
        float r = pcm[last * 2 + 1];
        if (l < minL)
          minL = l;
        if (l > maxL)
          maxL = l;
        if (r < minR)
          minR = r;
        if (r > maxR)
          maxR = r;
      }

      peakMin[x] = minL;
      peakMax[x] = maxL;
      peakMinR[x] = minR;
      peakMaxR[x] = maxR;
      peakMinMono[x] = Math.min(minL, minR);
      peakMaxMono[x] = Math.max(maxL, maxR);
    }
    peakCacheWidth = width;
    peakCacheStart = start;
    peakCacheFpp = fpp;
  }

  /** Найти ближайший zero-crossing (по сумме L+R) в пределах 2048 фреймов. */
  private int nearestZeroCross(int frame) {
    if (pcm == null || totalFrames == 0)
      return frame;
    frame = Math.max(0, Math.min(totalFrames - 1, frame));
    int search = Math.min(2048, totalFrames / 2);
    int best = frame;
    int bestDist = Integer.MAX_VALUE;
    for (int d = 0; d <= search; d++) {
      int a = frame - d;
      int b = frame + d;
      if (a >= 0 && isZeroCrossAt(a)) {
        if (d < bestDist) {
          bestDist = d;
          best = a;
        }
      }
      if (b < totalFrames && isZeroCrossAt(b)) {
        if (d < bestDist) {
          bestDist = d;
          best = b;
        }
      }
      if (bestDist <= 1)
        break;
    }
    return best;
  }

  /** Найти ближайший маркер к указанному фрейму (в пределах tolerancePx пикселей). */
  private int nearestMarker(int frame, int tolerancePx) {
    if (markers == null || markers.isEmpty())
      return -1;

    float targetX = frameToX(frame);
    int bestMarker = -1;
    float bestDist = tolerancePx;

    for (int m : markers) {
      float mx = frameToX(m);
      float dist = Math.abs(targetX - mx);
      if (dist < bestDist) {
        bestDist = dist;
        bestMarker = m;
      }
    }
    return bestMarker;
  }

  private boolean isZeroCrossAt(int frame) {
    if (frame <= 0 || frame >= totalFrames)
      return true;
    float prevL = pcm[(frame - 1) * 2];
    float prevR = pcm[(frame - 1) * 2 + 1];
    float curL = pcm[frame * 2];
    float curR = pcm[frame * 2 + 1];
    float prevSum = prevL + prevR;
    float curSum = curL + curR;
    return (prevSum <= 0f && curSum >= 0f) || (prevSum >= 0f && curSum <= 0f);
  }

  public boolean isZeroCrossingSnap() {
    return zeroCrossingSnap;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    int w = getWidth();
    int h = getHeight();
    if (w <= 0 || h <= 0)
      return;

    int rulerH = rulerHeightPx;
    int waveTop = rulerH;
    int waveBottom = h;
    int waveH = waveBottom - waveTop;
    int centerY = waveTop + waveH / 2;

    // 1. Фон и линейка
    canvas.drawRect(0, 0, w, rulerH, pRuler);

    // 2. Сдвиг для волны
    canvas.save();
    canvas.translate(paddingPx, 0);
    int waveWidth = w - 2 * paddingPx;
    lastWaveTop = waveTop;
    lastWaveBottom = waveBottom;
    lastWaveWidth = waveWidth;

    drawRuler(canvas, waveWidth, rulerH);

    if (pcm == null || totalFrames == 0) {
      drawCenterLine(canvas, centerY, waveWidth);
      canvas.restore();
      return;
    }

    // 3. Сетка и пустота
    drawGrid(canvas, waveWidth, waveTop, waveBottom);

    float zeroX = frameToX(0);
    float endX = frameToX(totalFrames);
    int startX = (int) Math.max(0, zeroX);
    int endXInt = (int) Math.min(waveWidth, endX);

    drawEmptySpace(canvas, zeroX, endX, waveWidth, waveTop, waveBottom);

    if (peakCacheWidth != waveWidth || peakCacheStart != viewStartFrame
        || peakCacheFpp != viewFramesPerPixel) {
      buildPeakCache(waveWidth);
    }

    // 4. Горизонтальные линии уровня
    drawLevels(canvas, waveTop, waveBottom, waveH, centerY, startX, endXInt);

    // 5. Выделение (подложка)
    drawSelectionBackground(canvas, waveTop, waveBottom);

    // 6. Волна
    drawWaveform(canvas, waveWidth, waveTop, waveBottom, waveH, centerY, startX, endXInt);

    // 7. Рамка выделения и ручки
    drawSelectionBorder(canvas, waveTop, waveBottom);

    // 8. Маркеры
    drawMarkers(canvas, waveWidth, waveTop, waveBottom);
    drawExportIntervals(canvas, waveWidth, waveTop);

    if (intervalsEditMode && exportIntervals != null) {
      drawIntervalsEditMode(canvas, waveWidth, waveTop, waveBottom);
    }

    // 9. Playhead (красная и зелёная линии)
    drawPlayheads(canvas, waveWidth, waveTop, waveBottom);

    canvas.restore();

    // 10. Шкала амплитуды (вне сдвинутого canvas)
    drawAmpScale(canvas, w - ampScaleWidthPx, w, waveTop, waveBottom, waveH);

    // 11. Чекбоксы L/R
    drawChannelCheckboxes(canvas, w, waveTop, waveH);
  }

  private void drawEmptySpace(Canvas canvas, float zeroX, float endX, int waveWidth, int waveTop,
      int waveBottom) {
    if (zeroX > 0)
      canvas.drawRect(0, waveTop, zeroX, waveBottom, pEmpty);
    if (endX < waveWidth)
      canvas.drawRect(endX, waveTop, waveWidth, waveBottom, pEmpty);
  }

  private void drawLevels(Canvas canvas, int waveTop, int waveBottom, int waveH, int centerY,
      int startX, int endXInt) {
    if (startX >= endXInt)
      return;
    if (stereoView) {
      drawHorizontalLevels(canvas, waveTop + waveH / 4, waveH * 0.25f - 2, waveTop,
          waveTop + waveH / 2, startX, endXInt);
      drawHorizontalLevels(canvas, waveTop + 3 * waveH / 4, waveH * 0.25f - 2, waveTop + waveH / 2,
          waveBottom, startX, endXInt);
    } else {
      drawHorizontalLevels(canvas, centerY, waveH * 0.5f - 2, waveTop, waveBottom, startX, endXInt);
    }
  }

  private void drawSelectionBackground(Canvas canvas, int waveTop, int waveBottom) {
    if (!hasSelection())
      return;
    float sx0 = frameToX(selStart);
    float sx1 = frameToX(selEnd);
    canvas.drawRect(sx0, waveTop, sx1, waveBottom, pSel);
  }

  private void drawWaveform(Canvas canvas, int waveWidth, int waveTop, int waveBottom, int waveH,
      int centerY, int startX, int endXInt) {
    if (peakMin == null || peakMax == null || startX >= endXInt)
      return;

    if (stereoView) {
      float halfH = waveH * 0.25f - 2;
      int topCenter = waveTop + waveH / 4;
      int botCenter = waveTop + 3 * waveH / 4;
      // L
      drawWaveChannel(canvas, peakMax, peakMin, waveWidth, startX, endXInt, topCenter, halfH);
      // R
      drawWaveChannel(canvas, peakMaxR, peakMinR, waveWidth, startX, endXInt, botCenter, halfH);
      drawCenterLine(canvas, centerY, waveWidth);
    } else {
      float halfH = waveH * 0.5f - 2;
      drawWaveChannel(canvas, peakMaxMono, peakMinMono, waveWidth, startX, endXInt, centerY, halfH);
      drawCenterLine(canvas, centerY, waveWidth);
    }
  }

  private void drawWaveChannel(Canvas canvas, float[] max, float[] min, int waveWidth, int startX,
      int endXInt, int centerY, float halfH) {
    wavePath.reset();
    for (int x = startX; x < endXInt; x++) {
      float y = centerY - max[x] * halfH;
      if (x == startX)
        wavePath.moveTo(x, y);
      else
        wavePath.lineTo(x, y);
    }
    for (int x = endXInt - 1; x >= startX; x--) {
      float y = centerY - min[x] * halfH;
      wavePath.lineTo(x, y);
    }
    wavePath.close();
    canvas.drawPath(wavePath, pWaveFill);
    canvas.drawPath(wavePath, pWave);
  }

  private void drawSelectionBorder(Canvas canvas, int waveTop, int waveBottom) {
    if (!hasSelection())
      return;
    float sx0 = frameToX(selStart);
    float sx1 = frameToX(selEnd);
    canvas.drawLine(sx0, waveTop, sx0, waveBottom, pSelBorder);
    canvas.drawLine(sx1, waveTop, sx1, waveBottom, pSelBorder);
    drawHandle(canvas, sx0, waveTop, waveBottom);
    drawHandle(canvas, sx1, waveTop, waveBottom);
  }

  private void drawMarkers(Canvas canvas, int waveWidth, int waveTop, int waveBottom) {
    if (markers == null)
      return;
    for (int m : markers) {
      float x = frameToX(m);
      if (x < -markerFlagW || x > waveWidth + markerFlagW)
        continue;
      canvas.drawLine(x, waveTop, x, waveBottom, pMarker);

      // Верхний флажок
      Path flag = new Path();
      flag.moveTo(x, waveTop + 2);
      flag.lineTo(x + markerFlagW, waveTop + 2);
      flag.lineTo(x + markerFlagW, waveTop + 2 + markerFlagH / 2f);
      flag.lineTo(x, waveTop + 2 + markerFlagH);
      flag.close();
      canvas.drawPath(flag, pMarkerFill);

      // Нижний флажок
      float botY = waveBottom - markerFlagH - 2;
      Path flag2 = new Path();
      flag2.moveTo(x, botY);
      flag2.lineTo(x + markerFlagW, botY);
      flag2.lineTo(x + markerFlagW, botY + markerFlagH / 2f);
      flag2.lineTo(x, botY + markerFlagH);
      flag2.close();
      canvas.drawPath(flag2, pMarkerFill);
    }
  }

  private void drawPlayheads(Canvas canvas, int waveWidth, int waveTop, int waveBottom) {
    float px = frameToX(playheadFrame);
    if (px >= 0 && px <= waveWidth) {
      canvas.drawLine(px, waveTop, px, waveBottom, pPlayhead);
      canvas.drawRect(px - 5, waveTop, px + 5, waveTop + 8, pPlayhead);
    }

    float activePx = frameToX(activePlayFrame);
    if (activePx >= 0 && activePx <= waveWidth && activePlayFrame != playheadFrame) {
      canvas.drawLine(activePx, waveTop, activePx, waveBottom, pActivePlayhead);
    }
  }

  private void drawChannelCheckboxes(Canvas canvas, int w, int waveTop, int waveH) {
    if (!stereoView)
      return;
    if (checkLx == 0f && checkLy == 0f) {
      // onSizeChanged ещё не вызывался (маловероятно) — пересчитываем на всякий случай
      updateCheckboxPositions(w, getHeight());
    }

    // L (верхняя волна)
    drawCheckbox(canvas, checkLx, checkLy, channelL);
    pChannelText.setColor(channelL ? 0xFF0E0E10 : 0xFFE8E8EA);
    canvas.drawText("L", checkLx + checkSize / 2f - 10, checkLy + checkSize / 2f + 10,
        pChannelText);

    // R (нижняя волна)
    drawCheckbox(canvas, checkRx, checkRy, channelR);
    pChannelText.setColor(channelR ? 0xFF0E0E10 : 0xFFE8E8EA);
    canvas.drawText("R", checkRx + checkSize / 2f - 10, checkRy + checkSize / 2f + 10,
        pChannelText);
  }

  private void drawExportIntervals(Canvas canvas, int waveWidth, int waveTop) {
    if (exportIntervals == null || exportIntervals.isEmpty())
      return;
    if (intervalsEditMode)
      return;

    float[] layout = calcStripLayout();
    float startY = layout[0];
    float stripH = 8f;
    int count = (int) layout[2];
    int startIdx = (int) layout[3];
    int endIdx = (int) layout[4];

    if (count <= 0)
      return;

    float offsetY = startY;
    for (int i = startIdx; i < endIdx; i++) {
      int[] interval = exportIntervals.get(i);
      float x1 = frameToX(interval[0]);
      float x2 = frameToX(interval[1]);

      if (x2 < 0 || x1 > waveWidth) {
        offsetY += stripH + 8f;
        continue;
      }

      float drawX1 = Math.max(0, x1);
      float drawX2 = Math.min(waveWidth, x2);

      tmpRect.set(drawX1, offsetY, drawX2, offsetY + stripH);
      canvas.drawRoundRect(tmpRect, 4, 4, pIntervalFillNormal);

      offsetY += stripH + 8f;
    }
  }

  private void drawCheckbox(Canvas c, float x, float y, boolean checked) {
    float cornerRadius = 8f * getResources().getDisplayMetrics().density;
    tmpRect.set(x, y, x + checkSize, y + checkSize);

    c.drawRoundRect(tmpRect, cornerRadius, cornerRadius, checked ? pCheckboxBgOn : pCheckboxBgOff);
    c.drawRoundRect(tmpRect, cornerRadius, cornerRadius,
        checked ? pCheckboxBorderOn : pCheckboxBorderOff);
  }

  private void drawRuler(Canvas canvas, int w, int rulerH) {
    if (sampleRate <= 0)
      return;
    double[] steps = { 0.01, 0.05, 0.1, 0.5, 1.0, 5.0, 10.0, 30.0, 60.0 };
    double visibleSec = w * viewFramesPerPixel / sampleRate;
    double step = steps[steps.length - 1];
    for (double s : steps) {
      if (visibleSec / s < 12) {
        step = s;
        break;
      }
    }
    double startSec = viewStartFrame / (double) sampleRate;
    double firstMark = Math.ceil(startSec / step) * step;
    double endSec = (viewStartFrame + w * viewFramesPerPixel) / (double) sampleRate;

    for (double t = firstMark; t <= endSec; t += step) {
      // Пропускаем метки, которые находятся за пределами файла
      if (t < 0 || t * sampleRate > totalFrames)
        continue;
      float x = (float) (((t * sampleRate) - viewStartFrame) / viewFramesPerPixel);
      if (x < 0 || x > w)
        continue;
      canvas.drawLine(x, rulerH - 6, x, rulerH, pGrid);
      String label;
      if (step < 1.0) {
        label = String.format(java.util.Locale.US, "%.0fms", t * 1000);
      } else {
        int sec = (int) t;
        label = (sec / 60) + ":" + String.format(java.util.Locale.US, "%02d", sec % 60);
      }
      canvas.drawText(label, x + 3, rulerH - 9, pRulerText);
    }
  }

  private void drawGrid(Canvas canvas, int w, int top, int bottom) {
    if (sampleRate <= 0)
      return;
    double[] steps = { 0.01, 0.05, 0.1, 0.5, 1.0, 5.0, 10.0, 30.0, 60.0 };
    double visibleSec = w * viewFramesPerPixel / sampleRate;
    double step = steps[steps.length - 1];
    for (double s : steps) {
      if (visibleSec / s < 12) {
        step = s;
        break;
      }
    }
    double startSec = viewStartFrame / (double) sampleRate;
    double firstMark = Math.ceil(startSec / step) * step;
    double endSec = (viewStartFrame + w * viewFramesPerPixel) / (double) sampleRate;

    for (double t = firstMark; t <= endSec; t += step) {
      // Пропускаем метки, которые находятся за пределами файла
      if (t < 0 || t * sampleRate > totalFrames)
        continue;
      float x = (float) (((t * sampleRate) - viewStartFrame) / viewFramesPerPixel);
      if (x < 0 || x > w)
        continue;
      canvas.drawLine(x, top, x, bottom, pGrid);
    }
  }

  private void drawHandle(Canvas canvas, float x, int top, int bottom) {
    float cyTop = top + handleHalfPx;
    float cyBot = bottom - handleHalfPx;
    canvas.drawCircle(x, cyTop, handleHalfPx, pSelBorder);
    canvas.drawCircle(x, cyBot, handleHalfPx, pSelBorder);
    canvas.drawLine(x, cyTop, x, cyBot, pSelBorder);
  }

  /**
   * Рисует горизонтальные линии уровня (0, -6, -12, -24, -48 dB) и подписи.
   * 
   * @param centerY центральная линия (0 амплитуды)
   * @param halfH   половина высоты (где амплитуда = 1.0)
   * @param top     верхняя граница области
   * @param bottom  нижняя граница области
   * @param x0      левый край
   * @param x1      правый край
   */
  private void drawHorizontalLevels(Canvas canvas, int centerY, float halfH, int top, int bottom,
      int x0, int x1) {
    float[] levels = { 0f, 0.5f, 0.7f, 0.9f, 0.999f }; // амплитуды
    String[] labels = { "-inf", "-6", "-3", "-1", "0" };

    for (int i = 0; i < levels.length; i++) {
      float amp = levels[i];
      float dy = amp * halfH;

      // Верхняя линия (положительная амплитуда)
      float yTop = centerY - dy;
      if (yTop >= top && yTop <= bottom) {
        canvas.drawLine(x0, yTop, x1, yTop, pHGrid);
      }
      // Нижняя линия (отрицательная амплитуда)
      float yBot = centerY + dy;
      if (yBot >= top && yBot <= bottom) {
        canvas.drawLine(x0, yBot, x1, yBot, pHGrid);
      }
    }
  }

  private void drawCenterLine(Canvas canvas, int centerY, int waveWidth) {
    float zeroX = frameToX(0);
    float endX = frameToX(totalFrames);

    // Если начало файла уехало за левый край (zeroX < 0),
    // то видимая часть файла начинается с 0.
    // Если начало файла правее 0 (zeroX > 0), то линия должна начинаться с zeroX.
    float startDraw = Math.max(0, zeroX);
    float endDraw = Math.min(waveWidth, endX);
    android.util.Log.d("WAVE", "drawCenterLine: zeroX=" + zeroX + " endX=" + endX + " startDraw="
        + startDraw + " endDraw=" + endDraw);
    // Дополнительная проверка: если startDraw > endDraw, значит, файла вообще не видно
    if (startDraw < endDraw) {
      canvas.drawLine(startDraw, centerY, endDraw, centerY, pCenter);
    }
  }

  private void drawAmpScale(Canvas canvas, int scaleX, int w, int top, int bottom, int waveH) {
    // Фон шкалы
    canvas.drawRect(scaleX, top, w, bottom, pScaleBg);

    int centerY = (top + bottom) / 2;
    float halfH = waveH * 0.5f - 2;

    // Для стерео — две половины
    if (stereoView) {
      drawScaleHalf(canvas, scaleX, w, top, top + waveH / 2, waveH * 0.25f - 2);
      drawScaleHalf(canvas, scaleX, w, top + waveH / 2, bottom, waveH * 0.25f - 2);
    } else {
      drawScaleHalf(canvas, scaleX, w, top, bottom, halfH);
    }
  }

  private void drawScaleHalf(Canvas canvas, int scaleX, int w, int top, int bottom, float halfH) {
    int centerY = (top + bottom) / 2;
    float[] levels = { 0f, 0.5f, 0.7f, 0.9f, 0.999f };
    String[] labels = { "-inf", "-6", "-3", "-1", "0" };

    for (int i = 0; i < levels.length; i++) {
      float amp = levels[i];
      float dy = amp * halfH;
      float yTop = centerY - dy;
      float yBot = centerY + dy;

      if (yTop >= top && yTop <= bottom) {
        canvas.drawLine(scaleX, yTop, scaleX + 6, yTop, pAmpScale);
        canvas.drawText(labels[i], scaleX + 8, yTop + 3, pAmpText);
      }
      if (i > 0 && yBot >= top && yBot <= bottom) {
        canvas.drawLine(scaleX, yBot, scaleX + 6, yBot, pAmpScale);
      }
    }
  }

  public void updateExportIntervals(List<int[]> intervals) {
    this.exportIntervals = intervals;
    invalidate();
  }

  public void setIntervalsEditMode(boolean enabled) {
    this.intervalsEditMode = enabled;
    this.intervalPage = 0;
    this.selectedIntervalIndex = -1;
    invalidate();
  }

  public boolean isIntervalsEditMode() {
    return intervalsEditMode;
  }

  public void nextIntervalPage() {
    if (exportIntervals == null)
      return;
    int maxPage = (exportIntervals.size() - 1) / intervalPageSize;
    if (intervalPage < maxPage) {
      intervalPage++;
      selectedIntervalIndex = -1;
      invalidate();
    }
  }

  public void prevIntervalPage() {
    if (intervalPage > 0) {
      intervalPage--;
      selectedIntervalIndex = -1;
      invalidate();
    }
  }

  private void drawIntervalsEditMode(Canvas canvas, int waveWidth, int waveTop, int waveBottom) {
    if (exportIntervals == null || exportIntervals.isEmpty())
      return;

    float[] layout = calcStripLayout();
    float startY = layout[0];
    float stripH = layout[1];
    int count = (int) layout[2];
    int startIdx = (int) layout[3];
    int endIdx = (int) layout[4];

    if (count <= 0)
      return;

    float offsetY = startY;
    for (int i = startIdx; i < endIdx; i++) {
      int[] interval = exportIntervals.get(i);
      float x1 = frameToX(interval[0]);
      float x2 = frameToX(interval[1]);

      if (x2 < 0 || x1 > waveWidth) {
        offsetY += stripH + 8f;
        continue;
      }

      float drawX1 = Math.max(0, x1);
      float drawX2 = Math.min(waveWidth, x2);

      android.graphics.RectF rect = new android.graphics.RectF(drawX1, offsetY, drawX2,
          offsetY + stripH);

      if (i == selectedIntervalIndex) {
        canvas.drawRoundRect(rect, 6, 6, pIntervalFill);
        canvas.drawRoundRect(rect, 6, 6, pIntervalStroke);
      } else {
        canvas.drawRoundRect(rect, 6, 6, pIntervalFill);
      }

      String label = (i + 1) + ": " + formatMsForStrip(interval[0]) + " – "
          + formatMsForStrip(interval[1]);
      canvas.drawText(label, drawX1 + 8, offsetY + stripH / 2 + 4, pIntervalText);

      offsetY += stripH + 8f;
    }
  }

  private String formatMsForStrip(int frame) {
    int ms = frame * 1000 / sampleRate;
    int m = ms / 60000;
    int s = (ms % 60000) / 1000;
    return String.format(java.util.Locale.US, "%02d:%02d", m, s);
  }

  /**
   * Вычисляет раскладку полос интервалов. Возвращает массив: [startY, stripH, count, startIdx, endIdx]. Не зависит от состояния onDraw — можно вызывать в любой момент.
   */
  private float[] calcStripLayout() {
    int startIdx = intervalPage * intervalPageSize;
    int endIdx = Math.min(startIdx + intervalPageSize, exportIntervals.size());
    int count = endIdx - startIdx;

    int waveTop = rulerHeightPx;
    int waveH = getHeight() - waveTop;
    if (waveH <= 0) {
      // Вьюха ещё не измерена — вернуть пустую раскладку
      return new float[] { 0f, 8f, 0, startIdx, endIdx };
    }

    float stripH;
    if (intervalsEditMode) {
      stripH = Math.min(40f, waveH / Math.max(1, count + 1));
      if (stripH < 24f)
        stripH = 24f;
    } else {
      stripH = 8f;
    }

    float totalH = count * stripH + Math.max(0, count - 1) * 8f;
    float startY = waveTop + (waveH - totalH) / 2f;

    return new float[] { startY, stripH, count, startIdx, endIdx };
  }

}
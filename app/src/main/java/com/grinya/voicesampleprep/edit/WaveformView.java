package com.grinya.voicesampleprep.edit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * Вьюха для отображения и редактирования PCM. - Multi-resolution peak-cache. - Pinch-zoom по X (и Y отдельно двумя пальцами — не делаем, чтобы не путать). - Скролл по X. - Выделение диапазона с ручками. - Маркеры (тап по линейке). - Playhead.
 */
public class WaveformView extends View {

  public interface Listener {
    /** Выделение изменилось. */
    void onSelectionChanged(int startFrame, int endFrame);

    /** Тап по волне (не выделение). */
    void onTap(int frame);

    /** Маркеры изменились (по тапу с Shift — не будем, отдельный режим). */
    void onPlayheadDragged(int frame);

    /** Тап по существующему маркеру. */
    void onMarkerTapped(int frame);

    void onMarkerLongPressed(int frame);
  }

  // ---------- цвета ----------
  private int colorBg = 0xFF0E0E10;
  private int colorWave = 0xFF9AA0A6;
  private int colorWaveSelected = 0xFFFFB300;
  private int colorCenterLine = 0xFF2A2A2E;
  private int colorPlayhead = 0xFFFF3B30;
  private int colorSelection = 0x33FFB300;
  private int colorSelectionBorder = 0xFFFFB300;
  private int colorMarker = 0xFF4FC3F7;
  private int colorGrid = 0xFF1F1F22;
  private int colorRuler = 0xFF2A2A2E;
  private int colorRulerText = 0xFF808080;
  private int playheadFrame = 0;
  private int activePlayFrame = 0; // Новая переменная для зелёной линии
  // private int targetActiveFrame = 0;
  // private android.view.Choreographer.FrameCallback frameCallback;
  // private boolean isAnimating = false;

  // ---------- геометрия ----------
  private static final int RULER_HEIGHT_DP = 22;
  private static final int HANDLE_HALF_DP = 10;
  private static final int MARKER_FLAG_W_DP = 10; // ширина ярлычка
  private static final int MARKER_FLAG_H_DP = 14; // высота ярлычка

  // ---------- данные ----------
  private float[] pcm;
  private int totalFrames = 0;
  private int sampleRate = 48000;
  private List<Integer> markers;
  private boolean pendingFit = false;

  // ---------- viewport ----------
  // viewStartFrame = первый фрейм, соответствующий x=0
  // viewFramesPerPixel = сколько фреймов на пиксель
  private double viewStartFrame = 0;
  private double viewFramesPerPixel = 1;

  // ---------- выделение ----------
  private int selStart = -1;
  private int selEnd = -1;
  private int markerFlagW;
  private int markerFlagH;

  // ---------- paints ----------
  private final Paint pWave = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pWaveSel = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pCenter = new Paint();
  private final Paint pPlayhead = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pSel = new Paint();
  private final Paint pSelBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pMarker = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pGrid = new Paint();
  private final Paint pRuler = new Paint();
  private final Paint pRulerText = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pMarkerFill = new Paint(Paint.ANTI_ALIAS_FLAG);

  private final Path wavePath = new Path();

  // ---------- жесты ----------
  private GestureDetector gestureDetector;
  private ScaleGestureDetector scaleDetector;
  private Listener listener;

  // drag state
  private static final int DRAG_NONE = 0;
  private static final int DRAG_SCROLL = 1;
  private static final int DRAG_SEL_START = 2;
  private static final int DRAG_SEL_END = 3;
  private static final int DRAG_NEW_SELECTION = 4;
  private int dragMode = DRAG_NONE;
  private float lastTouchX;
  private float downX;
  private float downY;
  private int downSelStart;
  private int downSelEnd;

  private static final int DRAG_LONG_PRESS_PENDING = 5;
  private boolean longPressActivated = false;
  private int downMarkerFrame = -1;
  private final Runnable longPressRunnable = new Runnable() {
    @Override
    public void run() {
      if (downMarkerFrame != -1) {
        // Долгий тап по маркеру
        if (listener != null)
          listener.onMarkerLongPressed(downMarkerFrame);
        longPressActivated = true;
        downMarkerFrame = -1;
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        return;
      }
      // Стандартный долгий тап (создание выделения)
      if (dragMode == DRAG_SCROLL || dragMode == DRAG_LONG_PRESS_PENDING) {
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

  // ---------- peak cache ----------
  // Кэш: для каждого пикселя при текущем viewFramesPerPixel храним min/max.
  // Пересчитывается при изменении зума/скролла.
  private float[] peakMin;
  private float[] peakMax;
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

    pWave.setColor(colorWave);
    pWave.setStrokeWidth(1f);
    pWave.setStyle(Paint.Style.STROKE);

    pWaveSel.setColor(colorWaveSelected);
    pWaveSel.setStrokeWidth(1f);
    pWaveSel.setStyle(Paint.Style.STROKE);

    pCenter.setColor(colorCenterLine);
    pCenter.setStrokeWidth(1f);

    pPlayhead.setColor(colorPlayhead);
    pPlayhead.setStrokeWidth(2f * density);

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

    pRuler.setColor(colorRuler);
    pRuler.setStyle(Paint.Style.FILL);

    pRulerText.setColor(colorRulerText);
    pRulerText.setTextSize(10f * density);

    setBackgroundColor(colorBg);

    gestureDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
      @Override
      public boolean onDown(MotionEvent e) {
        return true;
      }

      @Override
      public boolean onDoubleTap(MotionEvent e) {
        // сброс зума/скролла
        viewStartFrame = 0;
        viewFramesPerPixel = totalFrames > 0 ? (double) totalFrames / Math.max(1, getWidth()) : 1;
        invalidatePeakCache();
        invalidate();
        return true;
      }

      @Override
      public boolean onSingleTapUp(MotionEvent e) {
        return false; // тап обрабатывается в ACTION_UP
      }
    });

    scaleDetector = new ScaleGestureDetector(c,
        new ScaleGestureDetector.SimpleOnScaleGestureListener() {
          @Override
          public boolean onScale(ScaleGestureDetector d) {
            double oldFpp = viewFramesPerPixel;
            double newFpp = oldFpp / d.getScaleFactor();
            // ограничения
            double minFpp = 1.0 / 8.0; // максимум зума: 8 px на фрейм
            double maxFpp = Math.max(1, totalFrames); // минимум: весь файл на 1 px (и больше)
            if (newFpp < minFpp)
              newFpp = minFpp;
            if (newFpp > maxFpp)
              newFpp = maxFpp;

            // точка фокуса под пальцами
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

  // ---------- публичный API ----------

  public void setListener(Listener l) {
    this.listener = l;
  }

  public void setData(float[] pcm, int sampleRate, List<Integer> markers) {
    this.pcm = pcm;
    this.sampleRate = sampleRate;
    this.markers = markers;
    this.totalFrames = pcm == null ? 0 : pcm.length / 2;
    this.viewStartFrame = 0;
    this.viewFramesPerPixel = 1; // временно
    this.selStart = -1;
    this.selEnd = -1;
    this.playheadFrame = 0;
    this.activePlayFrame = 0;
    pendingFit = true; // ← запомнили, что надо отфитить, когда будет ширина
    invalidatePeakCache();
    invalidate();
  }

  /** Обновить только PCM (после операции), сохранив viewport. */
  public void updatePcm(float[] newPcm) {
    this.pcm = newPcm;
    this.totalFrames = newPcm == null ? 0 : newPcm.length / 2;
    if (selStart > totalFrames)
      selStart = -1;
    if (selEnd > totalFrames)
      selEnd = -1;
    if (playheadFrame > totalFrames)
      playheadFrame = totalFrames;
    this.activePlayFrame = Math.min(playheadFrame, totalFrames);
    invalidatePeakCache();
    invalidate();
  }

  public void setPlayheadFrame(int f) {
    if (f == playheadFrame)
      return;
    playheadFrame = Math.max(0, Math.min(totalFrames, f));
    invalidate();
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

  /** Показать/скрыть сетку времени (по умолчанию показана). */
  public void zoomToFit() {
    int w = getWidth();
    if (w <= 0 || totalFrames <= 0)
      return;
    viewStartFrame = 0;
    viewFramesPerPixel = (double) totalFrames / w;
    clampView();
    invalidatePeakCache();
    invalidate();
  }

  // ---------- жесты ----------

  @Override
  public boolean onTouchEvent(MotionEvent e) {
    scaleDetector.onTouchEvent(e);
    gestureDetector.onTouchEvent(e); // нужен только для double-tap

    // тап по линейке
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
        lastTouchX = e.getX();
        downX = e.getX();
        downY = e.getY();
        downSelStart = selStart;
        downSelEnd = selEnd;

        // 1. Проверка на попадание в ручки выделения
        if (hasSelection() && e.getY() >= rulerHeightPx) {
          float sxStart = frameToX(selStart);
          float sxEnd = frameToX(selEnd);
          if (Math.abs(e.getX() - sxStart) <= handleHalfPx * 2) {
            dragMode = DRAG_SEL_START;
            return true;
          }
          if (Math.abs(e.getX() - sxEnd) <= handleHalfPx * 2) {
            dragMode = DRAG_SEL_END;
            return true;
          }
        }

        // 2. Проверка на попадание в маркер (увеличенная зона: 20px влево и вправо)
        downMarkerFrame = -1;
        if (markers != null) {
          for (int m : markers) {
            float mx = frameToX(m);
            if (e.getX() >= mx - 20 && e.getX() <= mx + markerFlagW + 20) {
              downMarkerFrame = m;
              break;
            }
          }
        }

        // 3. Инициализация скролла и таймера долгого нажатия
        dragMode = DRAG_SCROLL;
        longPressActivated = false;
        postDelayed(longPressRunnable, 350);
        return true;

      case MotionEvent.ACTION_MOVE:
        if (scaleDetector.isInProgress())
          return true;

        // Если сдвинули палец больше чем на 12px — отменяем долгий тап
        if (!longPressActivated && Math.abs(e.getX() - downX) + Math.abs(e.getY() - downY) > 12) {
          removeCallbacks(longPressRunnable);
          downMarkerFrame = -1; // Сбрасываем, так как это уже скролл, а не тап по маркеру
        }

        if (dragMode == DRAG_SCROLL) {
          double dx = lastTouchX - e.getX();
          viewStartFrame += dx * viewFramesPerPixel;
          clampView();
          lastTouchX = e.getX();
          invalidatePeakCache();
          invalidate();
          return true;
        } else if (dragMode == DRAG_SEL_START) {
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
        } else if (dragMode == DRAG_SEL_END) {
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
        } else if (dragMode == DRAG_NEW_SELECTION) {
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

      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        removeCallbacks(longPressRunnable);

        // Если это был короткий тап (не долгий и не скролл)
        if (e.getActionMasked() == MotionEvent.ACTION_UP && Math.abs(e.getX() - downX) < 8
            && Math.abs(e.getY() - downY) < 8 && e.getY() >= rulerHeightPx && !longPressActivated) {

          if (downMarkerFrame != -1) {
            // Короткий тап по маркеру — переход
            if (listener != null)
              listener.onMarkerTapped(downMarkerFrame);
          } else {
            // Короткий тап по волне — перемещение playhead
            if (listener != null)
              listener.onTap(xToFrame(e.getX()));
          }
        }

        dragMode = DRAG_NONE;
        downMarkerFrame = -1;
        longPressActivated = false;
        return true;
    }
    return super.onTouchEvent(e);
  }
  // ---------- преобразования ----------

  private int xToFrame(float x) {
    double f = viewStartFrame + x * viewFramesPerPixel;
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
    double visibleFrames = getWidth() * viewFramesPerPixel;
    double maxStart = Math.max(0, totalFrames - visibleFrames);
    if (viewStartFrame < 0)
      viewStartFrame = 0;
    if (viewStartFrame > maxStart)
      viewStartFrame = maxStart;
  }

  // ---------- отрисовка ----------

  @Override
  protected void onSizeChanged(int w, int h, int ow, int oh) {
    super.onSizeChanged(w, h, ow, oh);
    if (w > 0 && totalFrames > 0 && (pendingFit || viewFramesPerPixel <= 0)) {
      zoomToFit();
      pendingFit = false;
    } else {
      invalidatePeakCache();
    }
  }

  private void invalidatePeakCache() {
    peakCacheWidth = 0; // пересчитаем на следующей отрисовке
  }

  private void buildPeakCache(int width) {
    if (pcm == null || totalFrames == 0 || width <= 0) {
      peakCacheWidth = width;
      peakCacheStart = viewStartFrame;
      peakCacheFpp = viewFramesPerPixel;
      return;
    }
    peakMin = new float[width];
    peakMax = new float[width];
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

      float min = 0f, max = 0f;
      // если фреймов на пиксель мало — смотрим каждый
      if (f1 - f0 <= 64) {
        for (int f = f0; f < f1; f++) {
          float l = pcm[f * 2];
          float r = pcm[f * 2 + 1];
          float m = (l + r) * 0.5f;
          if (m < min)
            min = m;
          if (m > max)
            max = m;
        }
      } else {
        // сэмплируем до 64 точек на пиксель
        int step = (f1 - f0) / 64;
        if (step < 1)
          step = 1;
        for (int f = f0; f < f1; f += step) {
          float l = pcm[f * 2];
          float r = pcm[f * 2 + 1];
          float m = (l + r) * 0.5f;
          if (m < min)
            min = m;
          if (m > max)
            max = m;
        }
      }
      peakMin[x] = min;
      peakMax[x] = max;
    }
    peakCacheWidth = width;
    peakCacheStart = start;
    peakCacheFpp = fpp;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    int w = getWidth();
    int h = getHeight();
    if (w <= 0 || h <= 0)
      return;
    if (pendingFit) {
      zoomToFit();
      pendingFit = false;
    }

    int rulerH = rulerHeightPx;
    int waveTop = rulerH;
    int waveBottom = h;
    int waveH = waveBottom - waveTop;
    int centerY = waveTop + waveH / 2;

    // линейка
    canvas.drawRect(0, 0, w, rulerH, pRuler);
    drawRuler(canvas, w, rulerH);

    if (pcm == null || totalFrames == 0) {
      // пусто
      canvas.drawLine(0, centerY, w, centerY, pCenter);
      return;
    }

    // сетка
    drawGrid(canvas, w, waveTop, waveBottom, centerY);

    // peak cache
    if (peakCacheWidth != w || peakCacheStart != viewStartFrame
        || peakCacheFpp != viewFramesPerPixel) {
      buildPeakCache(w);
    }

    // выделение (под волной)
    if (hasSelection()) {
      float sx0 = frameToX(selStart);
      float sx1 = frameToX(selEnd);
      canvas.drawRect(sx0, waveTop, sx1, waveBottom, pSel);
    }

    // волна (по каналам — среднее)
    if (peakMin != null && peakMax != null) {
      float halfH = waveH * 0.5f - 2;
      wavePath.reset();
      // верхняя огибающая
      for (int x = 0; x < w; x++) {
        float y = centerY - peakMax[x] * halfH;
        if (x == 0)
          wavePath.moveTo(x, y);
        else
          wavePath.lineTo(x, y);
      }
      // нижняя огибающая в обратном порядке
      for (int x = w - 1; x >= 0; x--) {
        float y = centerY - peakMin[x] * halfH;
        wavePath.lineTo(x, y);
      }
      wavePath.close();
      canvas.drawPath(wavePath, pWave);
    }

    // рамка выделения
    if (hasSelection()) {
      float sx0 = frameToX(selStart);
      float sx1 = frameToX(selEnd);
      canvas.drawLine(sx0, waveTop, sx0, waveBottom, pSelBorder);
      canvas.drawLine(sx1, waveTop, sx1, waveBottom, pSelBorder);
      // ручки
      drawHandle(canvas, sx0, waveTop, waveBottom);
      drawHandle(canvas, sx1, waveTop, waveBottom);
    }

    // маркеры
    if (markers != null) {
      for (int m : markers) {
        float x = frameToX(m);
        if (x < -markerFlagW || x > w + markerFlagW)
          continue;
        // линия
        canvas.drawLine(x, waveTop, x, waveBottom, pMarker);
        // ярлычок сверху — флажок вправо от линии
        float topY = waveTop + 2;
        Path flag = new Path();
        flag.moveTo(x, topY);
        flag.lineTo(x + markerFlagW, topY);
        flag.lineTo(x + markerFlagW, topY + markerFlagH / 2f);
        flag.lineTo(x, topY + markerFlagH);
        flag.close();
        canvas.drawPath(flag, pMarkerFill);
        // ярлычок снизу — флажок вправо
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

    // playhead
    // Красная линия (старт/курсор)
    float px = frameToX(playheadFrame);
    if (px >= 0 && px <= w) {
      canvas.drawLine(px, waveTop, px, waveBottom, pPlayhead);
      canvas.drawRect(px - 5, waveTop, px + 5, waveTop + 8, pPlayhead); // треугольник
    }

    // Зелёная линия (текущее воспроизведение)
    float activePx = frameToX(activePlayFrame);
    if (activePx >= 0 && activePx <= w && activePlayFrame != playheadFrame) {
      Paint pActive = new Paint();
      pActive.setColor(0xFF4CAF50); // Зелёный цвет
      pActive.setStrokeWidth(3f * getResources().getDisplayMetrics().density);
      canvas.drawLine(activePx, waveTop, activePx, waveBottom, pActive);
    }

    // центральная линия
    canvas.drawLine(0, centerY, w, centerY, pCenter);
  }

  private void drawRuler(Canvas canvas, int w, int rulerH) {
    if (sampleRate <= 0)
      return;
    // выбираем шаг: 10мс, 50мс, 100мс, 500мс, 1с, 5с...
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

  private void drawGrid(Canvas canvas, int w, int top, int bottom, int centerY) {
    // вертикальные линии по тем же шагам, что и линейка
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
      float x = (float) (((t * sampleRate) - viewStartFrame) / viewFramesPerPixel);
      if (x < 0 || x > w)
        continue;
      canvas.drawLine(x, top, x, bottom, pGrid);
    }
  }

  private void drawHandle(Canvas canvas, float x, int top, int bottom) {
    // кружки сверху и снизу
    float cyTop = top + handleHalfPx;
    float cyBot = bottom - handleHalfPx;
    canvas.drawCircle(x, cyTop, handleHalfPx, pSelBorder);
    canvas.drawCircle(x, cyBot, handleHalfPx, pSelBorder);
    // вертикальная линия между ними
    canvas.drawLine(x, cyTop, x, cyBot, pSelBorder);
  }

  /** Программный зум вокруг центра вьюхи. factor > 1 — приблизить, < 1 — отдалить. */
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

  /** Обновить маркеры без сброса зума/скролла. */
  public void updateMarkers(java.util.List<Integer> newMarkers) {
    this.markers = newMarkers;
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

}
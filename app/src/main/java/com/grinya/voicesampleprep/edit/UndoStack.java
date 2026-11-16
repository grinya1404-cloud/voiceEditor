package com.grinya.voicesampleprep.edit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * История состояний PCM. Хранит снапшоты float[].
 * Ограничение по суммарной памяти (по умолчанию 64 МБ — с запасом для 1-мин стерео).
 */
public class UndoStack {

    public static class Snapshot {
        public final float[] pcm;
        public final List<Integer> markers;
        public final List<int[]> exportIntervals;

        public Snapshot(float[] pcm, List<Integer> markers, List<int[]> exportIntervals) {
            this.pcm = pcm;
            this.markers = new ArrayList<>(markers);
            this.exportIntervals = new ArrayList<>();
            if (exportIntervals != null) {
                for (int[] i : exportIntervals) {
                    this.exportIntervals.add(new int[]{i[0], i[1]});
                }
            }
        }
    }

    private final ArrayDeque<Snapshot> undo = new ArrayDeque<>();
    private final ArrayDeque<Snapshot> redo = new ArrayDeque<>();
    private final long maxBytes;
    private long currentBytes = 0;

    public UndoStack(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public UndoStack() {
        this(64L * 1024 * 1024); // 64 МБ
    }

    /** Сохранить состояние ПЕРЕД изменением. */
    public void push(float[] pcm, List<Integer> markers, List<int[]> exportIntervals) {
        Snapshot s = new Snapshot(pcm, markers, exportIntervals);
        undo.push(s);
        currentBytes += (long) pcm.length * 4;
        redo.clear();
        // выбрасываем старейшие, если превысили лимит (оставляем хотя бы 1)
        while (currentBytes > maxBytes && undo.size() > 1) {
            Snapshot old = undo.removeLast();
            currentBytes -= (long) old.pcm.length * 4;
        }
    }

    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }

    public long getCurrentBytes() { return currentBytes; }
    public long getMaxBytes() { return maxBytes; }

    /** Вернуть предыдущее состояние и положить текущее в redo. */
    public Snapshot undo(float[] pcm, List<Integer> markers, List<int[]> exportIntervals) {
        if (undo.isEmpty()) return null;
        Snapshot prev = undo.pop();
        currentBytes -= (long) prev.pcm.length * 4;
        Snapshot cur = new Snapshot(pcm, markers, exportIntervals);
        redo.push(cur);
        return prev;
    }

    public Snapshot redo(float[] pcm, List<Integer> markers, List<int[]> exportIntervals) {
        if (redo.isEmpty()) return null;
        Snapshot next = redo.pop();
        Snapshot cur = new Snapshot(pcm, markers, exportIntervals);
        undo.push(cur);
        currentBytes += (long) cur.pcm.length * 4;
        return next;
    }

    public void clear() {
        undo.clear();
        redo.clear();
        currentBytes = 0;
    }
}
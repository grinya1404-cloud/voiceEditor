package com.grinya.voicesampleprep.edit;

import java.util.ArrayDeque;

/**
 * История состояний PCM. Хранит снапшоты float[].
 * Ограничение по суммарной памяти (по умолчанию 128 МБ — с запасом для 60-сек стерео).
 */
public class UndoStack {

    public static class Snapshot {
        public final float[] pcm;
        public final java.util.List<Integer> markers;

        public Snapshot(float[] pcm, java.util.List<Integer> markers) {
            this.pcm = pcm;
            this.markers = new java.util.ArrayList<>(markers);
        }
    }

    private final ArrayDeque<Snapshot> undo = new ArrayDeque<>();
    private final ArrayDeque<Snapshot> redo = new ArrayDeque<>();
    private long maxBytes;
    private long currentBytes = 0;

    public UndoStack(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public UndoStack() {
        this(128L * 1024 * 1024);
    }

    /** Сохранить состояние ПЕРЕД изменением. */
    public void push(float[] pcm, java.util.List<Integer> markers) {
        Snapshot s = new Snapshot(pcm, markers);
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

    /** Вернуть предыдущее состояние и положить текущее в redo. */
    public Snapshot undo(float[] currentPcm, java.util.List<Integer> currentMarkers) {
        if (undo.isEmpty()) return null;
        Snapshot prev = undo.pop();
        currentBytes -= (long) prev.pcm.length * 4;
        Snapshot cur = new Snapshot(currentPcm, currentMarkers);
        redo.push(cur);
        return prev;
    }

    public Snapshot redo(float[] currentPcm, java.util.List<Integer> currentMarkers) {
        if (redo.isEmpty()) return null;
        Snapshot next = redo.pop();
        Snapshot cur = new Snapshot(currentPcm, currentMarkers);
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
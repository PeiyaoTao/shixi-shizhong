package com.foldstandby.clock;

import java.util.ArrayList;

/** Local editor history. A physical drag is one transaction, while every value can still autosave. */
final class EditHistory {
    private final ArrayList<String> undo = new ArrayList<>(), redo = new ArrayList<>();
    private int depth;
    private String before, after;
    void begin() { depth++; }
    void record(String oldState, String newState) {
        if (oldState == null || oldState.equals(newState)) return;
        if (depth > 0) { if (before == null) before = oldState; after = newState; redo.clear(); }
        else push(oldState, newState);
    }
    void end() { if (depth > 0 && --depth == 0) flush(); }
    void finish() { depth = 0; flush(); }
    private void flush() { if (before != null && !before.equals(after)) push(before, after); before = after = null; }
    private void push(String oldState, String newState) {
        undo.add(oldState); undo.add(newState); redo.clear();
        int size = 0; for (String s : undo) size += s.length();
        while (undo.size() > 80 || size > 120000 && undo.size() > 2) { size -= undo.remove(0).length(); size -= undo.remove(0).length(); }
    }
    boolean canUndo() { return before != null && !before.equals(after) || !undo.isEmpty(); }
    boolean canRedo() { return !redo.isEmpty(); }
    String undo() {
        finish(); if (undo.isEmpty()) return null;
        String next = undo.remove(undo.size()-1), previous = undo.remove(undo.size()-1);
        redo.add(previous); redo.add(next); return previous;
    }
    String redo() {
        finish(); if (redo.isEmpty()) return null;
        String next = redo.remove(redo.size()-1), previous = redo.remove(redo.size()-1);
        undo.add(previous); undo.add(next); return next;
    }
    ArrayList<String> undoStates() { finish(); return new ArrayList<>(undo); }
    ArrayList<String> redoStates() { return new ArrayList<>(redo); }
    void restore(ArrayList<String> a, ArrayList<String> b) {
        undo.clear(); redo.clear(); before = after = null; depth = 0;
        if (a != null && a.size()%2 == 0) undo.addAll(a);
        if (b != null && b.size()%2 == 0) redo.addAll(b);
    }
}

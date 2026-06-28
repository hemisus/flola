package com.hemisus.flola.controller;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 캔버스(또는 서브그래프) 한 개에 대한 undo/redo 스택 관리자.
 *
 * <p>{@link #record(GraphCommand)}는 <b>이미 실행된</b> Command를 스택에 쌓는다
 * (execute를 호출하지 않는다). 노드 이동처럼 작업이 먼저 일어난 뒤 Command가
 * 만들어지는 경우와, Add/Remove/Connect처럼 호출부가 기존 경로로 먼저 실행하는
 * 경우 모두 이 모델에 자연스럽게 맞는다.</p>
 *
 * <p>MainController(메인 캔버스)와 CustomOperationEditorStage(서브그래프)가
 * 각자 하나씩 보유해 동일한 Command 클래스를 재사용한다.</p>
 */
public class GraphUndoManager {

    private final Deque<GraphCommand> undoStack = new ArrayDeque<>();
    private final Deque<GraphCommand> redoStack = new ArrayDeque<>();

    /** 마지막 저장 시점의 undo 스택 top (저장 이후 변경 여부 판정용). */
    private GraphCommand cleanMarker = null;

    /** 이미 실행된 Command를 기록한다. redo 스택은 비운다. */
    public void record(GraphCommand cmd) {
        if (cmd == null) return;
        undoStack.push(cmd);
        redoStack.clear();
    }

    public void undo() {
        if (undoStack.isEmpty()) return;
        GraphCommand cmd = undoStack.pop();
        cmd.undo();
        redoStack.push(cmd);
    }

    public void redo() {
        if (redoStack.isEmpty()) return;
        GraphCommand cmd = redoStack.pop();
        cmd.redo();
        undoStack.push(cmd);
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }

    /** 다음에 undo될 작업의 설명. 스택이 비어 있으면 null. */
    public String peekUndoDescription() {
        return undoStack.isEmpty() ? null : undoStack.peek().describe();
    }

    /** 다음에 redo될 작업의 설명. 스택이 비어 있으면 null. */
    public String peekRedoDescription() {
        return redoStack.isEmpty() ? null : redoStack.peek().describe();
    }

    /** 현재 상태를 '저장됨(깨끗함)'으로 표시한다 (저장 직후 호출). */
    public void markClean() { cleanMarker = undoStack.peek(); }

    /** 마지막 저장(markClean) 이후 변경이 있었는지. undo로 저장 시점까지 되돌리면 다시 false. */
    public boolean isDirty() { return undoStack.peek() != cleanMarker; }

    public void clear() {
        undoStack.clear();
        redoStack.clear();
        cleanMarker = null;
    }
}
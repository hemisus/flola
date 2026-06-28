package com.hemisus.flola.controller;

/**
 * 캔버스(또는 서브그래프) 편집의 되돌리기 가능한 단위 작업.
 *
 * <p>각 Command는 <b>이미 실행된 작업</b>으로 {@link GraphUndoManager}에 기록된다.
 * 따라서 {@code execute()} 없이 {@code undo()}/{@code redo()}만 가진다 —
 * 최초 실행은 호출부(예: MainController)가 기존 경로로 수행하고, Command는
 * 이후의 되돌리기/다시하기만 책임진다.</p>
 *
 * <p>구현체는 부분 로직을 재구현하지 않고 {@link CanvasContext}가 위임하는
 * 기존 편집 메서드를 그대로 호출한다 — 그래야 cascade·인스펙터·연결 갱신 등
 * 관련 이벤트가 정상 동작과 동일하게 트리거된다.</p>
 */
public interface GraphCommand {
    /** 작업을 되돌린다 (직전 상태로 복원). */
    void undo();

    /** 되돌린 작업을 다시 적용한다. */
    void redo();

    /**
     * 이 작업에 대한 사람이 읽을 수 있는 설명 (예: "Move NodeA", "Edit tensorX").
     * Edit 메뉴의 "Undo: …" / "Redo: …" 라벨에 쓰인다.
     * 기본값은 일반 문구이며, 메인 캔버스에 기록되는 Command는 구체적으로 오버라이드한다.
     */
    default String describe() { return "Edit"; }
}
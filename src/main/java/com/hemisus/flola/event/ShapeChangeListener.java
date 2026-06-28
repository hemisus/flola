package com.hemisus.flola.event;

public interface ShapeChangeListener {

    /**
     * shape 변경을 허용할지 결정한다.
     *
     * @param nodeId   변경 대상 노드 이름 (다이얼로그 표시용)
     * @param oldShape 변경 전 shape 배열 (예: [3, 4])
     * @param newShape 변경 후 shape 배열 (예: [1, 3, 4])
     * @return {@code true}이면 변경 진행, {@code false}이면 취소
     */
    boolean onShapeChange(String nodeId, int[] oldShape, int[] newShape);
}
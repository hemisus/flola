package com.hemisus.flola.controller;

import com.hemisus.flola.ui.CanvasPane;
import com.hemisus.flola.ui.NodeView;
import javafx.event.EventHandler;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import java.util.function.Consumer;

/**
 * 사이드바 → 캔버스 노드 드래그 트래킹.
 * scene 레벨 마우스 필터로 윈도우 어디서든 release 감지.
 */
public class NodeDragController {

    private final CanvasPane canvas;

    private boolean   dragging = false;
    private NodeView  preview;
    private Consumer<NodeView> onDrop;

    private EventHandler<MouseEvent> moveHandler;
    private EventHandler<MouseEvent> releaseHandler;

    public NodeDragController(CanvasPane canvas) { this.canvas = canvas; }

    public boolean isDragging() { return dragging; }

    public void setOnDrop(Consumer<NodeView> onDrop) { this.onDrop = onDrop; }

    /** 드래그 시작. preview는 canvas overlay에 추가되어 마우스를 따라다님. */
    public void startDrag(NodeView previewNode) {
        if (dragging) return;
        Scene scene = canvas.getScene();
        if (scene == null) return;

        this.preview  = previewNode;
        this.dragging = true;
        preview.setOpacity(0.6);
        preview.setMouseTransparent(true);
        canvas.getOverlay().getChildren().add(preview);

        // 현재 마우스 위치로 초기 위치 설정
        moveHandler = e -> updatePreviewPosition(e.getSceneX(), e.getSceneY());
        releaseHandler = e -> handleRelease(e);

        scene.addEventFilter(MouseEvent.MOUSE_MOVED,    moveHandler);
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED,  moveHandler);
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, releaseHandler);
    }

    private void updatePreviewPosition(double sceneX, double sceneY) {
        Point2D local = canvas.sceneToLocal(sceneX, sceneY);
        preview.setLayoutX(local.getX() - NodeView.WIDTH  / 2.0);
        preview.setLayoutY(local.getY() - NodeView.HEIGHT / 2.0);
    }

    private void handleRelease(MouseEvent e) {
        Scene scene = canvas.getScene();
        canvas.getOverlay().getChildren().remove(preview);
        scene.removeEventFilter(MouseEvent.MOUSE_MOVED,    moveHandler);
        scene.removeEventFilter(MouseEvent.MOUSE_DRAGGED,  moveHandler);
        scene.removeEventFilter(MouseEvent.MOUSE_RELEASED, releaseHandler);

        // 우클릭으로 release하면 취소
        if (e.getButton() == MouseButton.SECONDARY) { cleanup(); return; }

        // 캔버스 영역 내인지 체크
        Point2D local = canvas.sceneToLocal(e.getSceneX(), e.getSceneY());
        boolean insideCanvas =
            local.getX() >= 0 && local.getY() >= 0 &&
            local.getX() <= canvas.getWidth() && local.getY() <= canvas.getHeight();

        if (!insideCanvas) { cleanup(); return; }

        // world 좌표로 변환 후 캔버스에 안착
        Point2D world = canvas.sceneToWorld(e.getSceneX(), e.getSceneY());
        preview.setOpacity(1.0);
        preview.setMouseTransparent(false);
        canvas.addNode(preview,
            world.getX() - NodeView.WIDTH  / 2.0,
            world.getY() - NodeView.HEIGHT / 2.0);

        if (onDrop != null) onDrop.accept(preview);
        cleanup();
    }

    private void cleanup() {
        dragging = false;
        preview = null;
        moveHandler = null;
        releaseHandler = null;
    }
}
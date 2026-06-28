package com.hemisus.flola.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;

/**
 * Zoom / Pan을 지원하는 캔버스 패널.
 *
 * <ul>
 *   <li>Zoom: Ctrl + Scroll Wheel (마우스 위치 기준으로 줌)</li>
 *   <li>Pan:  Middle Mouse Drag</li>
 * </ul>
 *
 * <p>모든 컨텐츠(노드·연결선·그리드)는 {@code world} 그룹 안에 들어가며,
 * Scale/Translate 변환이 이 그룹에만 적용된다. 외부 코드는
 * {@link #getContentLayer()} 로 노드를 추가하면 된다.</p>
 */
public class CanvasPane extends Pane {

    private static final double MIN_SCALE   = 0.2;
    private static final double MAX_SCALE   = 4.0;
    private static final double ZOOM_FACTOR = 1.1;
    private static final int    GRID_SIZE   = 20;
    private static final int    GRID_EXTENT = 2500;   // ±2500 px

    /** Zoom/Pan이 적용되는 루트 그룹 */
    private final Group world        = new Group();
    private final Group gridLayer    = new Group();
    private final Group contentLayer = new Group();
    private final Group overlay = new Group();      // ← 추가 (드래그 미리보기용, 변환 미적용)
    private final List<NodeView> nodes = new ArrayList<>();   // ← 추가
    
    private final Scale     scaleT     = new Scale(1, 1);
    private final Translate translateT = new Translate(0, 0);

    private double lastDragSceneX;
    private double lastDragSceneY;
    private boolean panning = false;
    private final ConnectionLayer connectionLayer = new ConnectionLayer();
    private PortView dragHoverPort;
    private boolean connecting = false;
    private Port    connectFrom = null;
    private javafx.event.EventHandler<javafx.scene.input.MouseEvent> connMoveHandler, connReleaseHandler;
    private java.util.function.BiConsumer<Port, Port> onConnect;

    // ── 다중 선택 / 러버밴드 ──────────────────────────────
    private final Set<NodeView> selectedNodes = new LinkedHashSet<>();
    private Rectangle rubberBand;
    private double  rubberStartX, rubberStartY;   // CanvasPane-local
    private boolean rubberBanding = false;
    
    public CanvasPane() {
        getStyleClass().add("canvas-pane");

        world.getTransforms().addAll(translateT, scaleT);
        world.getChildren().addAll(gridLayer, connectionLayer, contentLayer);
        getChildren().addAll(world, overlay);

        // 캔버스 밖으로 컨텐츠가 새지 않도록 클리핑
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);

        buildGrid();
        installZoomHandler();
        installPanHandler();
        installSelectionHandler();
    }

    // ── Grid (world space) ────────────────────────────────────

    private void buildGrid() {
        for (int x = -GRID_EXTENT; x <= GRID_EXTENT; x += GRID_SIZE) {
            Line line = new Line(x, -GRID_EXTENT, x, GRID_EXTENT);
            line.getStyleClass().add("canvas-grid-line");
            gridLayer.getChildren().add(line);
        }
        for (int y = -GRID_EXTENT; y <= GRID_EXTENT; y += GRID_SIZE) {
            Line line = new Line(-GRID_EXTENT, y, GRID_EXTENT, y);
            line.getStyleClass().add("canvas-grid-line");
            gridLayer.getChildren().add(line);
        }
    }

    // ── Zoom (Ctrl + Scroll) ──────────────────────────────────

    private void installZoomHandler() {
        addEventFilter(ScrollEvent.SCROLL, e -> {
            if (!e.isControlDown()) return;
            e.consume();

            double oldScale = scaleT.getX();
            double factor   = e.getDeltaY() > 0 ? ZOOM_FACTOR : 1 / ZOOM_FACTOR;
            double newScale = clamp(oldScale * factor, MIN_SCALE, MAX_SCALE);
            if (newScale == oldScale) return;

            // 마우스 위치가 줌 전후 동일한 world 좌표를 가리키도록 translate 보정
            double mouseX = e.getX();
            double mouseY = e.getY();
            double worldX = (mouseX - translateT.getX()) / oldScale;
            double worldY = (mouseY - translateT.getY()) / oldScale;

            scaleT.setX(newScale);
            scaleT.setY(newScale);
            translateT.setX(mouseX - worldX * newScale);
            translateT.setY(mouseY - worldY * newScale);
        });
    }

    // ── Pan (Middle Mouse Drag) ───────────────────────────────

    private void installPanHandler() {
        addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() != MouseButton.MIDDLE) return;
            panning = true;
            lastDragSceneX = e.getSceneX();
            lastDragSceneY = e.getSceneY();
            setCursor(Cursor.MOVE);
            e.consume();
        });

        addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (!panning) return;
            double dx = e.getSceneX() - lastDragSceneX;
            double dy = e.getSceneY() - lastDragSceneY;
            translateT.setX(translateT.getX() + dx);
            translateT.setY(translateT.getY() + dy);
            lastDragSceneX = e.getSceneX();
            lastDragSceneY = e.getSceneY();
            e.consume();
        });

        addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            if (e.getButton() != MouseButton.MIDDLE) return;
            panning = false;
            setCursor(Cursor.DEFAULT);
            e.consume();
        });
    }

    // ── 다중 선택 (러버밴드) ──────────────────────────────

    private void installSelectionHandler() {
        addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (connecting) return;                                   // 포트 드래그 중
            if (isOverNode(e.getSceneX(), e.getSceneY())) return;     // 노드가 직접 처리
            // 빈 캔버스 → 러버밴드 시작
            if (!e.isShortcutDown()) clearSelection();
            startRubberBand(e.getX(), e.getY());
            e.consume();
        });

        addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (!rubberBanding) return;
            updateRubberBand(e.getX(), e.getY());
            e.consume();
        });

        addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            if (!rubberBanding) return;
            finishRubberBand(e.isShortcutDown());
            e.consume();
        });
    }

    private void startRubberBand(double localX, double localY) {
        rubberBanding = true;
        rubberStartX  = localX;
        rubberStartY  = localY;
        rubberBand = new Rectangle(localX, localY, 0, 0);
        rubberBand.getStyleClass().add("selection-rect");   // 색·테두리는 main.css의 .selection-rect가 담당
        rubberBand.setMouseTransparent(true);
        overlay.getChildren().add(rubberBand);
    }

    private void updateRubberBand(double localX, double localY) {
        if (rubberBand == null) return;
        rubberBand.setX(Math.min(localX, rubberStartX));
        rubberBand.setY(Math.min(localY, rubberStartY));
        rubberBand.setWidth (Math.abs(localX - rubberStartX));
        rubberBand.setHeight(Math.abs(localY - rubberStartY));
    }

    private void finishRubberBand(boolean additive) {
        rubberBanding = false;
        if (rubberBand == null) return;
        Bounds selScene = localToScene(rubberBand.getBoundsInParent());
        if (!additive) clearSelection();
        for (NodeView n : nodes) {
            Bounds nb = n.localToScene(n.getBoundsInLocal());
            if (selScene.intersects(nb)) addToSelection(n);
        }
        overlay.getChildren().remove(rubberBand);
        rubberBand = null;
    }

    private boolean isOverNode(double sceneX, double sceneY) {
        for (NodeView n : nodes)
            if (n.localToScene(n.getBoundsInLocal()).contains(sceneX, sceneY)) return true;
        return false;
    }

    // ── 선택 API ──────────────────────────────────────────

    public Set<NodeView> getSelectedNodes() { return Collections.unmodifiableSet(selectedNodes); }
    public boolean isSelected(NodeView n)   { return selectedNodes.contains(n); }

    public void addToSelection(NodeView n) {
        if (selectedNodes.add(n)) n.setSelected(true);
    }

    public void selectOnly(NodeView n) {
        clearSelection();
        addToSelection(n);
    }

    public void toggleSelection(NodeView n) {
        if (selectedNodes.remove(n)) n.setSelected(false);
        else                         addToSelection(n);
    }

    public void clearSelection() {
        for (NodeView n : selectedNodes) n.setSelected(false);
        selectedNodes.clear();
    }

    public void selectAll() {
        clearSelection();
        for (NodeView n : nodes) addToSelection(n);
    }

    /** 드래그된 노드를 포함한 선택 전체를 (dx, dy) world 좌표만큼 이동. */
    public void moveSelectionBy(NodeView dragged, double dx, double dy) {
        if (!selectedNodes.contains(dragged)) selectOnly(dragged);
        for (NodeView n : selectedNodes) n.moveBy(dx, dy);
    }

    /** 현재 뷰포트 중앙의 world 좌표 (붙여넣기 기준점) */
    public Point2D getViewportCenterInWorld() {
        Point2D centerScene = localToScene(getWidth() / 2.0, getHeight() / 2.0);
        return sceneToWorld(centerScene.getX(), centerScene.getY());
    }

    // ── Public API ────────────────────────────────────────────

    /** 노드·연결선 등 모든 사용자 컨텐츠를 추가할 레이어 */
    public Group getContentLayer() { return contentLayer; }

    /** 현재 줌 배율 (1.0 = 100%) */
    public double getScale() { return scaleT.getX(); }

    /** 뷰를 초기 상태(스케일 1, 원점 정렬)로 리셋 */
    public void resetView() {
        scaleT.setX(1);
        scaleT.setY(1);
        translateT.setX(0);
        translateT.setY(0);
    }
    
    public ConnectionLayer getConnectionLayer() { return connectionLayer; }
    public void setOnConnect(java.util.function.BiConsumer<Port, Port> cb) { this.onConnect = cb; }

    public void startConnection(Port from) {
        if (connecting || from.getType() != Port.Type.OUTPUT) return;
        javafx.scene.Scene scene = getScene();
        if (scene == null) return;
        connecting = true;
        connectFrom = from;

        connMoveHandler = e -> {
            Point2D w = sceneToWorld(e.getSceneX(), e.getSceneY());
            connectionLayer.setTempConnection(from, w);

            // 커서 아래 입력 포트 강조 (같은 노드는 제외)
            PortView pv = findInputPortViewAt(e.getSceneX(), e.getSceneY());
            if (pv != null && pv.getPort().getOwner() == from.getOwner()) pv = null;
            if (pv != dragHoverPort) {
                if (dragHoverPort != null) dragHoverPort.setHovered(false);
                dragHoverPort = pv;
                if (dragHoverPort != null) dragHoverPort.setHovered(true);
            }
        };
        connReleaseHandler = e -> finishConnection(e);
        scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_DRAGGED,  connMoveHandler);
        scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, connReleaseHandler);
    }

    // ── 노드 관리 ─────────────────────────────────────

    public void addNode(NodeView node, double worldX, double worldY) {
        node.setLayoutX(worldX);
        node.setLayoutY(worldY);
        contentLayer.getChildren().add(node);
        nodes.add(node);
    }

    public void removeNode(NodeView node) {
        contentLayer.getChildren().remove(node);
        nodes.remove(node);
        selectedNodes.remove(node);
        node.setSelected(false);   // 시각 하이라이트도 해제 (집합과 동기화)
    }

    public List<NodeView> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    /** 드래그 미리보기 등 변환되지 않는 오버레이 레이어 */
    public Group getOverlay() { return overlay; }
    private void finishConnection(javafx.scene.input.MouseEvent e) {
        javafx.scene.Scene scene = getScene();
        scene.removeEventFilter(javafx.scene.input.MouseEvent.MOUSE_DRAGGED,  connMoveHandler);
        scene.removeEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, connReleaseHandler);
        connectionLayer.clearTempConnection();
        if (dragHoverPort != null) { dragHoverPort.setHovered(false); dragHoverPort = null; }
        Port target = findInputPortAt(e.getSceneX(), e.getSceneY());
        if (target != null && target.getOwner() != connectFrom.getOwner() && onConnect != null) {
            onConnect.accept(connectFrom, target);
        }
        connecting = false;
        connectFrom = null;
    }

    private PortView findInputPortViewAt(double sceneX, double sceneY) {
        for (NodeView node : nodes) {
            for (PortView pv : node.getInputPorts()) {
                javafx.geometry.Bounds b = pv.localToScene(pv.getBoundsInLocal());
                if (b.contains(sceneX, sceneY)) return pv;
            }
        }
        return null;
    }

    private Port findInputPortAt(double sceneX, double sceneY) {
        PortView pv = findInputPortViewAt(sceneX, sceneY);
        return (pv != null) ? pv.getPort() : null;
    }

    // ── 좌표 변환 ─────────────────────────────────────

    /** scene 좌표 → world 좌표 */
    public Point2D sceneToWorld(double sceneX, double sceneY) {
        Point2D local = sceneToLocal(sceneX, sceneY);
        return new Point2D(
            (local.getX() - translateT.getX()) / scaleT.getX(),
            (local.getY() - translateT.getY()) / scaleT.getY()
        );
    }
    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(v, max));
    }
}
package com.hemisus.flola.ui;

import javafx.geometry.Point2D;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;

/**
 * 포트 시각화. 단일 포트는 원, variadic 포트(index=-1)는 둥근 직사각형.
 * Phase 3에서는 hover만 — 실제 연결 시작은 Phase 4에서.
 */
public class PortView extends Pane {

    public static final int PORT_SIZE  = 12;
    public static final int VARIADIC_W = 10;
    public static final int VARIADIC_H = 36;

    private final Port      port;
    private final Color     baseColor;
    private final boolean   variadic;
    private final Circle    circle;
    private final Rectangle rect;

    private boolean hovered = false;

    public PortView(Port port, Color baseColor) {
        this.port      = port;
        this.baseColor = baseColor;
        this.variadic  = (port.getIndex() == -1);

        if (variadic) {
            setPrefSize(VARIADIC_W, VARIADIC_H);
            setMinSize (VARIADIC_W, VARIADIC_H);
            setMaxSize (VARIADIC_W, VARIADIC_H);
            rect = new Rectangle(VARIADIC_W, VARIADIC_H);
            rect.setArcWidth(8);
            rect.setArcHeight(8);
            rect.setStroke(baseColor);
            rect.setStrokeWidth(1);
            getChildren().add(rect);
            circle = null;
        } else {
            setPrefSize(PORT_SIZE, PORT_SIZE);
            setMinSize (PORT_SIZE, PORT_SIZE);
            setMaxSize (PORT_SIZE, PORT_SIZE);
            circle = new Circle(PORT_SIZE / 2.0, PORT_SIZE / 2.0, PORT_SIZE / 2.0 - 1);
            circle.setStroke(baseColor);
            circle.setStrokeWidth(1.5);
            getChildren().add(circle);
            rect = null;
        }
        updateVisual();

        setOnMouseEntered(e -> { hovered = true;  updateVisual(); });
        setOnMouseExited (e -> { hovered = false; updateVisual(); });

        setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                e.consume();
                if (port.getType() == Port.Type.OUTPUT) {
                    CanvasPane canvas = findCanvas();
                    if (canvas != null) canvas.startConnection(port);
                }
            } else if (e.getButton() == MouseButton.SECONDARY) {
                e.consume();   // 노드 드래그/선택 방지
            }
        });
        setOnMouseDragged (e -> { if (e.getButton() == MouseButton.PRIMARY) e.consume(); });
        setOnMouseReleased(e -> { if (e.getButton() == MouseButton.PRIMARY) e.consume(); });

        setOnContextMenuRequested(e -> {
            e.consume();   // ← NodeView 우클릭 메뉴로 버블링 방지 (Fix 4)
            showConnectionMenu(e.getScreenX(), e.getScreenY());
        });
    }
    private void showConnectionMenu(double screenX, double screenY) {
        CanvasPane canvas = findCanvas();
        if (canvas == null) return;
        ConnectionLayer layer = canvas.getConnectionLayer();
        NodeView node = port.getOwner();
        java.util.List<Connection> conns = (port.getIndex() == -1)
            ? layer.getConnectionsForOwner(node, port.getType())
            : layer.getConnectionsFor(port);
        layer.showRemoveMenu(conns, this, screenX, screenY);
    }
    private CanvasPane findCanvas() {
        javafx.scene.Node n = getParent();
        while (n != null) {
            if (n instanceof CanvasPane cp) return cp;
            n = n.getParent();
        }
        return null;
    }

    private void showConnectionMenu(javafx.scene.input.MouseEvent e) {
        CanvasPane canvas = findCanvas();
        if (canvas == null) return;
        ConnectionLayer layer = canvas.getConnectionLayer();
        NodeView node = port.getOwner();

        java.util.List<Connection> conns = (port.getIndex() == -1)
            ? layer.getConnectionsForOwner(node, port.getType())
            : layer.getConnectionsFor(port);
        if (conns.isEmpty()) return;

        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        if (conns.size() == 1) {
            javafx.scene.control.MenuItem item = new javafx.scene.control.MenuItem(
                "Remove: " + ConnectionLayer.buildConnectionLabel(conns.get(0)));
            item.setOnAction(a -> layer.requestRemove(conns.get(0)));
            menu.getItems().add(item);
        } else {
            javafx.scene.control.MenuItem header = new javafx.scene.control.MenuItem("Remove Connection:");
            header.setDisable(true);
            menu.getItems().add(header);
            for (Connection c : conns) {
                javafx.scene.control.MenuItem item = new javafx.scene.control.MenuItem(
                    "  " + ConnectionLayer.buildConnectionLabel(c));
                item.setOnAction(a -> layer.requestRemove(c));
                menu.getItems().add(item);
            }
        }
        menu.show(this, e.getScreenX(), e.getScreenY());
    }
    private void updateVisual() {
        if (variadic) {
            rect.setFill(hovered ? baseColor : baseColor.interpolate(Color.BLACK, 0.35));
        } else {
            circle.setFill(hovered
                ? baseColor.interpolate(Color.WHITE, 0.25)
                : baseColor.interpolate(Color.BLACK, 0.55));
            circle.setStroke(hovered ? baseColor.brighter() : baseColor);
        }
    }
    /** 연결 드래그 중 캔버스가 타깃 포트를 강조할 때 사용 */
    public void setHovered(boolean h) { this.hovered = h; updateVisual(); }
    public Port getPort() { return port; }

    /** 포트 중심점을 부모 NodeView의 로컬 좌표 기준으로 반환 (= world 좌표). */
    public Point2D getCenterInWorld() {
        NodeView node = port.getOwner();
        return new Point2D(
            node.getLayoutX() + getLayoutX() + getPrefWidth()  / 2,
            node.getLayoutY() + getLayoutY() + getPrefHeight() / 2);
    }
}
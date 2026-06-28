package com.hemisus.flola.ui;

import com.hemisus.flola.viewmodel.NodeViewModel;
import com.hemisus.flola.viewmodel.OperationViewModel;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.paint.Color;
import javafx.scene.shape.CubicCurve;

import java.util.*;
import java.util.function.Consumer;

/** 연결 곡선 렌더링 레이어. world 안에 위치(노드 뒤). */
public class ConnectionLayer extends Group {
	private ContextMenu activeMenu;   // 현재 떠있는 메뉴 (중복 방지)
    private final List<Connection> connections = new ArrayList<>();
    private final Group connGroup = new Group();   // 영구 곡선 + 배지
    private CubicCurve tempCurve;                   // 드래그 중 임시 곡선

    private Consumer<Connection> onConnectionRemoved;

    // countKey용 그룹 키
    private record PortPair(Port from, Port to) {}
    private record VariadicKey(Port from, NodeView toNode) {}
    private record VariadicSourceKey(NodeView fromNode, Port to) {}
    private record NodePair(NodeView from, NodeView to) {}

    public ConnectionLayer() { getChildren().add(connGroup); }

    public void setOnConnectionRemoved(Consumer<Connection> cb) { onConnectionRemoved = cb; }
    public void requestRemove(Connection c) { if (onConnectionRemoved != null) onConnectionRemoved.accept(c); }

    // ── add / remove / query ──────────────────────────────

    public void replaceConnectionsOf(NodeView node, List<Connection> newConns) {
        connections.removeIf(c -> c.getFrom().getOwner() == node || c.getTo().getOwner() == node);
        connections.addAll(newConns);
        redraw();
    }

    public void removeConnectionsOf(NodeView node) {
        connections.removeIf(c -> c.getFrom().getOwner() == node || c.getTo().getOwner() == node);
        redraw();
    }

    public List<Connection> getConnectionsFor(Port port) {
        List<Connection> r = new ArrayList<>();
        for (Connection c : connections)
            if (c.getFrom().equals(port) || c.getTo().equals(port)) r.add(c);
        return r;
    }

    public List<Connection> getConnectionsForOwner(NodeView owner, Port.Type type) {
        List<Connection> r = new ArrayList<>();
        for (Connection c : connections) {
            Port p = (type == Port.Type.INPUT) ? c.getTo() : c.getFrom();
            if (p.getOwner() == owner) r.add(c);
        }
        return r;
    }

    // ── temp connection ───────────────────────────────────

    public void setTempConnection(Port from, Point2D to) {
        Point2D p1 = portCenter(from);
        if (p1 == null) return;
        if (tempCurve == null) {
            tempCurve = makeCurve(p1, to);
            tempCurve.setStroke(Color.rgb(100, 100, 100, 0.6));
            tempCurve.setStrokeWidth(2);
            tempCurve.setFill(null);
            tempCurve.setMouseTransparent(true);
            getChildren().add(tempCurve);
        } else {
            updateCurve(tempCurve, p1, to);
        }
    }

    public void clearTempConnection() {
        if (tempCurve != null) { getChildren().remove(tempCurve); tempCurve = null; }
    }

    // ── redraw ─────────────────────────────────────────────

    public void redraw() {
        connGroup.getChildren().clear();

        Map<Object, Integer> countMap = new HashMap<>();
        for (Connection c : connections) countMap.merge(countKey(c), 1, Integer::sum);

        Set<Object>    badgedKeys = new HashSet<>();
        List<double[]> badges     = new ArrayList<>();   // {cx, cy, count}

        // 1단계: 곡선 (배지보다 아래)
        for (Connection c : connections) {
            Point2D p1 = portCenter(c.getFrom());
            Point2D p2 = portCenter(c.getTo());
            if (p1 == null || p2 == null) continue;

            CubicCurve hit = makeCurve(p1, p2);
            hit.setStroke(Color.TRANSPARENT);
            hit.setStrokeWidth(12);
            hit.setFill(null);
            hit.setOnContextMenuRequested(ev -> {
                ev.consume();
                showRemoveMenu(connectionsInSameGroup(c), this, ev.getScreenX(), ev.getScreenY());
            });

            CubicCurve vis = makeCurve(p1, p2);
            vis.setStroke(Color.BLACK);
            vis.setStrokeWidth(2);
            vis.setFill(null);
            vis.setMouseTransparent(true);

            connGroup.getChildren().addAll(hit, vis);

            Object key = countKey(c);
            if (countMap.getOrDefault(key, 1) > 1 && badgedKeys.add(key)) {
                badges.add(new double[]{(p1.getX()+p2.getX())/2,
                                        (p1.getY()+p2.getY())/2,
                                        countMap.get(key)});
            }
        }

        // 2단계: 배지를 곡선 위에 (Fix 2)
        for (double[] b : badges) addBadge(b[0], b[1], (int) b[2]);
    }

    // ── 배지 ───────────────────────────────────────────────

    private void addBadge(double cx, double cy, int count) {
        Label badge = new Label("\u00d7" + count);
        badge.setStyle(
            "-fx-background-color: #d22d2d;" +
            "-fx-text-fill: white;" +
            "-fx-font-size: 10px;" +
            "-fx-font-weight: bold;" +
            "-fx-padding: 1 5 1 5;" +
            "-fx-background-radius: 8;" +
            "-fx-border-color: #a01414;" +
            "-fx-border-radius: 8;");
        badge.setLayoutX(cx);
        badge.setLayoutY(cy);
        badge.setMouseTransparent(true);
        badge.layoutBoundsProperty().addListener((o, ov, nv) -> {
            badge.setTranslateX(-nv.getWidth() / 2);
            badge.setTranslateY(-nv.getHeight() / 2);
        });
        connGroup.getChildren().add(badge);
    }

    // ── 우클릭 제거 메뉴 (곡선) ────────────────────────────

    /** clicked와 같은 시각 그룹(countKey)에 속한 모든 연결 */
    private List<Connection> connectionsInSameGroup(Connection clicked) {
        Object key = countKey(clicked);
        List<Connection> group = new ArrayList<>();
        for (Connection c : connections)
            if (countKey(c).equals(key)) group.add(c);
        if (group.isEmpty()) group.add(clicked);
        return group;
    }

    /** 곡선·포트 우클릭 공통 메뉴. 기존에 떠있던 메뉴는 닫고 새로 띄움. */
    public void showRemoveMenu(List<Connection> conns, javafx.scene.Node anchor,
                                double screenX, double screenY) {
        if (activeMenu != null) { activeMenu.hide(); activeMenu = null; }
        if (conns == null || conns.isEmpty()) return;

        ContextMenu menu = new ContextMenu();
        if (conns.size() == 1) {
            MenuItem item = new MenuItem("Remove: " + buildConnectionLabel(conns.get(0)));
            item.setOnAction(e -> requestRemove(conns.get(0)));
            menu.getItems().add(item);
        } else {
            MenuItem header = new MenuItem("Remove Connection (" + conns.size() + "):");
            header.setDisable(true);
            menu.getItems().add(header);
            for (Connection c : conns) {
                MenuItem item = new MenuItem("  " + buildConnectionLabel(c));
                item.setOnAction(e -> requestRemove(c));
                menu.getItems().add(item);
            }
        }
        menu.setOnHidden(e -> { if (activeMenu == menu) activeMenu = null; });
        activeMenu = menu;
        menu.show(anchor, screenX, screenY);
    }

    public static String buildConnectionLabel(Connection c) {
        String src = c.getFrom().getOwner().getViewModel().getNodeName();
        String dst = c.getTo().getOwner().getViewModel().getNodeName();
        return src + " [" + c.getFrom().getIndex() + "]  \u2192  "
             + dst + " [in " + c.getTo().getIndex() + "]";
    }

    // ── 그룹핑 (배지 카운트) ───────────────────────────────

    private Object countKey(Connection c) {
        boolean srcVar = isVariadicOutput(c.getFrom().getOwner());
        boolean dstVar = isVariadicInput(c.getTo().getOwner());
        if (srcVar && dstVar) return new NodePair(c.getFrom().getOwner(), c.getTo().getOwner());
        if (srcVar)           return new VariadicSourceKey(c.getFrom().getOwner(), c.getTo());
        if (dstVar)           return new VariadicKey(c.getFrom(), c.getTo().getOwner());
        return new PortPair(c.getFrom(), c.getTo());
    }

    private boolean isVariadicInput(NodeView node) {
        NodeViewModel vm = node.getViewModel();
        return vm instanceof OperationViewModel ovm && (ovm.isInputVariadic() || ovm.getInputCount() >= 4);
    }

    private boolean isVariadicOutput(NodeView node) {
        NodeViewModel vm = node.getViewModel();
        return vm instanceof OperationViewModel ovm && (ovm.isOutputVariadic() || ovm.getOutputCount() >= 4);
    }

    // ── 곡선 헬퍼 ──────────────────────────────────────────

    private Point2D portCenter(Port port) {
        NodeView node = port.getOwner();
        PortView pv = (port.getType() == Port.Type.INPUT)
            ? node.getInputPortView(port.getIndex())
            : node.getOutputPortView(port.getIndex());
        return (pv != null) ? pv.getCenterInWorld() : null;
    }

    private CubicCurve makeCurve(Point2D p1, Point2D p2) {
        double off = Math.max(50, Math.abs(p2.getX() - p1.getX()) / 2);
        return new CubicCurve(
            p1.getX(), p1.getY(),
            p1.getX() + off, p1.getY(),
            p2.getX() - off, p2.getY(),
            p2.getX(), p2.getY());
    }

    private void updateCurve(CubicCurve c, Point2D p1, Point2D p2) {
        double off = Math.max(50, Math.abs(p2.getX() - p1.getX()) / 2);
        c.setStartX(p1.getX());    c.setStartY(p1.getY());
        c.setControlX1(p1.getX() + off); c.setControlY1(p1.getY());
        c.setControlX2(p2.getX() - off); c.setControlY2(p2.getY());
        c.setEndX(p2.getX());      c.setEndY(p2.getY());
    }
}
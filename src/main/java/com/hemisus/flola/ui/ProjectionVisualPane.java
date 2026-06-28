package com.hemisus.flola.ui;

import com.hemisus.flola.model.Tensor;
import com.hemisus.flola.utils.TensorOperations;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;

/** 정사영을 2D로 시각화 (Canvas). 입력 벡터가 2성분일 때만 표시. */
public class ProjectionVisualPane extends Pane {

    private static final Color GRID_COLOR   = Color.web("#EEEEEE");
    private static final Color TICK_COLOR   = Color.web("#BBBBBB");
    private static final Color AXIS_COLOR   = Color.web("#999999");
    private static final Color TARGET_COLOR = Color.web("#E8901A");
    private static final Color U_COLOR      = Color.web("#C97500");
    private static final Color V_COLOR      = Color.web("#4A7CBF");
    private static final Color PROJ_COLOR   = Color.web("#3DAA6E");
    private static final Color ORTH_COLOR   = Color.web("#CC4444");
    private static final Color LABEL_COLOR  = Color.web("#222222");
    private static final Color MSG_COLOR    = Color.web("#888888");

    private static final int    ARROW_SIZE = 9;
    private static final double STROKE_W   = 2.2;

    private final Canvas canvas = new Canvas();

    private double[]   vData, projData, targetDir;
    private double[][] uVectors;
    private boolean    isSubspace;
    private String     unavailableMsg = "입력을 연결하세요.";

    public ProjectionVisualPane() {
        getChildren().add(canvas);
        setMinSize(0, 0);
        setStyle("-fx-background-color:white;");
        widthProperty().addListener((o, a, b)  -> { canvas.setWidth(b.doubleValue());  draw(); });
        heightProperty().addListener((o, a, b) -> { canvas.setHeight(b.doubleValue()); draw(); });
    }

    public void update(Tensor v, Tensor target, Tensor proj, Tensor orth) {
        if (v == null)      { setMsg("입력 벡터가 연결되지 않았습니다 (port 0)."); return; }
        if (target == null) { setMsg("정사영 대상이 연결되지 않았습니다 (port 1)."); return; }
        if (proj == null)   { setMsg("정사영을 계산할 수 없습니다. 입력 값을 확인하세요."); return; }

        double[] vFlat    = TensorOperations.extractVectorFlat(v);
        double[] projFlat = TensorOperations.extractVectorFlat(proj);
        if (vFlat == null || vFlat.length != 2)       { setMsg("2D 시각화는 2성분 벡터만 지원합니다."); return; }
        if (projFlat == null || projFlat.length != 2) { setMsg("정사영 결과가 2D가 아닙니다."); return; }

        double[][] uVecs = extractUVectors(target);
        if (uVecs == null || uVecs.length == 0) { setMsg("정사영 대상이 2D와 호환되지 않습니다."); return; }
        double[] dir = normalize(uVecs[0]);
        if (dir == null) { setMsg("정사영 대상이 영벡터입니다."); return; }

        vData = vFlat; projData = projFlat; targetDir = dir; uVectors = uVecs;
        isSubspace = !TensorOperations.isVectorLike(target);
        unavailableMsg = null;
        draw();
    }

    private void setMsg(String msg) {
        unavailableMsg = msg;
        vData = projData = targetDir = null; uVectors = null;
        draw();
    }

    private double[][] extractUVectors(Tensor t) {
        if (TensorOperations.isVectorLike(t)) {
            double[] f = TensorOperations.extractVectorFlat(t);
            return (f != null && f.length == 2) ? new double[][]{ f } : null;
        }
        int[] shape = t.getShape();
        if (t.getRank() != 2 || shape[0] != 2) return null;
        int k = shape[1];
        double[][] cols = new double[k][2];
        for (int j = 0; j < k; j++) { cols[j][0] = t.get(0, j); cols[j][1] = t.get(1, j); }
        return cols;
    }

    private double[] normalize(double[] v) {
        double len = Math.hypot(v[0], v[1]);
        return (len < 1e-12) ? null : new double[]{ v[0]/len, v[1]/len };
    }

    private static String subscript(int n) {
        StringBuilder sb = new StringBuilder();
        for (char c : String.valueOf(n).toCharArray()) sb.append((char) ('\u2080' + (c - '0')));
        return sb.toString();
    }

    private static double sx(double cx, double mx, double scale) { return cx + mx * scale; }
    private static double sy(double cy, double my, double scale) { return cy - my * scale; }
    private static double clamp(double v, double mn, double mx)  { return Math.max(mn, Math.min(mx, v)); }

    // ── 렌더링 ─────────────────────────────────────────
    private void draw() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;
        gc.setFill(Color.WHITE); gc.fillRect(0, 0, w, h);

        if (vData == null || unavailableMsg != null) {
            gc.setFill(MSG_COLOR);
            gc.setFont(Font.font("SansSerif", FontPosture.ITALIC, 13));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText(unavailableMsg != null ? unavailableMsg : "데이터 없음", w / 2, h / 2);
            gc.setTextAlign(TextAlignment.LEFT);
            return;
        }

        double maxVal = 1.0;
        for (double x : new double[]{ vData[0], vData[1], projData[0], projData[1] }) maxVal = Math.max(maxVal, Math.abs(x));
        if (uVectors != null) for (double[] u : uVectors) for (double x : u) maxVal = Math.max(maxVal, Math.abs(x));
        double scale = Math.min(w, h) * 0.34 / maxVal;
        double cx = w / 2, cy = h / 2;

        double ox = vData[0] - projData[0], oy = vData[1] - projData[1];
        boolean hasOrth = Math.hypot(ox, oy) > 1e-10;

        drawGrid(gc, cx, cy, scale, w, h, maxVal);
        drawAxes(gc, cx, cy, w, h);
        drawTargetLine(gc, cx, cy, scale, w, h);
        if (uVectors != null) drawUVectors(gc, cx, cy, scale, w, h);
        if (hasOrth) drawDashedArrow(gc, sx(cx, projData[0], scale), sy(cy, projData[1], scale),
            sx(cx, vData[0], scale), sy(cy, vData[1], scale), ORTH_COLOR);
        drawSolidArrow(gc, cx, cy, sx(cx, projData[0], scale), sy(cy, projData[1], scale), PROJ_COLOR);
        drawSolidArrow(gc, cx, cy, sx(cx, vData[0], scale), sy(cy, vData[1], scale), V_COLOR);
        if (hasOrth) drawRightAngleMark(gc, cx, cy, scale, ox, oy);
        drawVectorLabels(gc, cx, cy, scale, w, h);
        drawLegend(gc, w, h, hasOrth);
    }

    private void drawGrid(GraphicsContext gc, double cx, double cy, double scale, double w, double h, double maxVal) {
        int gridMax = (int) Math.ceil(maxVal) + 2;
        gc.setStroke(GRID_COLOR); gc.setLineWidth(1.0); gc.setLineDashes(0);
        for (int i = -gridMax; i <= gridMax; i++) {
            double gx = cx + i * scale, gy = cy - i * scale;
            if (gx >= 0 && gx <= w) gc.strokeLine(gx, 0, gx, h);
            if (gy >= 0 && gy <= h) gc.strokeLine(0, gy, w, gy);
        }
        gc.setFill(TICK_COLOR); gc.setFont(Font.font("SansSerif", 9));
        for (int i = -gridMax; i <= gridMax; i++) {
            if (i == 0) continue;
            double gx = cx + i * scale, gy = cy - i * scale;
            String label = String.valueOf(i);
            if (gx >= 5 && gx <= w - 14) gc.fillText(label, gx - 4, cy + 12);
            if (gy >= 5 && gy <= h - 5)  gc.fillText(label, cx + 3, gy + 4);
        }
    }

    private void drawAxes(GraphicsContext gc, double cx, double cy, double w, double h) {
        gc.setStroke(AXIS_COLOR); gc.setLineWidth(1.5); gc.setLineDashes(0);
        gc.strokeLine(0, cy, w, cy); gc.strokeLine(cx, 0, cx, h);
        gc.setFill(AXIS_COLOR);
        gc.fillPolygon(new double[]{ w - 2, w - 11, w - 11 }, new double[]{ cy, cy - 4, cy + 4 }, 3);
        gc.fillPolygon(new double[]{ cx, cx - 4, cx + 4 }, new double[]{ 2, 10, 10 }, 3);
        gc.setFill(LABEL_COLOR); gc.setFont(Font.font("SansSerif", FontWeight.BOLD, 12));
        gc.fillText("x", w - 13, cy - 5); gc.fillText("y", cx + 5, 13);
    }

    private void drawTargetLine(GraphicsContext gc, double cx, double cy, double scale, double w, double h) {
        double tMax = Math.max(w, h) / scale + 4;
        double x1 = cx - targetDir[0] * scale * tMax, y1 = cy + targetDir[1] * scale * tMax;
        double x2 = cx + targetDir[0] * scale * tMax, y2 = cy - targetDir[1] * scale * tMax;
        gc.setStroke(TARGET_COLOR); gc.setLineWidth(1.5); gc.setLineDashes(9, 5);
        gc.strokeLine(x1, y1, x2, y2); gc.setLineDashes(0);
    }

    private void drawUVectors(GraphicsContext gc, double cx, double cy, double scale, double w, double h) {
        for (int i = 0; i < uVectors.length; i++) {
            double[] u = uVectors[i];
            drawSolidArrow(gc, cx, cy, sx(cx, u[0], scale), sy(cy, u[1], scale), U_COLOR);
            String name = isSubspace ? "a" + subscript(i + 1) : "u";
            gc.setFill(U_COLOR); gc.setFont(Font.font("SansSerif", FontWeight.BOLD, 11));
            double ux = clamp(sx(cx, u[0], scale) - 14, 4, w - 130);
            double uy = clamp(sy(cy, u[1], scale) + 16, 14, h - 4);
            gc.fillText(String.format("%s (%.2f, %.2f)", name, u[0], u[1]), ux, uy);
        }
    }

    private void drawRightAngleMark(GraphicsContext gc, double cx, double cy, double scale, double ox, double oy) {
        double px = sx(cx, projData[0], scale), py = sy(cy, projData[1], scale);
        double tdx = targetDir[0], tdy = -targetDir[1];
        if (projData[0] * targetDir[0] + projData[1] * targetDir[1] < 0) { tdx = -tdx; tdy = -tdy; }
        double oLen = Math.hypot(ox, oy), odx = ox / oLen, ody = -oy / oLen;
        double sq = 11;
        double bx = px + sq * tdx, by = py + sq * tdy;
        double dx = px + sq * odx, dy = py + sq * ody;
        double ex = bx + sq * odx, ey = by + sq * ody;
        gc.setStroke(Color.web("#777777")); gc.setLineWidth(1.4); gc.setLineDashes(0);
        gc.strokeLine(bx, by, ex, ey); gc.strokeLine(dx, dy, ex, ey);
    }

    private void drawSolidArrow(GraphicsContext gc, double x1, double y1, double x2, double y2, Color c) {
        gc.setStroke(c); gc.setLineWidth(STROKE_W); gc.setLineDashes(0);
        gc.strokeLine(x1, y1, x2, y2);
        drawArrowHead(gc, x1, y1, x2, y2, c);
    }
    private void drawDashedArrow(GraphicsContext gc, double x1, double y1, double x2, double y2, Color c) {
        gc.setStroke(c); gc.setLineWidth(STROKE_W); gc.setLineDashes(7, 4);
        gc.strokeLine(x1, y1, x2, y2); gc.setLineDashes(0);
        drawArrowHead(gc, x1, y1, x2, y2, c);
    }
    private void drawArrowHead(GraphicsContext gc, double x1, double y1, double x2, double y2, Color c) {
        double a = Math.atan2(y2 - y1, x2 - x1);
        double[] xs = { x2, x2 - ARROW_SIZE * Math.cos(a - Math.PI / 6), x2 - ARROW_SIZE * Math.cos(a + Math.PI / 6) };
        double[] ys = { y2, y2 - ARROW_SIZE * Math.sin(a - Math.PI / 6), y2 - ARROW_SIZE * Math.sin(a + Math.PI / 6) };
        gc.setFill(c); gc.fillPolygon(xs, ys, 3);
    }

    private void drawVectorLabels(GraphicsContext gc, double cx, double cy, double scale, double w, double h) {
        gc.setFont(Font.font("SansSerif", FontWeight.BOLD, 11));
        double off = 10;
        gc.setFill(V_COLOR);
        gc.fillText(String.format("v (%.2f, %.2f)", vData[0], vData[1]),
            clamp(sx(cx, vData[0], scale) + off, 4, w - 150), clamp(sy(cy, vData[1], scale) - off, 14, h - 4));
        gc.setFill(PROJ_COLOR);
        gc.fillText(String.format("proj (%.2f, %.2f)", projData[0], projData[1]),
            clamp(sx(cx, projData[0], scale) + off, 4, w - 170), clamp(sy(cy, projData[1], scale) - off, 14, h - 4));
    }

    private void drawLegend(GraphicsContext gc, double w, double h, boolean hasOrth) {
        List<String> lbls = new ArrayList<>(); List<Color> colors = new ArrayList<>(); List<Boolean> dashed = new ArrayList<>();
        lbls.add("v  (original)"); colors.add(V_COLOR); dashed.add(false);
        lbls.add(hasOrth ? "proj(v)" : "proj(v) = v"); colors.add(PROJ_COLOR); dashed.add(false);
        if (hasOrth) { lbls.add("v − proj(v)"); colors.add(ORTH_COLOR); dashed.add(true); }
        if (uVectors != null) {
            String u = !isSubspace ? "u  (target)"
                : uVectors.length == 1 ? "a\u2081  (column of A)" : "a\u2081, a\u2082, \u2026  (columns of A)";
            lbls.add(u); colors.add(U_COLOR); dashed.add(false);
        }
        lbls.add(isSubspace ? "col(A)" : "span(u)"); colors.add(TARGET_COLOR); dashed.add(true);

        int spacing = 20, lineLen = 24, padH = 10, padV = 8;
        double boxH = lbls.size() * spacing + padV * 2, boxW = 188;
        double bx = w - boxW - 8, by = h - boxH - 8;
        gc.setFill(Color.rgb(255, 255, 255, 215 / 255.0));
        gc.fillRoundRect(bx, by, boxW, boxH, 8, 8);
        gc.setStroke(Color.web("#CCCCCC")); gc.setLineWidth(1); gc.setLineDashes(0);
        gc.strokeRoundRect(bx, by, boxW, boxH, 8, 8);
        gc.setFont(Font.font("SansSerif", 11));
        for (int i = 0; i < lbls.size(); i++) {
            double y = by + padV + (i + 1) * spacing - 3;
            gc.setStroke(colors.get(i)); gc.setLineWidth(2);
            gc.setLineDashes(dashed.get(i) ? new double[]{ 6, 4 } : new double[]{ 0 });
            gc.strokeLine(bx + padH, y, bx + padH + lineLen, y);
            gc.setLineDashes(0);
            gc.setFill(LABEL_COLOR);
            gc.fillText(lbls.get(i), bx + padH + lineLen + 6, y + 4);
        }
    }
}
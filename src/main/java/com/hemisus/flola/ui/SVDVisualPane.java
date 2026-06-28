package com.hemisus.flola.ui;

import com.hemisus.flola.model.Tensor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.List;

/** SVD 시각화 (Canvas). 2×2면 단위원→타원 기하 뷰, 그 외엔 특이값 막대 차트. */
public class SVDVisualPane extends Pane {

    private static final Color BG          = Color.web("#F8F9FB");
    private static final Color GRID        = Color.web("#E2E4EC");
    private static final Color AXIS        = Color.web("#9090A8");
    private static final Color UNIT_CIRCLE = Color.web("#4A7CBF");
    private static final Color ELLIPSE     = Color.web("#E06030");
    private static final Color V_COLOR     = Color.web("#2255CC");
    private static final Color U_COLOR     = Color.web("#CC3311");
    private static final Color TEXT        = Color.web("#333348");
    private static final Color MUTED       = Color.web("#8888AA");

    private static final Font LABEL_FNT = Font.font("SansSerif", FontWeight.BOLD,   11);
    private static final Font INFO_FNT  = Font.font("SansSerif", FontWeight.NORMAL, 11);
    private static final Font TITLE_FNT = Font.font("SansSerif", FontWeight.BOLD,   13);

    private final Canvas canvas = new Canvas();

    private double[][] matA, U, Vt;
    private double[]   sigma;
    private int        m, n;
    private boolean    hasData = false;

    public SVDVisualPane() {
        getChildren().add(canvas);
        setMinSize(0, 0);
        setStyle("-fx-background-color:#F8F9FB;");
        widthProperty().addListener((o, a, b)  -> { canvas.setWidth(b.doubleValue());  draw(); });
        heightProperty().addListener((o, a, b) -> { canvas.setHeight(b.doubleValue()); draw(); });
    }

    public void update(Tensor input, List<Tensor> outputs) {
        hasData = false;
        if (input == null || outputs == null || outputs.size() < 3) { draw(); return; }
        Tensor tU = outputs.get(0), tSigma = outputs.get(1), tVt = outputs.get(2);
        if (tU == null || tSigma == null || tVt == null) { draw(); return; }

        m = input.getShape()[0]; n = input.getShape()[1];
        int k = tSigma.dataSize();

        matA = new double[m][n];
        for (int i = 0; i < m; i++) for (int j = 0; j < n; j++) matA[i][j] = input.get(i, j);
        U = new double[m][k];
        for (int i = 0; i < m; i++) for (int j = 0; j < k; j++) U[i][j] = tU.get(i, j);
        sigma = new double[k];
        for (int i = 0; i < k; i++) sigma[i] = tSigma.getFlat(i);
        Vt = new double[k][n];
        for (int i = 0; i < k; i++) for (int j = 0; j < n; j++) Vt[i][j] = tVt.get(i, j);

        hasData = true;
        draw();
    }

    private void draw() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;
        gc.setFill(BG); gc.fillRect(0, 0, w, h);
        if (!hasData) {
            gc.setFill(MUTED); gc.setFont(INFO_FNT);
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText("No results of SVD", w / 2, h / 2);
            gc.setTextAlign(TextAlignment.LEFT);
            return;
        }
        if (m == 2 && n == 2) drawGeometricView(gc, w, h);
        else                  drawSingularValueChart(gc, w, h);
    }

    private void drawGeometricView(GraphicsContext gc, double w, double h) {
        double margin = 60, cx = w / 2, cy = h / 2;
        double maxExtent = Math.max(1.0, sigma.length > 0 ? sigma[0] : 1.0);
        double scale = (Math.min(w, h) / 2.0 - margin) / maxExtent;

        drawGrid(gc, w, h, cx, cy, scale);
        drawUnitCircle(gc, cx, cy, scale);
        drawTransformedEllipse(gc, cx, cy, scale);

        if (Vt.length >= 1 && n >= 2) drawArrow(gc, cx, cy, Vt[0][0] * scale, Vt[0][1] * scale, V_COLOR, "v\u2081", true);
        if (Vt.length >= 2 && n >= 2) drawArrow(gc, cx, cy, Vt[1][0] * scale, Vt[1][1] * scale, V_COLOR, "v\u2082", false);
        if (sigma.length >= 1 && m >= 2) drawArrow(gc, cx, cy, U[0][0] * sigma[0] * scale, U[1][0] * sigma[0] * scale, U_COLOR, "\u03c3\u2081u\u2081", true);
        if (sigma.length >= 2 && m >= 2) drawArrow(gc, cx, cy, U[0][1] * sigma[1] * scale, U[1][1] * sigma[1] * scale, U_COLOR, "\u03c3\u2082u\u2082", false);

        drawInfo2x2(gc, w, h);
        drawLegend2x2(gc, margin, h);
    }

    private void drawGrid(GraphicsContext gc, double w, double h, double cx, double cy, double scale) {
        gc.setLineWidth(0.5); gc.setStroke(GRID); gc.setLineDashes(0);
        int gridStep = (int) Math.ceil(scale * 0.5);
        if (gridStep < 20) gridStep = (int) scale;
        if (gridStep < 1) gridStep = 1;
        for (double x = cx % gridStep; x < w; x += gridStep) gc.strokeLine(x, 0, x, h);
        for (double y = cy % gridStep; y < h; y += gridStep) gc.strokeLine(0, y, w, y);
        gc.setStroke(AXIS); gc.setLineWidth(1.2);
        gc.strokeLine(0, cy, w, cy); gc.strokeLine(cx, 0, cx, h);
        gc.setFill(TEXT); gc.fillOval(cx - 3, cy - 3, 6, 6);
    }

    private void drawUnitCircle(GraphicsContext gc, double cx, double cy, double scale) {
        double r = scale;
        gc.setStroke(UNIT_CIRCLE); gc.setLineWidth(1.5); gc.setLineDashes(6, 4);
        gc.strokeOval(cx - r, cy - r, 2 * r, 2 * r); gc.setLineDashes(0);
    }

    private void drawTransformedEllipse(GraphicsContext gc, double cx, double cy, double scale) {
        int pts = 200;
        double[] xs = new double[pts], ys = new double[pts];
        for (int i = 0; i < pts; i++) {
            double a = 2 * Math.PI * i / pts, cosA = Math.cos(a), sinA = Math.sin(a);
            double ox = matA[0][0] * cosA + matA[0][1] * sinA;
            double oy = matA[1][0] * cosA + matA[1][1] * sinA;
            xs[i] = cx + ox * scale; ys[i] = cy - oy * scale;
        }
        gc.setFill(Color.rgb(0xE0, 0x60, 0x30, 50 / 255.0));
        gc.fillPolygon(xs, ys, pts);
        gc.setStroke(ELLIPSE); gc.setLineWidth(2); gc.setLineDashes(0);
        gc.strokePolygon(xs, ys, pts);
    }

    private void drawArrow(GraphicsContext gc, double cx, double cy, double mathDx, double mathDy,
                           Color color, String label, boolean solid) {
        double x2 = cx + mathDx, y2 = cy - mathDy;
        gc.setStroke(color); gc.setLineWidth(2.2);
        gc.setLineDashes(solid ? new double[]{ 0 } : new double[]{ 5, 3 });
        gc.strokeLine(cx, cy, x2, y2); gc.setLineDashes(0);
        double a = Math.atan2(y2 - cy, x2 - cx); double len = 10;
        double[] xp = { x2, x2 - len * Math.cos(a - 0.42), x2 - len * Math.cos(a + 0.42) };
        double[] yp = { y2, y2 - len * Math.sin(a - 0.42), y2 - len * Math.sin(a + 0.42) };
        gc.setFill(color); gc.fillPolygon(xp, yp, 3);
        if (label != null) {
            gc.setFont(LABEL_FNT); gc.setFill(color.darker());
            gc.fillText(label, x2 + 12 * Math.cos(a) - 4, y2 + 12 * Math.sin(a) + 4);
        }
    }

    private void drawInfo2x2(GraphicsContext gc, double w, double h) {
        double x = w - 200, y = 24;
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(TITLE_FNT); gc.setFill(TEXT);
        gc.fillText("SVD  A = U\u03a3V\u1d40", x, y);
        gc.setFont(INFO_FNT); gc.setFill(TEXT);
        for (int i = 0; i < sigma.length; i++)
            gc.fillText(String.format("\u03c3%d = %.4f", i + 1, sigma[i]), x, y + 20 + i * 18);
        if (sigma.length >= 2 && sigma[1] > 1e-10) {
            double cond = sigma[0] / sigma[1];
            gc.setFill(cond > 100 ? Color.web("#CC3311") : MUTED);
            gc.fillText(String.format("cond = %.2f", cond), x, y + 20 + sigma.length * 18 + 6);
        }
        gc.setFill(MUTED); gc.setFont(Font.font("Monospaced", 10));
        gc.fillText(String.format("A = [[%.2f, %.2f],", matA[0][0], matA[0][1]), x, h - 40);
        gc.fillText(String.format("     [%.2f, %.2f]]", matA[1][0], matA[1][1]), x, h - 25);
    }

    private void drawLegend2x2(GraphicsContext gc, double margin, double h) {
        double x = margin, y = h - 80;
        gc.setFont(INFO_FNT);
        gc.setStroke(UNIT_CIRCLE); gc.setLineWidth(1.5); gc.setLineDashes(5, 3);
        gc.strokeLine(x, y, x + 22, y); gc.setLineDashes(0);
        gc.setFill(TEXT); gc.fillText("Unit Circle", x + 28, y + 4);
        
        gc.setStroke(ELLIPSE); gc.setLineWidth(2);
        gc.strokeLine(x, y + 18, x + 22, y + 18);
        gc.setFill(TEXT); gc.fillText("Transformed Ellipse A(Unit Circle)", x + 28, y + 22);
        
        gc.setStroke(V_COLOR); gc.strokeLine(x, y + 36, x + 22, y + 36);
        gc.setFill(TEXT); gc.fillText("v₁, v₂ (Right Singular Vectors)", x + 28, y + 40);
        
        gc.setStroke(U_COLOR); gc.strokeLine(x, y + 54, x + 22, y + 54);
        gc.setFill(TEXT); gc.fillText("σᵢuᵢ (Scaled Left Singular Vectors)", x + 28, y + 58);
    }

    private void drawSingularValueChart(GraphicsContext gc, double w, double h) {
        if (sigma == null || sigma.length == 0) return;
        int k = sigma.length;
        double margin = 60;
        double chartW = w - margin * 2, chartH = h - margin * 2 - 80;
        double chartX = margin, chartY = margin + 50, maxSig = sigma[0];

        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(TITLE_FNT); gc.setFill(TEXT);
        gc.fillText(String.format("SVD — %d×%d Matrix (Singular Value Spectrum)", m, n), chartX, chartY - 24);
        gc.setFill(Color.WHITE); gc.fillRect(chartX, chartY, chartW, chartH);
        gc.setStroke(GRID); gc.setLineWidth(0.8); gc.setLineDashes(0);
        gc.strokeRect(chartX, chartY, chartW, chartH);
        for (int i = 1; i <= 4; i++) { double gy = chartY + chartH * i / 5; gc.strokeLine(chartX, gy, chartX + chartW, gy); }

        double barW = Math.max(6, chartW / (k * 2)), gap = barW;
        double startX = chartX + (chartW - k * (barW + gap)) / 2;
        for (int i = 0; i < k; i++) {
            double ratio = maxSig > 0 ? sigma[i] / maxSig : 0;
            double barH = chartH * ratio, bx = startX + i * (barW + gap), by = chartY + chartH - barH;
            Color barColor = UNIT_CIRCLE.interpolate(ELLIPSE, (double) i / Math.max(k - 1, 1));
            gc.setFill(barColor); gc.fillRect(bx, by, barW, barH);
            gc.setStroke(barColor.darker()); gc.setLineWidth(1); gc.setLineDashes(0);
            gc.strokeRect(bx, by, barW, barH);
            gc.setTextAlign(TextAlignment.CENTER);
            gc.setFont(Font.font("SansSerif", FontWeight.BOLD, 10)); gc.setFill(TEXT);
            gc.fillText(String.format("%.3f", sigma[i]), bx + barW / 2, by - 4);
            gc.setFont(INFO_FNT); gc.setFill(MUTED);
            gc.fillText("\u03c3" + (i + 1), bx + barW / 2, chartY + chartH + 18);
        }
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(INFO_FNT); gc.setFill(MUTED);
        gc.fillText("0", chartX - 18, chartY + chartH + 4);
        gc.fillText(String.format("%.2f", maxSig), chartX - 42, chartY + 4);

        drawChartInfo(gc, chartX, chartY + chartH + 35);
    }

    private void drawChartInfo(GraphicsContext gc, double x, double y) {
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(INFO_FNT); gc.setFill(TEXT);
        int rank = 0; for (double s : sigma) if (s > 1e-10) rank++;
        gc.fillText("rank = " + rank, x, y);
        if (rank >= 2 && sigma[rank - 1] > 1e-10) {
            double cond = sigma[0] / sigma[rank - 1];
            gc.setFill(cond > 1000 ? Color.web("#CC3311") : cond > 100 ? Color.web("#E06030") : MUTED);
            gc.fillText(String.format("cond = %.2f", cond), x + 90, y);
        }
        double total = 0, top2 = 0;
        for (int i = 0; i < sigma.length; i++) { total += sigma[i] * sigma[i]; if (i < 2) top2 += sigma[i] * sigma[i]; }
        if (total > 0) { 
            gc.setFill(MUTED); 
            gc.fillText(String.format("Top 2 Energy Ratio: %.1f%%", top2 / total * 100), x + 210, y); 
        }	
    }
}
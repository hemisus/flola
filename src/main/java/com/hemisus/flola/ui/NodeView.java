package com.hemisus.flola.ui;

import com.hemisus.flola.viewmodel.NodeViewModel;
import com.hemisus.flola.viewmodel.OperationViewModel;
import com.hemisus.flola.viewmodel.TensorViewModel;
import com.hemisus.flola.viewmodel.CustomOperationViewModel;
import com.hemisus.flola.viewmodel.UtilityNodeViewModel;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import com.hemisus.flola.utils.OperationIcons;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 노드 시각화 (Swing의 NodeComponent의 JavaFX 판).
 * 어두운 본체 + 컬러 헤더 + 아이콘/타입/이름/sub 라벨 + 입출력 포트.
 */
public class NodeView extends Pane {

    // 디멘션
    public static final int WIDTH    = 140;
    public static final int HEIGHT   = 100;
    public static final int HEADER_H = 30;
    public static final int ARC      = 10;

    // 컬러 (Swing 버전과 동일)
    private static final Color COL_BODY      = Color.rgb(38,  38,  46);
    private static final Color COL_BORDER    = Color.rgb(68,  68,  80);
    private static final Color COL_DIVIDER   = Color.rgb(255, 255, 255, 0.09);
    private static final Color COL_ICON_BG   = Color.rgb(0,   0,   0,  0.31);

    private static final Color HDR_TENSOR  = Color.rgb(28,  88, 148);
    private static final Color HDR_OP      = Color.rgb(22, 106,  80);
    private static final Color HDR_DEFAULT = Color.rgb(64,  64,  88);
    private static final Color HDR_CUSTOM  = Color.rgb(108,  76, 142);
    private static final Color HDR_UTILITY = Color.rgb(120, 100,  60);
    
    public  static final Color PORT_IN      = Color.rgb( 80, 160, 255);
    public  static final Color PORT_OUT     = Color.rgb(255, 128,  50);
    public  static final Color PORT_VAR_IN  = Color.rgb( 65, 210, 190);
    public  static final Color PORT_VAR_OUT = Color.rgb(200,  75, 195);

    private static final Color COL_SELECTED     = Color.rgb(100, 160, 255);   // 테두리 색

    private final NodeViewModel vm;
    private Label iconLabel, typeLabel, nameLabel, subLabel;
    private Circle    iconBg;     // 컬러 배지 (글자 아이콘 뒤). PNG 표시 시 숨김.
    private ImageView iconImage;  // 연산 PNG 아이콘 (있으면 글자 대신 표시)
    private final List<PortView> inputPorts  = new ArrayList<>();
    private final List<PortView> outputPorts = new ArrayList<>();

    // 드래그 상태
    private double pressSceneX, pressSceneY;
    private double lastSceneX, lastSceneY;   // 증분 델타 계산용
    private boolean dragging = false;
    private boolean selected = false;
    private javafx.scene.control.ContextMenu activeMenu;   // 현재 떠있는 우클릭 메뉴 (중복 방지)

    // 외부 콜백
    private Consumer<NodeView> onSelected;
    private Consumer<NodeView> onDoubleClicked;
    private Consumer<NodeView> onRemove;
    private BiConsumer<NodeView, Boolean>  onNodePressed;   // (node, shiftDown) — 선택 갱신
    private BiConsumer<NodeView, Point2D>  onDragDelta;     // (node, worldDelta) — 다중 이동
    private Consumer<NodeView>             onDragEnd;       // 드래그 완료 (이동 커밋 시점)
    private Runnable onRemoveSelected;   // 선택 전체 삭제
    private Runnable onCopySelected;     // 선택 전체 복사
    private java.util.function.IntSupplier selectionCount; // 현재 선택 노드 수

    public NodeView(NodeViewModel vm) {
        this.vm = vm;
        setPrefSize(WIDTH, HEIGHT);
        setMinSize (WIDTH, HEIGHT);
        setMaxSize (WIDTH, HEIGHT);
        setPickOnBounds(true);

        setupBackground();
        setupLabels();
        refreshPorts();
        setupInteractions();

        vm.addListener(this::onViewModelChanged);
    }

    // ── 배경 / 외곽 ───────────────────────────────────────

    private void setupBackground() {
        Color headerColor = getHeaderColor();
        setBackground(new Background(
            new BackgroundFill(COL_BODY, new CornerRadii(ARC), Insets.EMPTY),
            new BackgroundFill(headerColor,
                new CornerRadii(ARC, ARC, 0, 0, false),
                new Insets(0, 0, HEIGHT - HEADER_H, 0))
        ));
        applyBorder();
    }

    /** 선택 여부에 따라 테두리 색/두께를 적용 */
    private void applyBorder() {
        Color c = selected ? COL_SELECTED : COL_BORDER;
        double w = selected ? 2.2 : 0.8;
        setBorder(new Border(new BorderStroke(
            c, BorderStrokeStyle.SOLID, new CornerRadii(ARC), new BorderWidths(w))));

        // 선택 시 노드 바디 전체에 반투명 밝기 오버레이 추가/제거
        if (selected) {
            if (getEffect() == null) {
                javafx.scene.effect.DropShadow glow = new javafx.scene.effect.DropShadow();
                glow.setColor(COL_SELECTED.deriveColor(0, 1, 1, 0.55));
                glow.setRadius(12);
                glow.setSpread(0.05);
                setEffect(glow);
            }
        } else {
            setEffect(null);
        }
    }

    private Color getHeaderColor() {
        if (vm instanceof TensorViewModel)          return HDR_TENSOR;
        if (vm instanceof CustomOperationViewModel) return HDR_CUSTOM;
        if (vm instanceof UtilityNodeViewModel)     return HDR_UTILITY;
        if (vm instanceof OperationViewModel)       return HDR_OP;
        return HDR_DEFAULT;
    }

    // ── 라벨 / 아이콘 ─────────────────────────────────────

    private void setupLabels() {
        iconBg = new Circle(16, 15, 10);
        iconBg.setFill(COL_ICON_BG);

        iconLabel = mkLabel(6, 5, 20, 20, Color.WHITE,
            11, true, Pos.CENTER);

        // 연산 PNG 아이콘 — 배지 원 중심(16,15)에 22×22로 배치 (있을 때만 표시)
        final double ICON_SIZE = 22;
        iconImage = new ImageView();
        iconImage.setFitWidth(ICON_SIZE);
        iconImage.setFitHeight(ICON_SIZE);
        iconImage.setPreserveRatio(true);
        iconImage.setSmooth(true);
        iconImage.setMouseTransparent(true);
        iconImage.setLayoutX(16 - ICON_SIZE / 2);
        iconImage.setLayoutY(15 - ICON_SIZE / 2);
        iconImage.setVisible(false);

        typeLabel = mkLabel(32, 8, 102, 15, Color.rgb(210, 210, 220),
            12, false, Pos.CENTER_LEFT);

        nameLabel = mkLabel(4, HEADER_H + 1, WIDTH - 8, HEIGHT - HEADER_H - 2,
            Color.rgb(232, 232, 238), 13, false, Pos.CENTER);

        subLabel = mkLabel(4, 72, WIDTH - 8, 20,
            Color.rgb(148, 148, 165), 10, false, Pos.CENTER);
        subLabel.setVisible(false);

        // header/body divider
        Region divider = new Region();
        divider.setLayoutX(0);
        divider.setLayoutY(HEADER_H);
        divider.setPrefSize(WIDTH, 1);
        divider.setMinSize (WIDTH, 1);
        divider.setMaxSize (WIDTH, 1);
        divider.setBackground(new Background(new BackgroundFill(COL_DIVIDER, null, null)));

        getChildren().addAll(iconBg, iconLabel, iconImage, typeLabel, divider, nameLabel, subLabel);
        syncLabels();
    }

    private static Label mkLabel(double x, double y, double w, double h,
                                  Color textColor, double fontSize, boolean bold, Pos align) {
        Label l = new Label();
        l.setLayoutX(x); l.setLayoutY(y);
        l.setPrefSize(w, h); l.setMinSize(w, h); l.setMaxSize(w, h);
        l.setTextFill(textColor);
        // 폰트 패밀리는 .root(main.css)에서 상속받도록 두고, 크기/굵기만 지정
        l.setStyle("-fx-font-size:" + fontSize + "px;" + (bold ? " -fx-font-weight:bold;" : ""));
        l.setAlignment(align);
        return l;
    }

    private void syncLabels() {
    	String iconOpType = null;   // PNG 아이콘을 시도할 연산 타입 (없으면 글자 배지)
    	if (vm instanceof OperationViewModel ovm) {
            String op = ovm.getOperationType();
            typeLabel.setText(op);
            iconLabel.setText(op.isEmpty() ? "?" : String.valueOf(op.charAt(0)).toUpperCase());
            iconOpType = op;
        } else if (vm instanceof CustomOperationViewModel cvm) {
            String op = cvm.getOperationType();
            typeLabel.setText(op);
            iconLabel.setText(op.isEmpty() ? "C" : String.valueOf(op.charAt(0)).toUpperCase());
        } else {
            String icon = vm.getIconText();
            typeLabel.setText(icon != null ? kindLabel(icon) : "");
            iconLabel.setText(icon != null ? icon : "");
        }
        applyIconImage(iconOpType);
        nameLabel.setText(vm.getNodeName());

        String sub = vm.getSubLabel();
        subLabel.setText(sub);
        if (!sub.isEmpty()) {
            nameLabel.setLayoutY(HEADER_H + 4);
            nameLabel.setPrefHeight(34); nameLabel.setMinHeight(34); nameLabel.setMaxHeight(34);
            subLabel.setVisible(true);
        } else {
            nameLabel.setLayoutY(HEADER_H + 1);
            double bodyH = HEIGHT - HEADER_H - 2;
            nameLabel.setPrefHeight(bodyH); nameLabel.setMinHeight(bodyH); nameLabel.setMaxHeight(bodyH);
            subLabel.setVisible(false);
        }
    }

    /**
     * 연산 PNG 아이콘을 적용한다. 아이콘이 있으면 글자 배지(원+글자) 대신 PNG를 표시하고,
     * 없으면(또는 operationType이 null이면) 기존 글자 배지로 폴백한다.
     */
    private void applyIconImage(String operationType) {
        Image img = (operationType != null) ? OperationIcons.image(operationType) : null;
        boolean hasImg = (img != null);
        iconImage.setImage(img);
        iconImage.setVisible(hasImg);
        iconLabel.setVisible(!hasImg);   // PNG 있으면 글자 숨김
        iconBg.setVisible(!hasImg);      // PNG 있으면 컬러 배지 원도 숨김
    }

    private static String kindLabel(String icon) {
        return switch (icon) {
            case "S" -> "Scalar";
            case "V" -> "Vector";
            case "M" -> "Matrix";
            case "T" -> "Tensor";
            default  -> icon;
        };
    }

    // ── 포트 ──────────────────────────────────────────────

    public void refreshPorts() {
        getChildren().removeAll(inputPorts);
        getChildren().removeAll(outputPorts);
        inputPorts.clear();
        outputPorts.clear();

        int inCount  = vm.getInputCount();
        int outCount = vm.getOutputCount();

        boolean varIn  = vm instanceof OperationViewModel ovm && ovm.isInputVariadic();
        boolean varOut = vm instanceof OperationViewModel ovm && ovm.isOutputVariadic();

        if (varIn || inCount >= 4) addPortView(Port.Type.INPUT, -1, PORT_VAR_IN);
        else for (int i = 0; i < inCount; i++) addPortView(Port.Type.INPUT, i, PORT_IN);

        if (varOut || outCount >= 4) addPortView(Port.Type.OUTPUT, -1, PORT_VAR_OUT);
        else for (int i = 0; i < outCount; i++) addPortView(Port.Type.OUTPUT, i, PORT_OUT);

        layoutPorts();
    }

    private void addPortView(Port.Type type, int index, Color color) {
        Port p = new Port(this, type, index);
        PortView pv = new PortView(p, color);
        (type == Port.Type.INPUT ? inputPorts : outputPorts).add(pv);
        getChildren().add(pv);
    }

    private void layoutPorts() {
        layoutPortList(inputPorts,  -2);
        layoutPortList(outputPorts, WIDTH - 10);
    }

    private void layoutPortList(List<PortView> ports, double x) {
        if (ports.isEmpty()) return;

        PortView first = ports.get(0);
        if (ports.size() == 1 && first.getPort().getIndex() == -1) {
            double ph = first.getPrefHeight();
            first.setLayoutX(x);
            // 통합 포트도 일반 포트처럼 헤더 아래 '본체' 기준으로 중앙 정렬
            first.setLayoutY(HEADER_H + (HEIGHT - HEADER_H - ph) / 2);
        } else {
            double spacing = (HEIGHT - HEADER_H) / (double)(ports.size() + 1);
            for (int i = 0; i < ports.size(); i++) {
                PortView p = ports.get(i);
                p.setLayoutX(x);
                p.setLayoutY(HEADER_H + spacing * (i + 1) - p.getPrefHeight() / 2);
            }
        }
    }

    // ── 인터랙션 (선택/드래그/우클릭) ─────────────────────

    private void setupInteractions() {
        setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            pressSceneX  = e.getSceneX();
            pressSceneY  = e.getSceneY();
            lastSceneX   = e.getSceneX();
            lastSceneY   = e.getSceneY();
            dragging = false;
            toFront();  // 클릭한 노드를 위로 올림
            if (onNodePressed != null) onNodePressed.accept(this, e.isShortcutDown());
            e.consume();
        });

        setOnMouseDragged(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            // 부모(world Group)의 scale을 고려한 delta
            double scale = getParent() != null
                ? getParent().getLocalToSceneTransform().getMxx() : 1.0;
            if (scale == 0) scale = 1;

            if (!dragging) {
                double tdx = (e.getSceneX() - pressSceneX) / scale;
                double tdy = (e.getSceneY() - pressSceneY) / scale;
                if (Math.abs(tdx) <= 2 && Math.abs(tdy) <= 2) { e.consume(); return; }
                dragging = true;
                lastSceneX = pressSceneX;   // 첫 emit가 press~현재 누적분을 포함 → 점프 없음
                lastSceneY = pressSceneY;
            }

            double dx = (e.getSceneX() - lastSceneX) / scale;
            double dy = (e.getSceneY() - lastSceneY) / scale;
            lastSceneX = e.getSceneX();
            lastSceneY = e.getSceneY();

            if (onDragDelta != null) onDragDelta.accept(this, new Point2D(dx, dy));
            else moveBy(dx, dy);   // 콜백 없으면 단독 이동 (fallback)
            e.consume();
        });

        setOnMouseReleased(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (dragging && onDragEnd != null) onDragEnd.accept(this);  // 이동 커밋
            e.consume();
        });

        setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (dragging) return;   // 드래그였으면 클릭으로 안 침
            if (e.getClickCount() == 2 && onDoubleClicked != null) {
                onDoubleClicked.accept(this);
                return;
            }
            if (e.isShortcutDown()) return;   // Ctrl/Cmd 선택은 press에서 toggle 처리됨
            if (e.getClickCount() == 1 && onSelected != null) {
                onSelected.accept(this);
            }
        });

        // 우클릭 메뉴 — 선택 상태에 따라 매번 동적으로 재구성
        setOnContextMenuRequested(e -> {
            if (activeMenu != null) { activeMenu.hide(); activeMenu = null; }   // 기존 메뉴 닫기

            int selCount = (selectionCount != null) ? selectionCount.getAsInt() : 0;

            javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();

            // 항상: 이 노드 단독 삭제
            javafx.scene.control.MenuItem removeOne = new javafx.scene.control.MenuItem("Remove");
            removeOne.setOnAction(ev -> { if (onRemove != null) onRemove.accept(this); });
            menu.getItems().add(removeOne);

            // 다중 선택 중일 때만 추가
            if (selCount > 1) {
                menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());

                javafx.scene.control.MenuItem removeSel =
                    new javafx.scene.control.MenuItem("Remove Selected (" + selCount + ")");
                removeSel.setOnAction(ev -> { if (onRemoveSelected != null) onRemoveSelected.run(); });
                menu.getItems().add(removeSel);

                javafx.scene.control.MenuItem copySel =
                    new javafx.scene.control.MenuItem("Copy Selected (" + selCount + ")");
                copySel.setOnAction(ev -> { if (onCopySelected != null) onCopySelected.run(); });
                menu.getItems().add(copySel);
            }

            menu.setOnHidden(ev -> { if (activeMenu == menu) activeMenu = null; });
            activeMenu = menu;
            menu.show(this, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    /** 현재 위치에서 (dx, dy) world 좌표만큼 이동 */
    public void moveBy(double dx, double dy) {
        setLayoutX(getLayoutX() + dx);
        setLayoutY(getLayoutY() + dy);
    }

    /** 선택 시각 상태 토글 (테두리 강조) */
    public void setSelected(boolean sel) {
        if (selected == sel) return;
        selected = sel;
        applyBorder();
    }

    public boolean isSelected() { return selected; }

    private void onViewModelChanged() {
        syncLabels();
        refreshPorts();
    }

    // ── 외부 API ──────────────────────────────────────────

    public void setOnSelected(Consumer<NodeView> l)       { this.onSelected = l;      }
    public void setOnDoubleClicked(Consumer<NodeView> l)  { this.onDoubleClicked = l; }
    public void setOnRemove(Consumer<NodeView> l)         { this.onRemove = l;        }
    public void setOnNodePressed(BiConsumer<NodeView, Boolean> l) { this.onNodePressed = l; }
    public void setOnDragDelta(BiConsumer<NodeView, Point2D> l)   { this.onDragDelta = l;   }
    public void setOnDragEnd(Consumer<NodeView> l)               { this.onDragEnd = l;     }
    public void setOnRemoveSelected(Runnable l)               { this.onRemoveSelected = l;  }
    public void setOnCopySelected(Runnable l)                 { this.onCopySelected = l;    }
    public void setSelectionCount(java.util.function.IntSupplier s) { this.selectionCount = s; }

    public NodeViewModel  getViewModel()   { return vm;          }
    public List<PortView> getInputPorts()  { return inputPorts;  }
    public List<PortView> getOutputPorts() { return outputPorts; }
    public PortView getInputPortView(int index) {
        if (inputPorts.isEmpty()) return null;
        int i = Math.max(0, Math.min(index, inputPorts.size() - 1));
        return inputPorts.get(i);
    }

    public PortView getOutputPortView(int index) {
        if (outputPorts.isEmpty()) return null;
        int i = Math.max(0, Math.min(index, outputPorts.size() - 1));
        return outputPorts.get(i);
    }
}
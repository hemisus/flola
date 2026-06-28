package com.hemisus.flola.ui;

import com.hemisus.flola.viewmodel.NodeViewModel;
import com.hemisus.flola.viewmodel.TensorViewModel;
import com.hemisus.flola.model.Tensor;
import com.hemisus.flola.model.TensorNode;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 텐서 편집 창 — 별도 Stage (비모달).
 * Swing TensorEditorView + TensorEditorController를 하나로 통합.
 */
public class TensorEditorStage {

    // ── 색상 ──────────────────────────────────────────
	private static final String BG         = "#f5f4f1";
    private static final String BORDER_CLR = "#e0dfd9";
    private static final String ACCENT     = "#4A7CBF";
    private static final String SEC_BTN    = "#e8e7e1";
    private static final String LABEL_FG   = "#5a5a52";
    private static final String TITLE_FG   = "#989688";

    private static final int CELL_SIZE = 45;

    private final TensorViewModel vm;
    private final Stage stage;
    private final String tensorUuid;

    /** Tensor당 단일 편집 세션 — 같은 Tensor면 새로 안 열고 기존 창을 focus. (UUID 기준) */
    private static final Map<String, TensorEditorStage> OPEN = new HashMap<>();

    /**
     * save 1회를 캔버스 undo history에 통합하기 위한 콜백 (옵션 A).
     * MainController가 등록하며, save 직전/직후 노드 스냅샷을 받아 Command로 기록한다.
     */
    public interface SaveListener {
        void onTensorSaved(TensorNode node,
                           Tensor before, String beforeName,
                           Tensor after,  String afterName);
    }
    private static SaveListener saveListener;
    public static void setSaveListener(SaveListener l) { saveListener = l; }
    /** 이 세션 전용 리스너 (open 시 주입). null이면 static saveListener로 폴백. */
    private final SaveListener sessionSaveListener;

    // ── UI 필드 ───────────────────────────────────────
    private TextField nodeNameField, tensorNameField;
    private Label kindLabel;
    private VBox shapeEditorBox, fixedDimBox, gridBox;
    private Button newAxisBtn, removeAxisBtn, transposeBtn;
    private Button applyBtn, saveBtn, cancelBtn;
    private Button undoBtn, redoBtn;
    private ComboBox<String> rowAxisCombo, colAxisCombo;
    private TextArea currentValueArea, inputValueArea;

    private final List<Spinner<Integer>> shapeSpinners = new ArrayList<>();
    private final List<TextField>        axisNameFields = new ArrayList<>();

    /** 풀 리빌드 중 옛 컨트롤의 focus-lost가 undo 스택을 오염시키는 것을 막는 가드. */
    private boolean rebuilding = false;

    // ── 진입점 ────────────────────────────────────────
    public static void open(NodeViewModel vm) {
        open(vm, null);
    }

    /**
     * 세션 리스너를 지정해 연다. save 1회를 <b>이 리스너</b>로 보낸다(없으면 static 폴백).
     * 어디서 열렸는지에 따라 적합한 undo 타임라인으로 라우팅하기 위함:
     * 메인 캔버스 → MainController(static), 서브에디터 → 그 에디터의 editorUndo.
     */
    public static void open(NodeViewModel vm, SaveListener sessionListener) {
        if (!(vm instanceof TensorViewModel tvm)) return;
        String uuid = tvm.getNode().getTensor().getUuid();
        TensorEditorStage existing = OPEN.get(uuid);
        if (existing != null) {                 // 같은 Tensor 세션이 이미 열림 → 그 창을 앞으로
            existing.stage.toFront();
            existing.stage.requestFocus();
            return;
        }
        new TensorEditorStage(tvm, sessionListener);
    }

    private TensorEditorStage(TensorViewModel tvm, SaveListener sessionListener) {
        this.vm    = tvm;
        this.sessionSaveListener = sessionListener;
        this.stage = new Stage();
        this.tensorUuid = tvm.getNode().getTensor().getUuid();
        OPEN.put(tensorUuid, this);
        stage.setTitle("Tensor Editor  —  " + tvm.getEditTensor().getName());
        stage.setWidth(1260); stage.setHeight(860);
        stage.setMinWidth(960); stage.setMinHeight(600);

        BorderPane root = new BorderPane();
        root.getStyleClass().add("editor-root");          // ← 인라인 style 제거하고 클래스로
        root.setTop(buildTopBar());
        root.setLeft(buildLeftPanel());
        root.setCenter(buildCenterPanel());
        root.setRight(buildRightPanel());

        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource(
            "/com/hemisus/flola/css/main.css").toExternalForm());   // ← 추가
        // 단축키: Ctrl/⌘+Z = undo, Ctrl/⌘+Y 또는 Ctrl/⌘+Shift+Z = redo
        scene.getAccelerators().put(
            new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN), this::doUndo);
        scene.getAccelerators().put(
            new KeyCodeCombination(KeyCode.Y, KeyCombination.SHORTCUT_DOWN), this::doRedo);
        scene.getAccelerators().put(
            new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN), this::doRedo);
        stage.setScene(scene);
        stage.setOnHidden(e -> OPEN.remove(tensorUuid));   // 닫히면 세션 등록 해제
        setupListeners();
        refreshAll();
        stage.show();
    }

    // ══════════════════════════════════════════════════
    //  상단 바
    // ══════════════════════════════════════════════════

    private HBox buildTopBar() {
        nodeNameField   = makeTextField(13);
        tensorNameField = makeTextField(13);
        // 열자마자 이름 필드가 포커스를 가로채면 첫 Ctrl+Z가 텍스트 undo로 먹히므로 자동 포커스 방지
        // (마우스 클릭으로는 정상 포커스됨)
        nodeNameField.setFocusTraversable(false);
        tensorNameField.setFocusTraversable(false);
        kindLabel = new Label("—");
        kindLabel.setFont(Font.font("SansSerif", FontWeight.BOLD, 13));
        kindLabel.setTextFill(Color.web(ACCENT));

        HBox info = new HBox(8,
            barLabel("Node:"), nodeNameField,
            new Separator(Orientation.VERTICAL),
            barLabel("Tensor:"), tensorNameField,
            new Separator(Orientation.VERTICAL),
            barLabel("Kind:"), kindLabel);
        info.setAlignment(Pos.CENTER_LEFT);

        cancelBtn = makeSecBtn("Cancel");
        saveBtn   = makePrimBtn("Save Changes");
        undoBtn   = makeSecBtn("↶ Undo");
        redoBtn   = makeSecBtn("↷ Redo");
        HBox btns = new HBox(8, undoBtn, redoBtn,
            new Separator(Orientation.VERTICAL), cancelBtn, saveBtn);
        btns.setAlignment(Pos.CENTER_RIGHT);

        HBox bar = new HBox(info, btns);
        HBox.setHgrow(info, Priority.ALWAYS);
        bar.setPadding(new Insets(10, 16, 10, 16));
        bar.getStyleClass().add("editor-topbar");
        return bar;
    }

    // ══════════════════════════════════════════════════
    //  왼쪽 패널 (Shape / View Axis / Fixed / Quick Actions)
    // ══════════════════════════════════════════════════

    private ScrollPane buildLeftPanel() {
        VBox left = new VBox(8);
        left.setPadding(new Insets(10, 6, 10, 10));
        left.setStyle("-fx-background-color:" + BG + ";");

        // 1. Shape Editor
        shapeEditorBox = new VBox(2);
        newAxisBtn    = makeSmBtn("＋ Axis");
        removeAxisBtn = makeSmBtn("－ Axis");
        VBox shapeContent = new VBox(6, styledScroll(shapeEditorBox, 110), new HBox(4, newAxisBtn, removeAxisBtn));
        left.getChildren().add(sectionCard("Shape Editor", shapeContent));

        // 2. View Axis
        rowAxisCombo = new ComboBox<>();
        colAxisCombo = new ComboBox<>();
        rowAxisCombo.setMaxWidth(Double.MAX_VALUE);
        colAxisCombo.setMaxWidth(Double.MAX_VALUE);
        GridPane ag = new GridPane(); ag.setHgap(6); ag.setVgap(6);
        ag.add(barLabel("Row:"), 0, 0); ag.add(rowAxisCombo, 1, 0);
        ag.add(barLabel("Col:"), 0, 1); ag.add(colAxisCombo, 1, 1);
        ag.getColumnConstraints().addAll(new ColumnConstraints(), grow());
        left.getChildren().add(sectionCard("View Axis", ag));

        // 3. Fixed Indices
        fixedDimBox = new VBox(4);
        left.getChildren().add(sectionCard("Fixed Indices", styledScroll(fixedDimBox, 130)));

        // 4. Quick Actions
        transposeBtn = makeSmBtn("⇄  Transpose");
        transposeBtn.setMaxWidth(Double.MAX_VALUE);
        left.getChildren().add(sectionCard("Quick Actions", transposeBtn));

        ScrollPane outer = new ScrollPane(left);
        outer.setFitToWidth(true);
        outer.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        outer.setStyle("-fx-background:" + BG + ";");
        outer.setPrefWidth(260); outer.setMinWidth(240);
        return outer;
    }

    // ══════════════════════════════════════════════════
    //  중앙 패널 (2D Slice View)
    // ══════════════════════════════════════════════════

    private Node buildCenterPanel() {
        gridBox = new VBox(4);
        gridBox.setPadding(new Insets(10));
        gridBox.setAlignment(Pos.TOP_CENTER);
        gridBox.setStyle("-fx-background-color:white;");

        ScrollPane scroll = new ScrollPane(gridBox);
        scroll.setFitToWidth(true);

        VBox card = sectionCard("2D Slice View", scroll);
        card.setPadding(new Insets(10, 6, 10, 6));
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return card;
    }

    // ══════════════════════════════════════════════════
    //  오른쪽 패널 (Input String / Current Value)
    // ══════════════════════════════════════════════════

    private VBox buildRightPanel() {
        VBox right = new VBox(8);
        right.setPadding(new Insets(10, 10, 10, 6));
        right.setPrefWidth(210);
        right.setStyle("-fx-background-color:" + BG + ";");

        inputValueArea = new TextArea();
        inputValueArea.setWrapText(true);
        inputValueArea.setFont(Font.font("Monospaced", 12));
        applyBtn = makePrimBtn("Apply String");
        applyBtn.setMaxWidth(Double.MAX_VALUE);
        VBox inputContent = new VBox(6, styledScroll(inputValueArea, -1), applyBtn);
        VBox.setVgrow(inputContent.getChildren().get(0), Priority.ALWAYS);
        VBox inputCard = sectionCard("Input String", inputContent);

        currentValueArea = new TextArea();
        currentValueArea.setEditable(false);
        currentValueArea.setWrapText(true);
        currentValueArea.setFont(Font.font("Monospaced", 11));
        currentValueArea.setStyle("-fx-control-inner-background:#F4F4F8;");
        VBox currCard = sectionCard("Current Value", styledScroll(currentValueArea, 220));

        right.getChildren().addAll(inputCard, currCard);
        VBox.setVgrow(inputCard, Priority.ALWAYS);
        return right;
    }

    // ══════════════════════════════════════════════════
    //  리스너
    // ══════════════════════════════════════════════════

    private void setupListeners() {
        // 에디터 채널 구독 — 저빈도 변경(undo/redo/reshape/transpose/이름/외부)이 풀 리빌드.
        // 고빈도(셀·뷰축·fixed index)는 핸들러가 직접 부분 갱신하므로 여기 오지 않는다.
        vm.addEditorListener(() -> Platform.runLater(this::rebuildEditorUI));

        nodeNameField.setOnAction(e -> commitNodeName());
        nodeNameField.focusedProperty().addListener((o, ov, nv) -> { if (!nv) commitNodeName(); });
        tensorNameField.setOnAction(e -> commitTensorName());
        tensorNameField.focusedProperty().addListener((o, ov, nv) -> { if (!nv) commitTensorName(); });

        newAxisBtn.setOnAction(e -> vm.addAxis(1));
        removeAxisBtn.setOnAction(e -> removeLastAxis());
        applyBtn.setOnAction(e -> applyValueString());
        transposeBtn.setOnAction(e -> applyTranspose());
        saveBtn.setOnAction(e -> saveChanges());
        cancelBtn.setOnAction(e -> vm.cancel());
        undoBtn.setOnAction(e -> doUndo());
        redoBtn.setOnAction(e -> doRedo());

        stage.setOnCloseRequest(e -> {
            if (vm.isChanged()) {
                Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "Do you want to save changes?",
                    ButtonType.YES, ButtonType.NO, ButtonType.CANCEL);
                a.setHeaderText(null); a.initOwner(stage);
                var r = a.showAndWait();
                if (r.isEmpty() || r.get() == ButtonType.CANCEL) { e.consume(); return; }
                if (r.get() == ButtonType.YES) saveChanges();
                else vm.cancel();
            }
        });
    }

    // ══════════════════════════════════════════════════
    //  Shape 에디터
    // ══════════════════════════════════════════════════

    private void buildShapeEditor() {
        shapeSpinners.clear();
        axisNameFields.clear();
        shapeEditorBox.getChildren().clear();

        int   rank  = vm.getRank();
        int[] shape = vm.getShape();

        if (rank == 0) {
            shapeEditorBox.getChildren().add(new Label("Scalar (rank 0)"));
            return;
        }
        for (int i = 0; i < rank; i++) {
            final int axis = i;
            Spinner<Integer> sp = new Spinner<>(1, 9999, shape[i]);
            sp.setPrefWidth(70); sp.setEditable(true);
            applyIntFilter(sp);
            sp.valueProperty().addListener((o, ov, nv) -> applyShape());
            shapeSpinners.add(sp);

            TextField nameTf = new TextField(vm.getAxisName(axis));
            nameTf.setPrefWidth(90); nameTf.setFont(Font.font(11));
            nameTf.setPromptText("axis_" + axis);
            axisNameFields.add(nameTf);

            Runnable commit = () -> {
                if (rebuilding) return;
                String n = nameTf.getText().trim();
                vm.setAxisName(axis, n.isEmpty() ? "axis_" + axis : n);
            };
            nameTf.setOnAction(e -> commit.run());
            nameTf.focusedProperty().addListener((o, ov, nv) -> { if (!nv) commit.run(); });

            HBox row = new HBox(6, new Label("Axis " + i), sp, nameTf);
            row.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(nameTf, Priority.ALWAYS);
            shapeEditorBox.getChildren().add(row);
        }
    }

    // ══════════════════════════════════════════════════
    //  축 선택 콤보
    // ══════════════════════════════════════════════════

    private void buildAxisSelectors() {
        boolean sel = vm.isAxisSelectable();
        rowAxisCombo.setDisable(!sel);
        colAxisCombo.setDisable(!sel);

        if (!sel) { fixedDimBox.getChildren().clear(); return; }

        int rank  = vm.getRank();
        int[] sh  = vm.getShape();
        rowAxisCombo.setOnAction(null);
        colAxisCombo.setOnAction(null);
        rowAxisCombo.getItems().clear();
        colAxisCombo.getItems().clear();
        for (int i = 0; i < rank; i++) {
            String lbl = "Axis " + i + ": " + vm.getAxisName(i) + "  (size " + sh[i] + ")";
            rowAxisCombo.getItems().add(lbl);
            colAxisCombo.getItems().add(lbl);
        }
        rowAxisCombo.getSelectionModel().select(vm.getRowAxis());
        colAxisCombo.getSelectionModel().select(vm.getColAxis());
        wireAxisComboActions();
        refreshFixedDimControls();
    }

    private void wireAxisComboActions() {
        rowAxisCombo.setOnAction(e -> {
            int i = rowAxisCombo.getSelectionModel().getSelectedIndex();
            if (i >= 0) { vm.setRowAxis(i); syncAxisCombos(); refreshFixedDimControls(); createGrid(); }
        });
        colAxisCombo.setOnAction(e -> {
            int i = colAxisCombo.getSelectionModel().getSelectedIndex();
            if (i >= 0) { vm.setColAxis(i); syncAxisCombos(); refreshFixedDimControls(); createGrid(); }
        });
    }

    private void syncAxisCombos() {
        rowAxisCombo.setOnAction(null);
        colAxisCombo.setOnAction(null);
        rowAxisCombo.getSelectionModel().select(vm.getRowAxis());
        colAxisCombo.getSelectionModel().select(vm.getColAxis());
        wireAxisComboActions();
    }

    // ══════════════════════════════════════════════════
    //  Fixed Indices
    // ══════════════════════════════════════════════════

    private void refreshFixedDimControls() {
        fixedDimBox.getChildren().clear();
        int   rank  = vm.getRank();
        int[] shape = vm.getShape();
        int[] fixed = vm.getFixedIndices();

        for (int axis = 0; axis < rank; axis++) {
            if (axis == vm.getRowAxis() || axis == vm.getColAxis()) continue;
            int dim = shape[axis];
            final int a = axis;

            Spinner<Integer> sp = new Spinner<>(0, Math.max(0, dim - 1), fixed[axis]);
            sp.setPrefWidth(65); sp.setEditable(true);
            applyIntFilter(sp);
            String label = "Axis " + axis + ": " + vm.getAxisName(axis) + "  [0‥" + (dim - 1) + "]";

            VBox block = new VBox(2);
            block.getChildren().add(new HBox(6, new Label(label), sp));

            if (dim > 1) {
            	Slider slider = new Slider(0, dim - 1, fixed[axis]);
                slider.getStyleClass().add("editor-slider");   // 회색 트랙 + 파란 thumb (main.css)
                slider.setBlockIncrement(1);
                slider.setMajorTickUnit(1);   // ← 정수마다 눈금
                slider.setMinorTickCount(0);  // ← 사이 눈금 없음
                slider.setSnapToTicks(true);
                sp.valueProperty().addListener((o, ov, nv) -> {
                    if (rebuilding) return;
                    if ((int) slider.getValue() != nv) slider.setValue(nv);
                    vm.setFixedIndex(a, nv); createGrid();
                });
                slider.valueProperty().addListener((o, ov, nv) -> {
                    int v = (int) Math.round(nv.doubleValue());
                    if (sp.getValue() != v) sp.getValueFactory().setValue(v);
                });
                block.getChildren().add(slider);
            } else {
                sp.valueProperty().addListener((o, ov, nv) -> { if (rebuilding) return; vm.setFixedIndex(a, nv); createGrid(); });
            }
            fixedDimBox.getChildren().add(block);
        }
    }

    // ══════════════════════════════════════════════════
    //  그리드 렌더링
    // ══════════════════════════════════════════════════

    public void createGrid() {
        double[][] slice = vm.getCurrentSlice();
        int rows = vm.getRows(), cols = vm.getCols(), rank = vm.getRank();
        gridBox.getChildren().clear();

        if (rank >= 2) {
            int rA = vm.getRowAxis(), cA = vm.getColAxis();
            int[] sh = vm.getShape();
            String info = "Axis " + rA + ": " + vm.getAxisName(rA) + " (size " + sh[rA] + ")"
                + "  ×  Axis " + cA + ": " + vm.getAxisName(cA) + " (size " + sh[cA] + ")";
            Label h = new Label(info);
            h.setFont(Font.font("SansSerif", FontPosture.ITALIC, 11));
            gridBox.getChildren().add(h);
        }

        GridPane g = new GridPane();
        g.setHgap(2); g.setVgap(2); g.setPadding(new Insets(2));
        g.setStyle("-fx-background-color:lightgray;");

        g.add(indexCell(""), 0, 0);
        for (int c = 0; c < cols; c++) g.add(indexCell(String.valueOf(c)), c + 1, 0);

        for (int r = 0; r < rows; r++) {
            g.add(indexCell(String.valueOf(r)), 0, r + 1);
            for (int c = 0; c < cols; c++) {
            	TextField tf = new TextField(formatVal(slice[r][c]));
                tf.setPrefSize(CELL_SIZE, CELL_SIZE);
                tf.setMinSize(CELL_SIZE, CELL_SIZE);
                tf.setMaxSize(CELL_SIZE, CELL_SIZE);
                tf.getStyleClass().add("tensor-cell");
                applyDoubleFilter(tf);
                final int fr = r, fc = c;
                tf.setOnAction(e -> updateCell(tf, fr, fc));
                tf.focusedProperty().addListener((o, ov, nv) -> { if (!nv) updateCell(tf, fr, fc); });
                g.add(tf, c + 1, r + 1);
            }
        }

        HBox center = new HBox(g);
        center.setAlignment(Pos.CENTER);
        gridBox.getChildren().add(center);
    }

    private Label indexCell(String text) {
        Label l = new Label(text);
        l.setPrefSize(CELL_SIZE, CELL_SIZE); l.setMinSize(CELL_SIZE, CELL_SIZE); l.setMaxSize(CELL_SIZE, CELL_SIZE);
        l.getStyleClass().add("tensor-index-cell");
        return l;
    }

    private void updateCell(TextField tf, int r, int c) {
        if (rebuilding) return;
        try {
            double val = Double.parseDouble(tf.getText());
            vm.setSliceValue(r, c, val);
            currentValueArea.setText(vm.getValueString());
            updateUndoRedo();
        } catch (NumberFormatException ignored) {}
    }

    // ══════════════════════════════════════════════════
    //  액션
    // ══════════════════════════════════════════════════

    private void commitNodeName() {
        if (rebuilding) return;
        String n = nodeNameField.getText().trim();
        if (!n.isEmpty()) vm.setDraftNodeName(n);
    }

    private void commitTensorName() {
        if (rebuilding) return;
        String n = tensorNameField.getText().trim();
        if (!n.isEmpty()) vm.setDraftTensorName(n);
    }

    private void applyTranspose() {
        if (vm.getRank() < 2) return;
        vm.transpose();
    }

    private void removeLastAxis() {
        if (vm.getRank() <= 1) { showWarning("Cannot remove axis: rank must be at least 1."); return; }
        vm.removeLastAxis();
    }

    private void applyValueString() {
        String input = inputValueArea.getText();
        if (input == null || input.trim().isEmpty()) return;
        try { vm.applyValueString(input); }
        catch (Exception ex) { showWarning(ex.getMessage()); }
    }

    private void applyShape() {
        if (rebuilding) return;
        if (shapeSpinners.isEmpty()) return;
        int[] s = new int[shapeSpinners.size()];
        for (int i = 0; i < s.length; i++) s[i] = shapeSpinners.get(i).getValue();
        try { vm.reshapeEditTensor(s); }
        catch (Exception ex) { showWarning("Shape change failed: " + ex.getMessage()); }
    }

    private void saveChanges() {
        try {
            commitNodeName();     // 필드에 남은 미커밋 텍스트를 draft로 반영
            commitTensorName();

            TensorNode node   = vm.getNode();
            Tensor before     = node.getTensor().makeCopy();   // save 직전 노드 상태
            String beforeName = node.getNodeName();

            vm.save();                                         // editTensor → node 커밋 (cascade 포함)

            Tensor after      = node.getTensor().makeCopy();   // save 직후 상태
            String afterName  = node.getNodeName();

            // 실제 변경이 있었고 리스너가 등록됐으면 캔버스 history에 1개 Command로 기록
            SaveListener listener = (sessionSaveListener != null) ? sessionSaveListener : saveListener;
            if (listener != null && isDifferent(before, beforeName, after, afterName)) {
                listener.onTensorSaved(node, before, beforeName, after, afterName);
            }
        } catch (Exception ex) { showWarning("Save failed: " + ex.getMessage()); }
    }

    /** save 전후 상태가 실제로 다른지 (값·shape·축이름·텐서이름·노드이름). */
    private static boolean isDifferent(Tensor a, String aName, Tensor b, String bName) {
        if (!aName.equals(bName)) return true;
        if (!a.getName().equals(b.getName())) return true;
        if (!a.equalValue(b)) return true;   // shape + data
        return !java.util.Arrays.equals(a.getAxisNames(), b.getAxisNames());
    }

    private void doUndo() { vm.undo(); }   // notify → deferred 풀 리빌드
    private void doRedo() { vm.redo(); }

    private void updateUndoRedo() {
        undoBtn.setDisable(!vm.canUndo());
        redoBtn.setDisable(!vm.canRedo());
    }

    // ══════════════════════════════════════════════════
    //  전체 갱신
    // ══════════════════════════════════════════════════

    /** 에디터 채널 알림에 대응하는 풀 리빌드 (focus-lost 오염 방지 가드). */
    private void rebuildEditorUI() {
        if (rebuilding) return;
        rebuilding = true;
        try {
            refreshAll();
        } finally {
            rebuilding = false;
        }
    }

    private void refreshAll() {
        nodeNameField.setText(vm.getDraftNodeName());
        tensorNameField.setText(vm.getEditTensor().getName());
        kindLabel.setText(vm.getEditKind().name());
        currentValueArea.setText(vm.getValueString());
        inputValueArea.setText("");
        transposeBtn.setDisable(vm.getRank() < 2);
        buildShapeEditor();
        buildAxisSelectors();
        createGrid();
        updateUndoRedo();
    }

    // ══════════════════════════════════════════════════
    //  유틸
    // ══════════════════════════════════════════════════

    /**
     * 스피너 에디터를 양의 정수만 입력 가능하도록 제한한다.
     * 빈 문자열(전체 선택 후 타이핑 직전)은 허용해야 자연스럽게 동작한다.
     */
    private static void applyIntFilter(Spinner<Integer> sp) {
        sp.getEditor().setTextFormatter(new javafx.scene.control.TextFormatter<>(change ->
            change.getControlNewText().matches("\\d*") ? change : null));
    }

    /**
     * 텍스트필드를 실수(음수·소수점·지수 표기 포함)만 입력 가능하도록 제한한다.
     * 부분 입력 중간 상태("-", "1.", "1e", "1e-" 등)도 허용해서 자연스럽게 동작한다.
     */
    private static void applyDoubleFilter(TextField tf) {
        tf.setTextFormatter(new javafx.scene.control.TextFormatter<>(change ->
            change.getControlNewText().matches("-?\\d*\\.?\\d*([eE][+-]?\\d*)?") ? change : null));
    }

    private static String formatVal(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.valueOf(v);
    }

    private void showWarning(String msg) {
        Alert a = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
        a.setHeaderText(null); a.initOwner(stage); a.showAndWait();
    }

    private static VBox sectionCard(String title, Node content) {
        Label lbl = new Label(title);
        lbl.getStyleClass().add("editor-card-title");
        VBox card = new VBox(4, lbl, content);
        card.getStyleClass().add("editor-card");
        card.setPadding(new Insets(8, 10, 10, 10));
        VBox.setVgrow(content, Priority.ALWAYS);
        return card;
    }

    private static ScrollPane styledScroll(Node content, int maxH) {
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setStyle("-fx-border-color:" + BORDER_CLR + ";");
        if (maxH > 0) { sp.setMaxHeight(maxH); sp.setPrefHeight(maxH); }
        return sp;
    }

    private static ColumnConstraints grow() {
        ColumnConstraints c = new ColumnConstraints(); c.setHgrow(Priority.ALWAYS); return c;
    }

    private static TextField makeTextField(int cols) {
        TextField tf = new TextField(); tf.setPrefColumnCount(cols);
        tf.setFont(Font.font("SansSerif", 12)); return tf;
    }
    private static Button makePrimBtn(String text) {
        Button b = new Button(text);
        b.getStyleClass().addAll("editor-btn", "editor-btn-primary");
        b.setPadding(new Insets(6, 14, 6, 14));
        b.setFocusTraversable(false);   // 클릭 시 포커스 안 잡음 (포커스 테두리 떠도는 문제 방지)
        return b;
    }
    private static Button makeSecBtn(String text) {
        Button b = new Button(text);
        b.getStyleClass().addAll("editor-btn", "editor-btn-secondary");
        b.setPadding(new Insets(6, 14, 6, 14));
        b.setFocusTraversable(false);
        return b;
    }
    private static Button makeSmBtn(String text) {
        Button b = new Button(text);
        b.getStyleClass().add("editor-btn-small");
        b.setPadding(new Insets(4, 10, 4, 10));
        b.setFocusTraversable(false);
        return b;
    }
    private static Label barLabel(String text) {
        Label l = new Label(text);
        l.setFont(Font.font("SansSerif", 12)); l.setTextFill(Color.web(LABEL_FG)); return l;
    }
}
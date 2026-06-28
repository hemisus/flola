package com.hemisus.flola.ui;

import com.hemisus.flola.model.ConnectionModel;
import com.hemisus.flola.model.GenericOperationNode;
import com.hemisus.flola.model.Graph;
import com.hemisus.flola.model.GraphNode;
import com.hemisus.flola.model.Tensor;
import com.hemisus.flola.utils.TensorOperations;
import com.hemisus.flola.viewmodel.NodeViewModel;
import com.hemisus.flola.viewmodel.OperationViewModel;
import com.hemisus.flola.controller.GraphCommands.ConnSnapshot;

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
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javafx.scene.text.FontPosture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OperationEditorStage {

    private static final String BG         = "#f5f4f1";
    private static final String BORDER_CLR = "#e0dfd9";
    private static final String ACCENT     = "#4A7CBF";
    private static final String SEC_BTN    = "#e8e7e1";
    private static final String LABEL_FG   = "#5a5a52";
    private static final String TITLE_FG   = "#989688";

    private static final int CELL_SIZE = 45;
    private static final int MINI_CELL      = 36;
    private static final int MAX_DISP       = 8;
    private static final int MAX_INPUT_SHOW = 5;

    private boolean perspectiveMode = false;
    private Tensor  perspRefTensor  = null;
    private int     perspRowAxis    = 0;
    private int     perspColAxis    = 1;
    private int[]   perspFixedIndices = new int[0];
    private boolean perspBuilding   = false;

    private Label      centerTitle;
    private ScrollPane outputScroll, perspectiveScroll;
    private VBox       perspectiveBox;
    
    private final OperationViewModel vm;
    private final Graph graph;
    private final Stage stage;
    private final boolean editableName;

    private TextField nameField;
    private VBox paramBox, inputConnBox, outputConnBox, gridBox;
    private Button applyBtn, cancelBtn, changeViewBtn, showVisualBtn;
    private Button undoBtn, redoBtn;
    private StackPane centerStack;
    
    
    /** 풀 리빌드 중 옛 컨트롤의 focus-lost가 undo 스택을 오염시키는 것을 막는 재진입 가드. */
    private boolean rebuilding = false;

    private final List<SliceState> sliceStates = new ArrayList<>();
    // ── Visual 상태 ───────────────────────────────────
    private boolean isProjectionNode = false, isSVDNode = false, visualMode = false;
    private ProjectionVisualPane projectionVisualPane;
    private SVDVisualPane        svdVisualPane;
    private Pane                 visualPane;
    // ── 슬라이스 상태 (rank≥3 출력 미리보기용) ─────────
    private static final class SliceState {
        int rowAxis, colAxis;
        int[] fixedIndices;
        SliceState(int rank) {
            fixedIndices = new int[Math.max(rank, 0)];
            rowAxis = rank >= 2 ? rank - 2 : 0;
            colAxis = rank >= 1 ? rank - 1 : 0;
        }
        void clampTo(int[] shape) {
            int rank = shape.length;
            if (rank < 2) { rowAxis = 0; colAxis = 0; }
            else {
                rowAxis = Math.min(rowAxis, rank - 1);
                colAxis = Math.min(colAxis, rank - 1);
                if (rowAxis == colAxis) colAxis = (rowAxis == rank - 1) ? rank - 2 : rank - 1;
            }
            int[] nf = new int[rank];
            for (int i = 0; i < rank; i++) {
                int prev = i < fixedIndices.length ? fixedIndices[i] : 0;
                nf[i] = Math.max(0, Math.min(prev, shape[i] - 1));
            }
            fixedIndices = nf;
        }
    }

    // ── 진입점 ────────────────────────────────────────
    /**
     * Apply 1회를 캔버스 undo history에 통합하기 위한 콜백 (옵션 A).
     * MainController가 등록하며, Apply 직전/직후 노드 확정 상태를 받아 Command로 기록한다.
     */
    public interface SaveListener {
        void onOperationSaved(OperationViewModel vm,
                              OperationViewModel.CommittedState before,
                              OperationViewModel.CommittedState after,
                              List<ConnSnapshot> beforeConns,
                              List<ConnSnapshot> afterConns);
    }
    private static SaveListener saveListener;
    public static void setSaveListener(SaveListener l) { saveListener = l; }
    /** 이 세션 전용 리스너 (open 시 주입). null이면 static saveListener로 폴백. */
    private final SaveListener sessionSaveListener;
    private List<ConnSnapshot> captureConnections() {
        List<ConnSnapshot> list = new ArrayList<>();
        for (ConnectionModel c : graph.getInputsFor(vm.getNode())) {
            list.add(new ConnSnapshot(c.getSource(), c.getSourcePortIndex(), c.getTarget(), c.getTargetPortIndex()));
        }
        for (ConnectionModel c : graph.getOutputsFrom(vm.getNode())) {
            list.add(new ConnSnapshot(c.getSource(), c.getSourcePortIndex(), c.getTarget(), c.getTargetPortIndex()));
        }
        return list;
    }
    /** 캔버스 인스턴스 등 이름 편집이 의미 있는 경우 (기본). */
    public static void open(NodeViewModel vm, Graph graph) {
        open(vm, graph, true);
    }

    /** editableName=false → 사이드바 템플릿처럼 노드 이름 편집을 막는다. */
    public static void open(NodeViewModel vm, Graph graph, boolean editableName) {
        if (vm instanceof OperationViewModel ovm) new OperationEditorStage(ovm, graph, editableName, null);
    }

    /**
     * 세션 리스너를 지정해 연다. Apply 1회를 <b>이 리스너</b>로 보낸다(없으면 static 폴백).
     * 서브에디터에서 열린 연산 노드의 Apply를 그 에디터의 editorUndo로 라우팅하기 위함.
     */
    public static void open(NodeViewModel vm, Graph graph, SaveListener sessionListener) {
        if (vm instanceof OperationViewModel ovm) new OperationEditorStage(ovm, graph, true, sessionListener);
    }

    private OperationEditorStage(OperationViewModel vm, Graph graph, boolean editableName) {
        this(vm, graph, editableName, null);
    }

    private OperationEditorStage(OperationViewModel vm, Graph graph, boolean editableName, SaveListener sessionListener) {
        this.vm = vm;
        this.graph = graph;
        this.editableName = editableName;
        this.sessionSaveListener = sessionListener;
        this.stage = new Stage();
        stage.setTitle("Operation Editor  —  " + vm.getNodeName());
        stage.setWidth(1080); stage.setHeight(700);
        stage.setMinWidth(820); stage.setMinHeight(520);

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:" + BG + ";");
        root.setTop(buildTopBar());
        Node leftPanel  = buildLeftPanel();
        Node centerPane = buildCenter();
        if (centerPane instanceof Region r) r.setMinWidth(360);
        SplitPane split = new SplitPane(leftPanel, centerPane);
        SplitPane.setResizableWithParent(leftPanel, false);   // 창 리사이즈 시 패널 폭 유지, 결과영역이 흡수
        split.setDividerPositions(0.27);                       // 메인 화면과 동일하게 경계 드래그로 조절
        root.setCenter(split);

        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource(
            "/com/hemisus/flola/css/main.css").toExternalForm());
        scene.getAccelerators().put(
            new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN), this::doUndo);
        scene.getAccelerators().put(
            new KeyCodeCombination(KeyCode.Y, KeyCombination.SHORTCUT_DOWN), this::doRedo);
        scene.getAccelerators().put(
            new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN), this::doRedo);
        stage.setScene(scene);

        setupListeners();
        populateNodeInfo();
        populateConnectionInfo();
        populateParameters();
        createGrid();
        setupVisualIfNeeded();
        updateUndoRedo();
        stage.show();
    }

    // ══════════════════════════════════════════════════
    //  상단 바
    // ══════════════════════════════════════════════════

    private HBox buildTopBar() {
        nameField = new TextField();
        nameField.setPrefColumnCount(16);
        // 열자마자 이름 필드가 포커스를 가로채면 첫 Ctrl+Z가 텍스트 undo로 먹히므로 자동 포커스 방지
        // (마우스 클릭으로는 정상 포커스됨)
        nameField.setFocusTraversable(false);
        if (!editableName) {
            nameField.setEditable(false);
            nameField.setDisable(true);
            nameField.setTooltip(new Tooltip("You can edit NodeName after placing instance on the canvas"));
        }

        HBox info = new HBox(8, barLabel("Node:"), nameField);
        info.setAlignment(Pos.CENTER_LEFT);

        changeViewBtn = makeSecBtn("Change Perspective");
        showVisualBtn = makeSecBtn("Show Visual");
        showVisualBtn.setVisible(false); showVisualBtn.setManaged(false);   // 5d-3에서 활성화
        
        cancelBtn = makeSecBtn("Cancel");
        applyBtn  = makePrimBtn("Apply");
        undoBtn = makeSecBtn("\u21B6 Undo");
        redoBtn = makeSecBtn("\u21B7 Redo");
        HBox btns = new HBox(8, showVisualBtn, changeViewBtn,
            new Separator(Orientation.VERTICAL), undoBtn, redoBtn,
            new Separator(Orientation.VERTICAL), cancelBtn, applyBtn);
        btns.setAlignment(Pos.CENTER_RIGHT);

        HBox bar = new HBox(info, btns);
        HBox.setHgrow(info, Priority.ALWAYS);
        bar.setPadding(new Insets(10, 16, 10, 16));
        bar.setStyle("-fx-background-color:#fafaf7; -fx-border-color:transparent transparent "
            + BORDER_CLR + " transparent; -fx-border-width:0 0 1 0;");
        return bar;
    }

    // ══════════════════════════════════════════════════
    //  왼쪽 패널 (Parameters / Input / Output Connections)
    // ══════════════════════════════════════════════════

    private ScrollPane buildLeftPanel() {
        paramBox      = new VBox(4);
        inputConnBox  = new VBox(4);
        outputConnBox = new VBox(4);

        VBox left = new VBox(8,
            sectionCard("Parameters",         styledScroll(paramBox, 150)),
            sectionCard("Input Connections",  styledScroll(inputConnBox, 170)),
            sectionCard("Output Connections", styledScroll(outputConnBox, 130)));
        left.setPadding(new Insets(10, 6, 10, 10));
        left.setStyle("-fx-background-color:" + BG + ";");

        ScrollPane outer = new ScrollPane(left);
        outer.setFitToWidth(true);
        outer.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        outer.setStyle("-fx-background:" + BG + ";");
        outer.setPrefWidth(290); outer.setMinWidth(260);
        return outer;
    }

    // ══════════════════════════════════════════════════
    //  중앙 (Output 그리드) — 카드 스택 (5d-2/5d-3 확장용)
    // ══════════════════════════════════════════════════

    private Node buildCenter() {
        gridBox = new VBox(8);
        gridBox.setPadding(new Insets(10));
        gridBox.setStyle("-fx-background-color:white;");
        outputScroll = new ScrollPane(gridBox);
        outputScroll.setFitToWidth(true);

        perspectiveBox = new VBox(14);
        perspectiveBox.setPadding(new Insets(12));
        perspectiveBox.setStyle("-fx-background-color:white;");
        perspectiveScroll = new ScrollPane(perspectiveBox);
        perspectiveScroll.setFitToWidth(true);
        perspectiveScroll.setVisible(false);
        perspectiveScroll.setManaged(false);

        centerStack = new StackPane(outputScroll, perspectiveScroll);

        centerTitle = new Label("Operation Result (Preview)");
        centerTitle.getStyleClass().add("editor-card-title");
        VBox card = new VBox(4, centerTitle, centerStack);
        card.getStyleClass().add("editor-card");
        card.setPadding(new Insets(8, 10, 10, 10));
        VBox.setVgrow(centerStack, Priority.ALWAYS);

        VBox outer = new VBox(card);
        outer.setPadding(new Insets(10, 6, 10, 6));
        VBox.setVgrow(card, Priority.ALWAYS);
        return outer;
    }

    private void showCard(String which) {
        boolean persp = "perspective".equals(which);
        boolean vis   = "visual".equals(which);
        boolean out   = !persp && !vis;
        outputScroll.setVisible(out);        outputScroll.setManaged(out);
        perspectiveScroll.setVisible(persp); perspectiveScroll.setManaged(persp);
        if (visualPane != null) { visualPane.setVisible(vis); visualPane.setManaged(vis); }
        centerTitle.setText(vis ? "Visualization" : persp ? "Computation Flow" : "Operation Result (Preview)");
    }

    // ══════════════════════════════════════════════════
    //  파라미터
    // ══════════════════════════════════════════════════

    private void populateParameters() {
        paramBox.getChildren().clear();
        if (!(vm.getNode() instanceof GenericOperationNode node)) return;
        String op = node.getOperationType();

        switch (op) {
            case "Concatenate" -> {
                ComboBox<String> cb = axisCombo((Integer) vm.getEditParam("axis"), 0);
                cb.setOnAction(e -> { setParam("axis", cb.getSelectionModel().getSelectedIndex()); });
                paramBox.getChildren().add(paramRow("Axis:", cb));
            }
            case "Softmax" -> {
                ComboBox<String> cb = axisCombo((Integer) vm.getEditParam("axis"), 1);
                cb.setOnAction(e -> setParam("axis", cb.getSelectionModel().getSelectedIndex()));
                paramBox.getChildren().add(paramRow("Axis:", cb));
            }
            case "Split" -> {
                ComboBox<String> axisCb = axisCombo((Integer) vm.getEditParam("axis"), 0);
                Spinner<Integer> valSp = intSpinner(1, 100, editIntParam("value", 1));
                ComboBox<String> typeCb = new ComboBox<>();
                typeCb.getItems().addAll("NUM_CHUNKS", "CHUNK_SIZE");
                Object ct = vm.getEditParam("type");
                typeCb.getSelectionModel().select(
                    (ct instanceof TensorOperations.SplitType st && st == TensorOperations.SplitType.CHUNK_SIZE) ? 1 : 0);

                axisCb.setOnAction(e -> setParam("axis", axisCb.getSelectionModel().getSelectedIndex()));
                typeCb.setOnAction(e -> setParam("type",
                    typeCb.getSelectionModel().getSelectedIndex() == 0
                        ? TensorOperations.SplitType.NUM_CHUNKS : TensorOperations.SplitType.CHUNK_SIZE));
                valSp.valueProperty().addListener((o, ov, nv) -> setParam("value", nv));

                paramBox.getChildren().addAll(
                    paramRow("Axis:", axisCb), paramRow("Type:", typeCb), paramRow("Value:", valSp));
            }
            case "Conv2D", "ConvTranspose2D" -> {
                int defStride = op.equals("ConvTranspose2D") ? 2 : 1;
                Spinner<Integer> strideSp  = intSpinner(1, 16, editIntParam("stride", defStride));
                Spinner<Integer> paddingSp = intSpinner(0, 16, editIntParam("padding", 0));
                strideSp.valueProperty().addListener((o, ov, nv) -> setParam("stride", nv));
                paddingSp.valueProperty().addListener((o, ov, nv) -> setParam("padding", nv));
                paramBox.getChildren().addAll(paramRow("Stride:", strideSp), paramRow("Padding:", paddingSp));
            }
            case "MaxPool2D" -> {
                Spinner<Integer> ksSp     = intSpinner(1, 16, editIntParam("kernel_size", 2));
                Spinner<Integer> strideSp = intSpinner(1, 16, editIntParam("stride", 2));
                ksSp.valueProperty().addListener((o, ov, nv) -> setParam("kernel_size", nv));
                strideSp.valueProperty().addListener((o, ov, nv) -> setParam("stride", nv));
                paramBox.getChildren().addAll(paramRow("Kernel:", ksSp), paramRow("Stride:", strideSp));
            }
            case "Upsample" -> {
                Spinner<Integer> shSp = intSpinner(1, 16, editIntParam("scale_h", 2));
                Spinner<Integer> swSp = intSpinner(1, 16, editIntParam("scale_w", 2));
                ComboBox<String> modeCb = new ComboBox<>();
                modeCb.getItems().addAll("nearest", "bilinear");
                Object cm = vm.getEditParam("mode");
                modeCb.getSelectionModel().select(cm != null ? (String) cm : "nearest");
                shSp.valueProperty().addListener((o, ov, nv) -> setParam("scale_h", nv));
                swSp.valueProperty().addListener((o, ov, nv) -> setParam("scale_w", nv));
                modeCb.setOnAction(e -> setParam("mode", modeCb.getSelectionModel().getSelectedItem()));
                paramBox.getChildren().addAll(
                    paramRow("Scale H:", shSp), paramRow("Scale W:", swSp), paramRow("Mode:", modeCb));
            }
            case "View" -> {
                String cur = (String) vm.getEditParam("shape");
                TextField tf = new TextField(cur != null ? cur : "-1");
                tf.setPrefColumnCount(12);
                Runnable apply = () -> {
                    if (rebuilding) return;   // 리빌드 중 tf teardown의 focus-lost 재호출 차단
                    String text = tf.getText().trim();
                    try {
                        for (String s : text.split(",")) Integer.parseInt(s.trim());
                        setParam("shape", text);
                    } catch (NumberFormatException ex) {
                        showWarning("Invalid shape. e.g. \"1,-1\" or \"2,3,4\".");
                        tf.setText(cur != null ? cur : "-1");
                    }
                };
                tf.setOnAction(e -> apply.run());
                tf.focusedProperty().addListener((o, ov, nv) -> { if (!nv) apply.run(); });
                paramBox.getChildren().addAll(paramRow("Shape:", tf),
                    paramRow("", hint("e.g.  1,-1  /  2,3,4  /  -1")));
            }
            default -> paramBox.getChildren().add(hint("No parameters."));
        }
    }

    /** 파라미터 변경 → 임시 출력 재계산 → 그리드 갱신 */
    private void setParam(String key, Object value) {
        if (rebuilding) return;   // 리빌드 중 옛 컨트롤의 focus-lost 재호출 차단
        try {
            vm.setEditParam(key, value, graph.getIncomingTensors(vm.getNode()));
            createGrid();
            updateUndoRedo();
        } catch (Exception ex) {
            showWarning(ex.getMessage());
        }
    }

    // ══════════════════════════════════════════════════
    //  입출력 포트 재배치
    // ══════════════════════════════════════════════════

    private void populateConnectionInfo() {
        GraphNode node = vm.getNode();
        inputConnBox.getChildren().clear();
        outputConnBox.getChildren().clear();

        List<ConnectionModel> ins  = graph.getInputsFor(node);
        List<ConnectionModel> outs = graph.getOutputsFrom(node);

        if (ins.isEmpty()) inputConnBox.getChildren().add(hint("No incoming connections."));
        else for (ConnectionModel c : ins) inputConnBox.getChildren().add(inputRow(c, node.getInputPortCount()));

        if (outs.isEmpty()) outputConnBox.getChildren().add(hint("No outgoing connections."));
        else for (ConnectionModel c : outs) outputConnBox.getChildren().add(outputRow(c, node.getOutputPortCount()));
    }

    private HBox inputRow(ConnectionModel conn, int maxPort) {
        int maxIdx = Math.max(0, maxPort - 1);
        int ei = Math.max(0, Math.min(vm.getEffectiveInputPort(conn), maxIdx));
        Spinner<Integer> sp = intSpinner(0, maxIdx, ei);
        sp.setPrefWidth(60);
        sp.valueProperty().addListener((o, ov, nv) -> {
            if (rebuilding) return;   // 리빌드 중 스피너 재생성에 의한 재호출 차단
            vm.setTempInputPort(conn, nv, buildDraftInputs(conn, nv));
            populateConnectionInfo();
            createGrid();
            updateUndoRedo();
        });
        Label tgt = new Label("← " + conn.getSource().getNodeName() + " (Port " + conn.getSourcePortIndex() + ")");
        HBox row = new HBox(4, new Label("Port"), sp, tgt);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private HBox outputRow(ConnectionModel conn, int maxPort) {
        int maxIdx = Math.max(0, maxPort - 1);
        int ei = Math.max(0, Math.min(vm.getEffectiveOutputPort(conn), maxIdx));
        Spinner<Integer> sp = intSpinner(0, maxIdx, ei);
        sp.setPrefWidth(60);
        sp.valueProperty().addListener((o, ov, nv) -> {
            if (rebuilding) return;   // 리빌드 중 스피너 재생성에 의한 재호출 차단
            vm.setTempOutputPort(conn, nv); populateConnectionInfo(); updateUndoRedo();
        });
        Label tgt = new Label("→ " + conn.getTarget().getNodeName() + " (Port " + conn.getTargetPortIndex() + ")");
        HBox row = new HBox(4, new Label("Port"), sp, tgt);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private List<Tensor> buildDraftInputs(ConnectionModel changed, int newIdx) {
        List<ConnectionModel> all = graph.getInputsFor(vm.getNode());
        int count = vm.getNode().getInputPortCount();
        List<Tensor> inputs = new ArrayList<>(Collections.nCopies(count, null));
        for (ConnectionModel c : all) {
            int idx = c.equals(changed) ? newIdx : vm.getEffectiveInputPort(c);
            if (idx >= 0 && idx < count) inputs.set(idx, c.getSource().getOutputValue(c.getSourcePortIndex()));
        }
        return inputs;
    }

    private void applyPendingPortReassignments() {
        Map<OperationViewModel.InputConnKey, Integer> im = vm.getTempInputPortMap();
        Map<OperationViewModel.OutputConnKey, Integer> om = vm.getTempOutputPortMap();

        if (!im.isEmpty()) {
            Map<ConnectionModel, Integer> inputMap = new HashMap<>();
            for (ConnectionModel c : graph.getInputsFor(vm.getNode())) {
                OperationViewModel.InputConnKey key = new OperationViewModel.InputConnKey(c.getSource(), c.getSourcePortIndex());
                if (im.containsKey(key)) inputMap.put(c, im.get(key));
            }
            graph.batchReassignInputPorts(vm.getNode(), inputMap);
        }

        if (!om.isEmpty()) {
            Map<ConnectionModel, Integer> outputMap = new HashMap<>();
            for (ConnectionModel c : graph.getOutputsFrom(vm.getNode())) {
                OperationViewModel.OutputConnKey key = new OperationViewModel.OutputConnKey(c.getTarget(), c.getTargetPortIndex());
                if (om.containsKey(key)) outputMap.put(c, om.get(key));
            }
            graph.batchReassignOutputPorts(vm.getNode(), outputMap);
        }
    }

    // ══════════════════════════════════════════════════
    //  출력 그리드 미리보기
    // ══════════════════════════════════════════════════

    private void createGrid() {
        gridBox.getChildren().clear();
        List<Tensor> tensors = vm.getTempOutputs();
        if (tensors == null || tensors.isEmpty() || tensors.get(0) == null) {
            gridBox.getChildren().add(hint("No results to preview. Check inputs or parameters."));
            return;
        }
        syncSliceStates(tensors);
        for (int i = 0; i < tensors.size(); i++) {
            Tensor t = tensors.get(i);
            if (t == null) continue;
            gridBox.getChildren().add(resultPanel(t, i, sliceStates.get(i)));
            if (i < tensors.size() - 1) gridBox.getChildren().add(new Separator());
        }
    }

    private void syncSliceStates(List<Tensor> tensors) {
        while (sliceStates.size() < tensors.size()) {
            Tensor t = tensors.get(sliceStates.size());
            sliceStates.add(new SliceState(t != null ? t.getRank() : 1));
        }
        while (sliceStates.size() > tensors.size()) sliceStates.remove(sliceStates.size() - 1);
        for (int i = 0; i < tensors.size(); i++) {
            Tensor t = tensors.get(i);
            if (t != null) sliceStates.get(i).clampTo(t.getShape());
        }
    }

    private Node resultPanel(Tensor t, int idx, SliceState state) {
        Label header = new Label("Result [" + idx + "]  " + t.getKind().name() + "  " + t.getSummary());
        header.setFont(Font.font("SansSerif", FontWeight.BOLD, 12));

        HBox body = new HBox(12);
        body.setAlignment(Pos.TOP_LEFT);
        if (t.getRank() >= 3) body.getChildren().add(axisControl(t, state));
        body.getChildren().add(sliceGrid(t, state));

        VBox wrap = new VBox(6, header, body);
        wrap.setPadding(new Insets(6, 6, 12, 6));
        return wrap;
    }

    private Node axisControl(Tensor t, SliceState state) {
        int rank = t.getRank();
        int[] shape = t.getShape();
        String[] labels = new String[rank];
        for (int i = 0; i < rank; i++) labels[i] = "Axis " + i + "  (size " + shape[i] + ")";

        ComboBox<String> rowCb = new ComboBox<>(); rowCb.getItems().addAll(labels);
        ComboBox<String> colCb = new ComboBox<>(); colCb.getItems().addAll(labels);
        rowCb.getSelectionModel().select(state.rowAxis);
        colCb.getSelectionModel().select(state.colAxis);

        VBox fixedBox = new VBox(3);
        Runnable refreshFixed = () -> {
            fixedBox.getChildren().clear();
            for (int a = 0; a < rank; a++) {
                if (a == state.rowAxis || a == state.colAxis) continue;
                final int ax = a;
                Spinner<Integer> sp = intSpinner(0, shape[ax] - 1, state.fixedIndices[ax]);
                sp.setPrefWidth(60);
                sp.valueProperty().addListener((o, ov, nv) -> { state.fixedIndices[ax] = nv; createGrid(); });
                HBox r = new HBox(4, new Label("Ax" + ax + ":"), sp);
                r.setAlignment(Pos.CENTER_LEFT);
                fixedBox.getChildren().add(r);
            }
        };
        rowCb.setOnAction(e -> {
            state.rowAxis = rowCb.getSelectionModel().getSelectedIndex();
            if (state.rowAxis == state.colAxis) { state.colAxis = otherAxis(state.rowAxis, rank); colCb.getSelectionModel().select(state.colAxis); }
            refreshFixed.run(); createGrid();
        });
        colCb.setOnAction(e -> {
            state.colAxis = colCb.getSelectionModel().getSelectedIndex();
            if (state.rowAxis == state.colAxis) { state.rowAxis = otherAxis(state.colAxis, rank); rowCb.getSelectionModel().select(state.rowAxis); }
            refreshFixed.run(); createGrid();
        });
        refreshFixed.run();

        VBox p = new VBox(4, new Label("Row Axis:"), rowCb, new Label("Col Axis:"), colCb,
            new Label("Fixed Indices:"), fixedBox);
        p.setPrefWidth(180);
        return p;
    }

    private int otherAxis(int occupied, int rank) {
        for (int i = 0; i < rank; i++) if (i != occupied) return i;
        return 0;
    }

    private Node sliceGrid(Tensor t, SliceState state) {
        int rank = t.getRank();
        double[][] slice = (rank < 2) ? t.to2DArray() : t.to2DArray(state.rowAxis, state.colAxis, state.fixedIndices);
        int rows = slice.length, cols = rows > 0 ? slice[0].length : 0;

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
                tf.setEditable(false);
                tf.getStyleClass().add("tensor-cell");
                g.add(tf, c + 1, r + 1);
            }
        }
        return g;
    }

    private Label indexCell(String text) {
        Label l = new Label(text);
        l.setPrefSize(CELL_SIZE, CELL_SIZE); l.setMinSize(CELL_SIZE, CELL_SIZE); l.setMaxSize(CELL_SIZE, CELL_SIZE);
        l.getStyleClass().add("tensor-index-cell");
        return l;
    }

    // ══════════════════════════════════════════════════
    //  리스너 / 저장
    // ══════════════════════════════════════════════════

    private void setupListeners() {
        // 노드 이름 — Enter / 포커스 해제 시 draft에 반영 (편집 가능할 때만)
        if (editableName) {
            nameField.setOnAction(e -> commitDraftName());
            nameField.focusedProperty().addListener((o, ov, nv) -> { if (!nv) commitDraftName(); });
        }

        applyBtn.setOnAction(e -> {
            commitDraftName(); 
            OperationViewModel.CommittedState beforeState = vm.captureCommittedState();
            List<ConnSnapshot> beforeConns = captureConnections(); // 변경 전 선 연결 정보

            applyPendingPortReassignments();
            vm.save();
            vm.getTempInputPortMap().clear();
            vm.getTempOutputPortMap().clear();
            
            OperationViewModel.CommittedState afterState = vm.captureCommittedState();
            List<ConnSnapshot> afterConns = captureConnections(); // 변경 후 선 연결 정보

            // 실제 변경이 있을 때만 캔버스 Undo History로 넘김
            SaveListener listener = (sessionSaveListener != null) ? sessionSaveListener : saveListener;
            if (listener != null) {
                boolean stateChanged = !beforeState.equals(afterState);
                boolean connsChanged = !beforeConns.equals(afterConns);
                if (stateChanged || connsChanged) {
                    listener.onOperationSaved(vm, beforeState, afterState, beforeConns, afterConns);
                }
            }
        });
        cancelBtn.setOnAction(e -> vm.cancel());   // notify → deferred 풀 리빌드
        undoBtn.setOnAction(e -> doUndo());
        redoBtn.setOnAction(e -> doRedo());
        changeViewBtn.setOnAction(e -> togglePerspective());
        showVisualBtn.setOnAction(e -> toggleVisual());
        stage.setOnCloseRequest(e -> vm.cancel());
        
        // 에디터 채널 구독 (drag 같은 고빈도 변경은 핸들러가 직접 createGrid 하므로
        // 여기 오지 않는다). undo/redo/cancel/save/외부 캐스케이드만 풀 리빌드.
        // deferred + rebuilding 가드로 focus-lost 스택 오염을 막는다.
        vm.addEditorListener(() -> Platform.runLater(this::rebuildEditorUI));
    }

    /**
     * 이름·파라미터·연결·그리드를 모두 다시 그린다.
     * populateParameters가 옛 컨트롤을 teardown 할 때 발생하는 focus-lost가
     * vm 세터를 재호출해 스택을 오염시키지 않도록 rebuilding 플래그로 가드한다.
     */
    private void rebuildEditorUI() {
        if (rebuilding) return;
        rebuilding = true;
        try {
            populateNodeInfo();
            populateParameters();
            populateConnectionInfo();
            createGrid();
            if (perspectiveMode) buildPerspectiveView();
            if (visualMode && isProjectionNode) updateProjectionVisual();
            if (visualMode && isSVDNode)        updateSVDVisual();
            updateUndoRedo();
        } finally {
            rebuilding = false;
        }
    }

    private void populateNodeInfo() { nameField.setText(vm.getDraftNodeName()); }

    private void doUndo() {
        if (!vm.canUndo()) return;
        vm.undo();                                       // → 에디터 채널 notify → deferred 풀 리빌드 예약
        vm.recomputePreview(buildCurrentDraftInputs());  // deferred 리빌드 전에 동기 실행 → 그리드가 최신 출력 반영
    }

    private void doRedo() {
        if (!vm.canRedo()) return;
        vm.redo();
        vm.recomputePreview(buildCurrentDraftInputs());
    }

    private void updateUndoRedo() {
        undoBtn.setDisable(!vm.canUndo());
        redoBtn.setDisable(!vm.canRedo());
    }

    private void commitDraftName() {
        if (!editableName) return;
        if (rebuilding) return;   // 리빌드 중 nameField focus-lost 재호출 차단
        String name = nameField.getText().trim();
        if (!name.isEmpty()) vm.setDraftNodeName(name);
        updateUndoRedo();
    }

    /** 현재 draft 포트 매핑을 반영한 입력 텐서 목록 */
    private List<Tensor> buildCurrentDraftInputs() {
        List<ConnectionModel> all = graph.getInputsFor(vm.getNode());
        int count = vm.getNode().getInputPortCount();
        List<Tensor> inputs = new ArrayList<>(Collections.nCopies(count, null));
        for (ConnectionModel c : all) {
            int idx = vm.getEffectiveInputPort(c);
            if (idx >= 0 && idx < count)
                inputs.set(idx, c.getSource().getOutputValue(c.getSourcePortIndex()));
        }
        return inputs;
    }

    /** editParams에서 정수 파라미터를 읽는다 (undo 후에도 draft 값 반영) */
    private int editIntParam(String key, int def) {
        Object v = vm.getEditParam(key);
        return v instanceof Integer i ? i : def;
    }
 // ══════════════════════════════════════════════════
    //  Perspective (계산 흐름) 뷰
    // ══════════════════════════════════════════════════
    
    private void togglePerspective() {
        if (visualMode) { visualMode = false; showVisualBtn.setText("Show Visual"); }
        perspectiveMode = !perspectiveMode;
        if (perspectiveMode) {
            perspRefTensor = null;
            buildPerspectiveView();
            showCard("perspective");
            changeViewBtn.setText("Show Output Grid");
        } else {
            showCard("output");
            changeViewBtn.setText("Change Perspective");
        }
    }

    private void buildPerspectiveView() {
        if (perspBuilding) return;
        perspBuilding = true;
        try {
            perspectiveBox.getChildren().clear();

            List<Tensor> inputs  = graph.getIncomingTensors(vm.getNode());
            List<Tensor> outputs = vm.getTempOutputs();
            String opType = vm.getOperationType();

            initSharedAxisState();
            if (perspRefTensor != null)
                perspectiveBox.getChildren().add(buildSharedAxisControlPanel());

            long nonNull = inputs.stream().filter(t -> t != null).count();
            Node content = (nonNull <= 2)
                ? buildHorizontalFormulaView(inputs, outputs, opType)
                : buildVerticalFlowView(inputs, outputs, opType);
            perspectiveBox.getChildren().add(content);
        } finally {
            perspBuilding = false;
        }
    }
 // ══════════════════════════════════════════════════
    //  Visual 패널 (Projection / SVD)
    // ══════════════════════════════════════════════════

    private void setupVisualIfNeeded() {
        String op = vm.getOperationType();
        if ("Projection".equals(op)) {
            isProjectionNode = true;
            projectionVisualPane = new ProjectionVisualPane();
            visualPane = projectionVisualPane;
        } else if ("SVD".equals(op)) {
            isSVDNode = true;
            svdVisualPane = new SVDVisualPane();
            visualPane = svdVisualPane;
        }
        if (visualPane != null) {
            visualPane.setVisible(false); visualPane.setManaged(false);
            centerStack.getChildren().add(visualPane);
            showVisualBtn.setVisible(true); showVisualBtn.setManaged(true);
            showVisualBtn.setText("Show Visual");
        }
    }

    private void toggleVisual() {
        visualMode = !visualMode;
        if (visualMode) {
            if (isProjectionNode) updateProjectionVisual();
            if (isSVDNode)        updateSVDVisual();
            showCard("visual");
            showVisualBtn.setText("Hide Visual");
        } else {
            showCard(perspectiveMode ? "perspective" : "output");
            showVisualBtn.setText("Show Visual");
        }
    }

    private void updateProjectionVisual() {
        if (projectionVisualPane == null) return;
        List<Tensor> inputs  = graph.getIncomingTensors(vm.getNode());
        List<Tensor> outputs = vm.getTempOutputs();
        Tensor v      = inputs.size()  > 0 ? inputs.get(0)  : null;
        Tensor target = inputs.size()  > 1 ? inputs.get(1)  : null;
        Tensor proj   = (outputs != null && outputs.size() > 0) ? outputs.get(0) : null;
        Tensor orth   = (outputs != null && outputs.size() > 1) ? outputs.get(1) : null;
        projectionVisualPane.update(v, target, proj, orth);
    }

    private void updateSVDVisual() {
        if (svdVisualPane == null) return;
        List<Tensor> inputs  = graph.getIncomingTensors(vm.getNode());
        List<Tensor> outputs = vm.getTempOutputs();
        Tensor input = (inputs != null && !inputs.isEmpty()) ? inputs.get(0) : null;
        svdVisualPane.update(input, outputs);
    }
    // ── 공유 축 상태 (rank≥3 입력 기준) ────────────────
    private void initSharedAxisState() {
        Tensor ref = graph.getIncomingTensors(vm.getNode()).stream()
            .filter(t -> t != null && t.getRank() >= 3)
            .findFirst().orElse(null);
        if (ref == null) { perspRefTensor = null; return; }
        if (perspRefTensor == null || perspRefTensor.getRank() != ref.getRank()) {
            int rank = ref.getRank();
            perspRowAxis = rank - 2;
            perspColAxis = rank - 1;
            perspFixedIndices = new int[rank];
        }
        perspRefTensor = ref;
    }

    private Node buildSharedAxisControlPanel() {
        int rank = perspRefTensor.getRank();
        int[] shape = perspRefTensor.getShape();
        String[] labels = new String[rank];
        for (int i = 0; i < rank; i++)
            labels[i] = "Axis " + i + ": " + perspRefTensor.getAxisName(i) + "  (size " + shape[i] + ")";

        ComboBox<String> rowCb = new ComboBox<>(); rowCb.getItems().addAll(labels);
        ComboBox<String> colCb = new ComboBox<>(); colCb.getItems().addAll(labels);
        rowCb.getSelectionModel().select(perspRowAxis);
        colCb.getSelectionModel().select(perspColAxis);

        HBox fixedPanel = new HBox(6);
        fixedPanel.setAlignment(Pos.CENTER_LEFT);
        Runnable refreshFixed = () -> {
            fixedPanel.getChildren().clear();
            for (int axis = 0; axis < rank; axis++) {
                if (axis == perspRowAxis || axis == perspColAxis) continue;
                final int a = axis;
                int max = shape[axis] - 1;
                int cur = (a < perspFixedIndices.length) ? Math.max(0, Math.min(perspFixedIndices[a], max)) : 0;
                Spinner<Integer> sp = intSpinner(0, max, cur);
                sp.setPrefWidth(60);
                sp.valueProperty().addListener((o, ov, nv) -> {
                    if (a < perspFixedIndices.length) perspFixedIndices[a] = nv;
                    buildPerspectiveView();
                });
                fixedPanel.getChildren().addAll(new Label(perspRefTensor.getAxisName(axis) + ":"), sp);
            }
        };
        rowCb.setOnAction(e -> {
            perspRowAxis = rowCb.getSelectionModel().getSelectedIndex();
            if (perspRowAxis == perspColAxis) {
                perspColAxis = (perspRowAxis == rank - 1) ? rank - 2 : rank - 1;
                colCb.getSelectionModel().select(perspColAxis);
            }
            refreshFixed.run(); buildPerspectiveView();
        });
        colCb.setOnAction(e -> {
            perspColAxis = colCb.getSelectionModel().getSelectedIndex();
            if (perspRowAxis == perspColAxis) {
                perspRowAxis = (perspColAxis == rank - 1) ? rank - 2 : rank - 1;
                rowCb.getSelectionModel().select(perspRowAxis);
            }
            refreshFixed.run(); buildPerspectiveView();
        });
        refreshFixed.run();

        HBox panel = new HBox(10, new Label("View axes (rank ≥ 3):"),
            new Label("Row:"), rowCb, new Label("Col:"), colCb, fixedPanel);
        panel.setAlignment(Pos.CENTER_LEFT);
        panel.setPadding(new Insets(4, 8, 4, 8));
        panel.setStyle("-fx-background-color:#f4f3ef; -fx-border-color:#d8d6cf; "
            + "-fx-border-radius:4; -fx-background-radius:4;");
        return panel;
    }

    // ── 가로 수식 레이아웃 (입력 ≤ 2) ──────────────────
    private Node buildHorizontalFormulaView(List<Tensor> inputs, List<Tensor> outputs, String opType) {
        HBox row = new HBox(16);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().add(buildTensorCard(labelFor(inputs, 0, true), safeGet(inputs, 0), true));
        row.getChildren().add(buildOperatorLabel(opType));
        if (safeGet(inputs, 1) != null)
            row.getChildren().add(buildTensorCard(labelFor(inputs, 1, true), safeGet(inputs, 1), true));
        row.getChildren().add(makeSymbolLabel("="));
        if (outputs != null)
            for (int i = 0; i < outputs.size(); i++) {
                Tensor t = outputs.get(i);
                if (t != null) row.getChildren().add(buildTensorCard(labelFor(outputs, i, false), t, false));
            }
        return row;
    }

    // ── 세로 흐름 레이아웃 (입력 ≥ 3) ──────────────────
    private Node buildVerticalFlowView(List<Tensor> inputs, List<Tensor> outputs, String opType) {
        HBox panel = new HBox(30);
        panel.setAlignment(Pos.CENTER_LEFT);
        panel.getChildren().addAll(
            buildTensorColumn("Inputs", inputs, true),
            buildArrowPanel(opType),
            buildTensorColumn("Outputs", outputs, false));
        return panel;
    }

    private Node buildTensorColumn(String title, List<Tensor> tensors, boolean isInput) {
        VBox col = new VBox(12);
        Label t = new Label(title);
        t.setFont(Font.font("SansSerif", FontWeight.BOLD, 13));
        col.getChildren().add(t);
        if (tensors == null || tensors.isEmpty()) { col.getChildren().add(hint("(no tensors)")); return col; }

        int displayCount = isInput ? Math.min(tensors.size(), MAX_INPUT_SHOW) : tensors.size();
        for (int i = 0; i < displayCount; i++) {
            String prefix = (isInput ? "Input Port " : "Output Port ") + i;
            Tensor tt = tensors.get(i);
            String lbl = tt == null ? prefix : prefix + "  " + tt.getName();
            col.getChildren().add(buildTensorCard(lbl, tt, isInput));
        }
        if (isInput && tensors.size() > MAX_INPUT_SHOW)
            col.getChildren().add(hint("+ " + (tensors.size() - MAX_INPUT_SHOW) + " more inputs…"));
        return col;
    }

    // ── 텐서 카드 ──────────────────────────────────────
    private Node buildTensorCard(String label, Tensor tensor, boolean isInput) {
        VBox card = new VBox(4);
        card.setPadding(new Insets(6, 8, 6, 8));
        card.setStyle("-fx-background-color:white; -fx-border-color:#c9c7be; "
            + "-fx-border-radius:4; -fx-background-radius:4;");

        String shapeStr;
        if (isInput && tensor != null) {
            String[] names = tensor.getAxisNames();
            int[] shape = tensor.getShape();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < names.length; i++) {
                if (i > 0) sb.append(" × ");
                sb.append(names[i]).append("(").append(shape[i]).append(")");
            }
            shapeStr = sb.toString();
        } else {
            shapeStr = tensor == null ? "" : tensor.getSummary();
        }
        Label header = new Label(label + (tensor == null ? "" : "  [" + shapeStr + "]"));
        header.setFont(Font.font("SansSerif", FontWeight.BOLD, 11));
        card.getChildren().add(header);
        if (tensor != null) card.getChildren().add(buildMiniGrid(tensor));
        return card;
    }

    // ── 미니 그리드 (8×8 초과 시 … 잘림) ───────────────
    private Node buildMiniGrid(Tensor tensor) {
        double[][] slice = computeSlice(tensor);
        int rawRows = slice.length;
        int rawCols = rawRows > 0 ? slice[0].length : 0;
        boolean truncRow = rawRows > MAX_DISP, truncCol = rawCols > MAX_DISP;
        int dispRows = Math.min(rawRows, MAX_DISP), dispCols = Math.min(rawCols, MAX_DISP);

        GridPane grid = new GridPane();
        grid.setHgap(1); grid.setVgap(1); grid.setPadding(new Insets(1));
        grid.setStyle("-fx-background-color:#b4b4be;");

        grid.add(miniHeaderCell(""), 0, 0);
        for (int c = 0; c < dispCols; c++) grid.add(miniHeaderCell(String.valueOf(c)), c + 1, 0);
        if (truncCol) grid.add(miniHeaderCell("…"), dispCols + 1, 0);

        for (int r = 0; r < dispRows; r++) {
            grid.add(miniHeaderCell(String.valueOf(r)), 0, r + 1);
            for (int c = 0; c < dispCols; c++) {
                Label cell = new Label(formatVal(slice[r][c]));
                cell.setPrefSize(MINI_CELL, MINI_CELL); cell.setMinSize(MINI_CELL, MINI_CELL);
                cell.setAlignment(Pos.CENTER);
                cell.setStyle("-fx-background-color:white; -fx-font-size:10;");
                grid.add(cell, c + 1, r + 1);
            }
            if (truncCol) grid.add(miniEllipsisCell(), dispCols + 1, r + 1);
        }
        if (truncRow) {
            grid.add(miniHeaderCell("…"), 0, dispRows + 1);
            for (int c = 0; c < dispCols; c++) grid.add(miniEllipsisCell(), c + 1, dispRows + 1);
            if (truncCol) grid.add(miniEllipsisCell(), dispCols + 1, dispRows + 1);
        }

        if (truncRow || truncCol) {
            Label note = new Label("(showing " + dispRows + "×" + dispCols + " of " + rawRows + "×" + rawCols + ")");
            note.setFont(Font.font("SansSerif", FontPosture.ITALIC, 9));
            note.setTextFill(Color.GRAY);
            return new VBox(2, grid, note);
        }
        return grid;
    }

    private double[][] computeSlice(Tensor tensor) {
        int rank = tensor.getRank();
        if (rank < 2 || perspRefTensor == null || rank == 2) return tensor.to2DArray();
        int ra = Math.min(perspRowAxis, rank - 1);
        int ca = Math.min(perspColAxis, rank - 1);
        if (ra == ca) ca = (ra == rank - 1) ? rank - 2 : rank - 1;
        int[] fixed = new int[rank];
        for (int i = 0; i < rank; i++) {
            int prev = i < perspFixedIndices.length ? perspFixedIndices[i] : 0;
            fixed[i] = Math.max(0, Math.min(prev, tensor.getDim(i) - 1));
        }
        return tensor.to2DArray(ra, ca, fixed);
    }

    private Label miniHeaderCell(String text) {
        Label l = new Label(text);
        l.setPrefSize(MINI_CELL, MINI_CELL); l.setMinSize(MINI_CELL, MINI_CELL);
        l.setAlignment(Pos.CENTER);
        l.setStyle("-fx-background-color:#d2d2d7; -fx-font-weight:bold; -fx-font-size:9;");
        return l;
    }
    private Label miniEllipsisCell() {
        Label l = new Label("…");
        l.setPrefSize(MINI_CELL, MINI_CELL); l.setMinSize(MINI_CELL, MINI_CELL);
        l.setAlignment(Pos.CENTER);
        l.setStyle("-fx-background-color:#ebebeb; -fx-font-size:9;");
        return l;
    }

    // ── 연산자 기호 / 화살표 ───────────────────────────
    private Node buildOperatorLabel(String opType) {
        String symbol = switch (opType) {
            case "Matrix Multiply"      -> "×";
            case "Add"                  -> "+";
            case "Subtract"             -> "−";
            case "Elementwise Multiply" -> "⊙";
            case "Divide"               -> "÷";
            case "Transpose"            -> "ᵀ";
            default -> opType;
        };
        return makeSymbolLabel(symbol);
    }
    private Label makeSymbolLabel(String text) {
        Label l = new Label(text);
        l.setFont(Font.font("SansSerif", FontWeight.BOLD, 24));
        l.setTextFill(Color.web("#3C64B4"));
        return l;
    }
    private Node buildArrowPanel(String opType) {
        Label op = new Label(opType);
        op.setFont(Font.font("SansSerif", FontWeight.BOLD, 13));
        op.setTextFill(Color.web("#3C64B4"));
        Label arrow = new Label("──────►");
        arrow.setFont(Font.font("SansSerif", 18));
        arrow.setTextFill(Color.web("#505050"));
        VBox p = new VBox(6, op, arrow);
        p.setAlignment(Pos.CENTER);
        return p;
    }

    private static String labelFor(List<Tensor> tensors, int i, boolean isInput) {
        Tensor t = safeGet(tensors, i);
        String prefix = (isInput ? "In:" : "Out:") + i;
        return t == null ? prefix + "  (none)" : prefix + "  " + t.getName();
    }
    private static Tensor safeGet(List<Tensor> list, int i) {
        return (list != null && i < list.size()) ? list.get(i) : null;
    }
    // ══════════════════════════════════════════════════
    //  헬퍼
    // ══════════════════════════════════════════════════

    private static ComboBox<String> axisCombo(Integer cur, int def) {
        ComboBox<String> cb = new ComboBox<>();
        cb.getItems().addAll("Row (0)", "Column (1)");
        cb.getSelectionModel().select(cur != null ? cur : def);
        return cb;
    }

    private static Spinner<Integer> intSpinner(int min, int max, int val) {
        Spinner<Integer> sp = new Spinner<>(min, max, Math.max(min, Math.min(val, max)));
        sp.setEditable(true);
        return sp;
    }

    private static HBox paramRow(String label, Node control) {
        HBox row = new HBox(6, new Label(label), control);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Label hint(String text) {
        Label l = new Label(text);
        l.setTextFill(Color.web("#9a9788"));
        l.setFont(Font.font("SansSerif", 11));
        l.setWrapText(true);
        return l;
    }

    private static String formatVal(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.format("%.3f", v);
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
    private static Label barLabel(String text) {
        Label l = new Label(text);
        l.setTextFill(Color.web(LABEL_FG)); return l;
    }
}
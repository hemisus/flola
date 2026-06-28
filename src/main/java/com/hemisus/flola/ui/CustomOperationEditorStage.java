package com.hemisus.flola.ui;

import com.hemisus.flola.event.ShapeChangeListener;
import com.hemisus.flola.model.*;
import com.hemisus.flola.model.factory.NodeFactory;
import com.hemisus.flola.utils.DataConverter;
import com.hemisus.flola.utils.DialogHelper;
import com.hemisus.flola.viewmodel.*;

import com.hemisus.flola.controller.CanvasContext;
import com.hemisus.flola.controller.GraphCommand;
import com.hemisus.flola.controller.GraphCommands;
import com.hemisus.flola.controller.GraphCommands.ConnSnapshot;
import com.hemisus.flola.controller.GraphUndoManager;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.geometry.Side;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Custom Operation 편집 창 — 별도 Stage(비모달).
 * vm.getEditSubGraph()(draft)를 캔버스 작업 대상으로 쓰고,
 * Apply→vm.save() / Close·X→vm.cancel(). OperationEditorStage와 동일 팔레트.
 */
public class CustomOperationEditorStage implements CanvasContext {

    private static final String BG         = "#f5f4f1";
    private static final String BORDER_CLR = "#e0dfd9";
    private static final String ACCENT     = "#4A7CBF";
    private static final String LABEL_FG   = "#5a5a52";

    private final CustomOperationViewModel  vm;
    private final Graph                     parentGraph;          // null = template mode
    private final List<OperationNode>       builtinOpTemplates;
    private final List<CustomOperationNode> customOpTemplates;
    private final Consumer<CustomOperation> onOperationChanged;
    private final ShapeChangeListener       shapeListener;

    private final Stage      stage;
    private final CanvasPane canvasPane = new CanvasPane();
    private final Map<GraphNode, NodeViewModel> viewModelMap = new HashMap<>();
    private boolean isUpdatingCascade = false;

    // ── 에디터-내부 Undo/Redo (열기~Apply/Cancel 구간의 단일 타임라인) ──
    private final GraphUndoManager editorUndo = new GraphUndoManager();
    /** 드래그 시작 시점의 선택 노드 위치 스냅샷 (MoveNodesCommand 생성용). */
    private final Map<NodeView, GraphCommands.Pos> preDragPositions = new HashMap<>();
    /** 풀 리빌드/undo 적용 중 focus-lost·spinner 재설정이 스택을 오염시키는 것을 막는 가드. */
    private boolean rebuilding = false;

    private TextField operationNameField, nodeNameField;
    private Label     statusLabel;
    private Button    undoBtn, redoBtn;
    private int spawnX = 60, spawnY = 60;

    private VBox extInputBox, extOutputBox;

    /** CustomOperation당 단일 편집 세션 — 같은 operation이면 새 창 대신 기존 창을 focus. (operation UUID 기준)
     *  같은 operation은 서브그래프를 공유하므로, 창이 둘이면 서로의 draft가 충돌·clobber된다. */
    private static final Map<String, CustomOperationEditorStage> OPEN = new HashMap<>();
    private final String operationUuid;

    /** Apply 1회 → 캔버스 history에 CustomOperationEditCommand 1개로 통합하기 위한 콜백. */
    public interface SaveListener {
        void onCustomOperationSaved(CustomOperationViewModel vm,
                                    CustomOperationViewModel.CommittedSubGraph before,
                                    CustomOperationViewModel.CommittedSubGraph after,
                                    List<ConnSnapshot> beforeConns,
                                    List<ConnSnapshot> afterConns);
    }
    private static SaveListener saveListener;
    public static void setSaveListener(SaveListener l) { saveListener = l; }
    /** 이 세션 전용 리스너 (open 시 주입). null이면 static saveListener로 폴백. */
    private final SaveListener sessionSaveListener;

    public static void open(CustomOperationViewModel vm,
                            Graph parentGraph,
                            List<OperationNode> builtinOpTemplates,
                            List<CustomOperationNode> customOpTemplates,
                            Consumer<CustomOperation> onOperationChanged,
                            ShapeChangeListener shapeListener) {
        open(vm, parentGraph, builtinOpTemplates, customOpTemplates, onOperationChanged, shapeListener, null);
    }

    /**
     * 세션 리스너를 지정해 연다. Apply 1회를 <b>이 리스너</b>로 보낸다(없으면 static 폴백).
     * 서브에디터에서 열린 중첩 custom op의 Apply를 그 에디터의 editorUndo로 라우팅하기 위함.
     */
    public static void open(CustomOperationViewModel vm,
                            Graph parentGraph,
                            List<OperationNode> builtinOpTemplates,
                            List<CustomOperationNode> customOpTemplates,
                            Consumer<CustomOperation> onOperationChanged,
                            ShapeChangeListener shapeListener,
                            SaveListener sessionListener) {
        String uuid = vm.getOperation().getUuid();
        CustomOperationEditorStage existing = OPEN.get(uuid);
        if (existing != null) {                 // 같은 operation 세션이 이미 열림 → 그 창을 앞으로
            existing.stage.toFront();
            existing.stage.requestFocus();
            return;
        }
        new CustomOperationEditorStage(vm, parentGraph, builtinOpTemplates,
            customOpTemplates, onOperationChanged, shapeListener, sessionListener);
    }

    private CustomOperationEditorStage(CustomOperationViewModel vm, Graph parentGraph,
                                       List<OperationNode> builtinOpTemplates,
                                       List<CustomOperationNode> customOpTemplates,
                                       Consumer<CustomOperation> onOperationChanged,
                                       ShapeChangeListener shapeListener,
                                       SaveListener sessionListener) {
        this.vm                 = vm;
        this.parentGraph        = parentGraph;
        this.builtinOpTemplates = (builtinOpTemplates != null) ? builtinOpTemplates : new ArrayList<>();
        this.customOpTemplates  = (customOpTemplates  != null) ? customOpTemplates  : new ArrayList<>();
        this.onOperationChanged = onOperationChanged;
        this.shapeListener      = shapeListener;
        this.operationUuid      = vm.getOperation().getUuid();
        this.sessionSaveListener = sessionListener;

        stage = new Stage();
        stage.setTitle("Custom Operation Editor  —  " + vm.getNodeName());
        stage.setWidth(1080); stage.setHeight(700);
        stage.setMinWidth(820); stage.setMinHeight(520);

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:" + BG + ";");
        root.setTop(buildTopBar());
        Node extPanel = buildExternalPortsPanel();
        canvasPane.setMinWidth(360);
        SplitPane split = new SplitPane(extPanel, canvasPane);
        SplitPane.setResizableWithParent(extPanel, false);   // 창 리사이즈 시 패널 폭 유지, 캔버스가 흡수
        split.setDividerPositions(0.27);                      // 메인 화면과 동일하게 경계 드래그로 조절
        root.setCenter(split);
        root.setBottom(buildBottomBar());

        Scene scene = new Scene(root);
        var css = getClass().getResource("/com/hemisus/flola/css/main.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        // 키 처리는 scene 레벨 필터로 (메인 캔버스와 동일). 텍스트 입력 중에는 가로채지 않음.
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, this::handleCanvasKey);
        stage.setScene(scene);
        vm.syncFromNode();
        setupCanvas();
        initNameFields();
        setupNameFieldListeners();
        restoreCanvas();
        populateExternalPorts();
        updateUndoRedo();

        stage.setOnCloseRequest(e -> { if (!confirmCloseAndDispose()) e.consume(); });
        stage.setOnHidden(e -> OPEN.remove(operationUuid));   // 닫히면 세션 등록 해제
        OPEN.put(operationUuid, this);                        // 단일 세션 등록
        stage.show();
        // 첫 레이아웃 패스에서 빈 캔버스의 pref-width(≈0) 때문에 divider가 좌측으로 무너지는 것 방지:
        // Stage가 실제 폭을 가진 뒤 divider를 확정한다.
        Platform.runLater(() -> {
            split.setDividerPositions(0.27);
            canvasPane.requestFocus();   // 이름 필드 자동 포커스 방지 → Ctrl+Z가 처음부터 동작
        });
    }

    private Graph editGraph() { return vm.getEditSubGraph(); }

    // ── 상단 바 ───────────────────────────────────────
    private HBox buildTopBar() {
        operationNameField = new TextField(); operationNameField.setPrefColumnCount(14);
        nodeNameField      = new TextField(); nodeNameField.setPrefColumnCount(14);

        HBox info = new HBox(8, barLabel("Operation:"), operationNameField,
                                barLabel("Node:"),      nodeNameField);
        info.setAlignment(Pos.CENTER_LEFT);

        Button addInput  = makeSecBtn("+ Input");
        Button addOutput = makeSecBtn("+ Output");
        Button addOp     = makeSecBtn("+ Operation");
        Button addTensor = makeSecBtn("+ Tensor");
        addInput .setOnAction(e -> addInputDialog());
        addOutput.setOnAction(e -> addOutputDialog());
        addOp    .setOnAction(e -> showAddOperationMenu(addOp));
        addTensor.setOnAction(e -> addTensorDialog());

        Button closeBtn = makeSecBtn("Close");
        Button applyBtn = makePrimBtn("Apply");
        closeBtn.setOnAction(e -> { if (confirmCloseAndDispose()) stage.close(); });
        applyBtn.setOnAction(e -> applyChanges());

        undoBtn = makeSecBtn("\u21B6 Undo");
        redoBtn = makeSecBtn("\u21B7 Redo");
        undoBtn.setOnAction(e -> doUndo());
        redoBtn.setOnAction(e -> doRedo());

        HBox btns = new HBox(8, addInput, addOutput, addOp, addTensor,
            new Separator(Orientation.VERTICAL), undoBtn, redoBtn,
            new Separator(Orientation.VERTICAL), closeBtn, applyBtn);
        btns.setAlignment(Pos.CENTER_RIGHT);

        HBox bar = new HBox(info, btns);
        HBox.setHgrow(info, Priority.ALWAYS);
        bar.setPadding(new Insets(10, 16, 10, 16));
        bar.setStyle("-fx-background-color:#fafaf7; -fx-border-color:transparent transparent "
            + BORDER_CLR + " transparent; -fx-border-width:0 0 1 0;");
        return bar;
    }

    private HBox buildBottomBar() {
        statusLabel = new Label("Ready");
        statusLabel.setTextFill(Color.web(LABEL_FG));
        HBox bar = new HBox(statusLabel);
        bar.setPadding(new Insets(6, 12, 6, 12));
        bar.setStyle("-fx-background-color:#fafaf7; -fx-border-color:" + BORDER_CLR
            + " transparent transparent transparent; -fx-border-width:1 0 0 0;");
        return bar;
    }

    private void initNameFields() {
        // 열 때 이름 필드가 포커스를 가로채면 첫 Ctrl+Z가 텍스트 undo로 먹히고,
        // 포커스 해제 시 가짜 커밋이 기록되므로 자동 포커스 방지 (마우스 클릭으로는 정상 포커스됨)
        operationNameField.setFocusTraversable(false);
        nodeNameField.setFocusTraversable(false);

        operationNameField.setText(vm.getOperation().getName());
        if (parentGraph == null) {              // 템플릿 모드: operation 이름 편집
            nodeNameField.setText("(template)");
            nodeNameField.setDisable(true);
        } else {                                // 캔버스 모드: 인스턴스 이름 편집
            operationNameField.setDisable(true);
            applyInstanceNameField(vm.getCustomNode().getInstanceName());
        }
    }

    /**
     * 인스턴스 이름 필드를 채운다. null이면 빈 필드 + operation 이름을 placeholder로 표시한다.
     * (operation 이름을 실제 텍스트로 채우면 draft(null)와 불일치해 가짜 커밋이 기록되므로 promptText 사용)
     */
    private void applyInstanceNameField(String instanceName) {
        if (instanceName != null) {
            nodeNameField.setText(instanceName);
        } else {
            nodeNameField.clear();
            nodeNameField.setPromptText(vm.getOperation().getName());
        }
    }

    /** 이름 필드 Enter/포커스 해제 시 draft에 커밋 + DraftStateCommand 기록. */
    private void setupNameFieldListeners() {
        if (parentGraph == null) {              // 템플릿 모드: operation 이름만 편집 가능
            operationNameField.setOnAction(e -> commitOperationName());
            operationNameField.focusedProperty().addListener((o, ov, nv) -> { if (!nv) commitOperationName(); });
        } else {                                // 캔버스 모드: 인스턴스 이름만 편집 가능
            nodeNameField.setOnAction(e -> commitInstanceName());
            nodeNameField.focusedProperty().addListener((o, ov, nv) -> { if (!nv) commitInstanceName(); });
        }
    }

    private void commitOperationName() {
        if (rebuilding || parentGraph != null) return;
        String n = operationNameField.getText().trim();
        if (n.isEmpty() || n.equals(vm.getEditName())) return;
        var before = vm.captureDraftSnapshot();
        vm.setEditName(n);
        editorUndo.record(new DraftStateCommand(before, vm.captureDraftSnapshot()));
        updateUndoRedo();
    }

    private void commitInstanceName() {
        if (rebuilding || parentGraph == null) return;
        String n = nodeNameField.getText().trim();
        String normalized = n.isEmpty() ? null : n;
        if (java.util.Objects.equals(normalized, vm.getDraftInstanceName())) return;
        var before = vm.captureDraftSnapshot();
        vm.setDraftInstanceName(normalized);
        editorUndo.record(new DraftStateCommand(before, vm.captureDraftSnapshot()));
        updateUndoRedo();
    }

    // Canvas
    private void setupCanvas() {
        canvasPane.setFocusTraversable(true);   // 키보드 포커스를 받을 수 있게 (Ctrl+Z/Delete 등)
        canvasPane.setOnConnect(this::handleConnectionRequest);
        canvasPane.getConnectionLayer().setOnConnectionRemoved(this::handleConnectionRemoval);
        editGraph().setRemovalListener((removed, affected) -> {
            for (GraphNode child : affected)
                if (child instanceof OperationNode) updateCascade(child);
        });
    }

    private void restoreCanvas() {
        Graph eg = editGraph();
        for (GraphNode node : eg.getAllNodes()) {
            NodeViewModel nvm = createOrGetViewModel(node);
            if (nvm == null) continue;
            NodeView nv = new NodeView(nvm);
            Graph.NodePosition pos = eg.getNodePosition(node);
            double x, y;
            if (pos != null) { x = pos.x(); y = pos.y(); }
            else { x = spawnX; y = spawnY; advanceSpawn(); }
            canvasPane.addNode(nv, x, y);
            wireCanvasNode(nv);
        }
        for (NodeView nv : canvasPane.getNodes())
            rebuildVisualConnections(nv.getViewModel().getNode());
        for (GraphNode n : eg.getAllNodes())
            if (eg.getInputsFor(n).isEmpty()) updateCascade(n);
    }

    private void advanceSpawn() {
        spawnX += 40; spawnY += 40;
        if (spawnY > 420) { spawnY = 60; spawnX += 200; }
    }

    private NodeViewModel createOrGetViewModel(GraphNode node) {
        NodeViewModel existing = viewModelMap.get(node);
        if (existing != null) return existing;
        NodeViewModel nvm = makeViewModel(node);
        if (nvm == null) return null;
        nvm.addListener(() -> handleNodeDataUpdate(nvm));
        viewModelMap.put(node, nvm);
        return nvm;
    }

    private NodeViewModel makeViewModel(GraphNode node) {
        if (node instanceof TensorNode tn) {
            if (shapeListener != null) tn.setShapeChangeListener(shapeListener);
            return new TensorViewModel(tn);
        }
        if (node instanceof CustomOperationNode cn) return new CustomOperationViewModel(cn);
        if (node instanceof UtilityNode un)         return new UtilityNodeViewModel(un);
        if (node instanceof OperationNode on)       return new OperationViewModel(on);
        return null;
    }

    private void wireCanvasNode(NodeView nv) {
        nv.setOnSelected(v -> {
            canvasPane.selectOnly(v);
            setStatus(v.getViewModel().getNodeName() + " selected");
        });
        nv.setOnNodePressed((v, shift) -> {
            if (shift) canvasPane.toggleSelection(v);
            else if (!canvasPane.isSelected(v)) canvasPane.selectOnly(v);
            // 드래그 이동 추적: 선택 갱신 후 현재 선택 노드들의 위치 스냅샷
            preDragPositions.clear();
            for (NodeView s : canvasPane.getSelectedNodes())
                preDragPositions.put(s, new GraphCommands.Pos(s.getLayoutX(), s.getLayoutY()));
        });
        nv.setOnDragDelta((v, d) -> canvasPane.moveSelectionBy(v, d.getX(), d.getY()));
        nv.setOnDragEnd(v -> commitMove());
        nv.setOnDoubleClicked(v -> {
            NodeViewModel m = v.getViewModel();
            if      (m instanceof TensorViewModel tvm)          TensorEditorStage.open(tvm, this::onNestedTensorSaved);
            else if (m instanceof CustomOperationViewModel)     setStatus("Custom operations can't be edited from inside another editor.");
            else if (m instanceof OperationViewModel ovm)       OperationEditorStage.open(ovm, editGraph(), this::onNestedOperationSaved);
            else if (m instanceof UtilityNodeViewModel uvm)     renameUtility(uvm);
        });
        nv.setOnRemove(v -> removeNodesRecorded(java.util.List.of(v)));
        nv.setOnRemoveSelected(this::deleteSelectedRecorded);
        nv.setSelectionCount(() -> canvasPane.getSelectedNodes().size());
        nv.layoutXProperty().addListener((o, ov, nw) -> canvasPane.getConnectionLayer().redraw());
        nv.layoutYProperty().addListener((o, ov, nw) -> canvasPane.getConnectionLayer().redraw());
    }

    private void placeNode(GraphNode node) {
        double x = spawnX, y = spawnY;
        editGraph().addNode(node);
        NodeViewModel nvm = createOrGetViewModel(node);
        if (nvm == null) return;
        NodeView nv = new NodeView(nvm);
        canvasPane.addNode(nv, x, y);
        editGraph().setNodePosition(node, (int) x, (int) y);
        advanceSpawn();
        wireCanvasNode(nv);
        editorUndo.record(new GraphCommands.AddNodeCommand(this, nv, x, y));
        updateUndoRedo();
    }

    // Connection
    private void handleConnectionRequest(Port from, Port to) {
        GraphNode src  = from.getOwner().getViewModel().getNode();
        GraphNode dest = to.getOwner().getViewModel().getNode();
        try {
            List<ConnectionModel> before = new ArrayList<>(editGraph().getInputsFor(dest));
            editGraph().connect(src, from.getIndex(), dest, to.getIndex());
            rebuildVisualConnections(dest);
            updateCascade(dest);
            // 새로 추가된 연결을 찾아 resolved 포트로 기록 (variadic 자동 할당 대응)
            ConnectionModel added = null;
            for (ConnectionModel c : editGraph().getInputsFor(dest))
                if (!before.contains(c)) { added = c; break; }
            if (added != null) {
                editorUndo.record(new GraphCommands.AddConnectionCommand(
                    this, added.getSource(), added.getSourcePortIndex(),
                    added.getTarget(), added.getTargetPortIndex()));
                updateUndoRedo();
            }
            setStatus("Connected: " + src.getNodeName() + " → " + dest.getNodeName() + "   (unsaved)");
        } catch (Exception ex) {
            DialogHelper.showWarning("Connection failed: " + ex.getMessage());
        }
    }

    private void handleConnectionRemoval(Connection c) {
        GraphNode dest = c.getTo().getOwner().getViewModel().getNode();
        int destPort   = c.getTo().getIndex();
        GraphNode src  = c.getFrom().getOwner().getViewModel().getNode();
        int srcPort    = c.getFrom().getIndex();
        editGraph().removeConnectionTo(dest, destPort);
        rebuildVisualConnections(dest);
        updateCascade(dest);
        editorUndo.record(new GraphCommands.RemoveConnectionCommand(this, src, srcPort, dest, destPort));
        updateUndoRedo();
        setStatus("Connection removed   (unsaved)");
    }

    private void rebuildVisualConnections(GraphNode node) {
        NodeView nv = findNodeView(node);
        if (nv == null) return;
        List<Connection> conns = new ArrayList<>();
        for (ConnectionModel cm : editGraph().getOutputsFrom(node)) {
            NodeView t = findNodeView(cm.getTarget());
            if (t == null) continue;
            conns.add(new Connection(
                new Port(nv, Port.Type.OUTPUT, cm.getSourcePortIndex()),
                new Port(t,  Port.Type.INPUT,  cm.getTargetPortIndex())));
        }
        for (ConnectionModel cm : editGraph().getInputsFor(node)) {
            NodeView s = findNodeView(cm.getSource());
            if (s == null) continue;
            conns.add(new Connection(
                new Port(s,  Port.Type.OUTPUT, cm.getSourcePortIndex()),
                new Port(nv, Port.Type.INPUT,  cm.getTargetPortIndex())));
        }
        canvasPane.getConnectionLayer().replaceConnectionsOf(nv, conns);
    }

    private NodeView findNodeView(GraphNode node) {
        for (NodeView nv : canvasPane.getNodes())
            if (nv.getViewModel().getNode() == node) return nv;
        return null;
    }

    private void removeCanvasNode(NodeView nv) {
        GraphNode node = nv.getViewModel().getNode();
        canvasPane.getConnectionLayer().removeConnectionsOf(nv);
        canvasPane.removeNode(nv);
        editGraph().removeNode(node);
        viewModelMap.remove(node);
        setStatus("Removed: " + node.getNodeName() + "   (unsaved)");
    }

    /** 선택 전체를 RemoveNodesCommand로 묶어 삭제·기록한다 (Delete 키). */
    private void deleteSelectedRecorded() {
        List<NodeView> sel = new ArrayList<>(canvasPane.getSelectedNodes());
        if (!sel.isEmpty()) removeNodesRecorded(sel);
    }

    /** 노드 묶음을 제거하고 RemoveNodesCommand로 editorUndo에 기록한다. */
    private void removeNodesRecorded(List<NodeView> sel) {
        if (sel.isEmpty()) return;
        // 제거 전에 위치·연결 스냅샷
        List<GraphCommands.Pos> positions = new ArrayList<>();
        for (NodeView nv : sel)
            positions.add(new GraphCommands.Pos(nv.getLayoutX(), nv.getLayoutY()));

        List<ConnSnapshot> conns = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (NodeView nv : sel) {
            GraphNode g = nv.getViewModel().getNode();
            for (ConnectionModel c : editGraph().getOutputsFrom(g)) addConnSnapshot(conns, seen, c);
            for (ConnectionModel c : editGraph().getInputsFor(g))   addConnSnapshot(conns, seen, c);
        }

        GraphCommand cmd = new GraphCommands.RemoveNodesCommand(this, sel, positions, conns);
        for (NodeView nv : sel) removeCanvasNode(nv);
        editorUndo.record(cmd);
        updateUndoRedo();
    }

    private void addConnSnapshot(List<ConnSnapshot> out, Set<String> seen, ConnectionModel c) {
        String key = System.identityHashCode(c.getSource()) + ":" + c.getSourcePortIndex()
                   + ">" + System.identityHashCode(c.getTarget()) + ":" + c.getTargetPortIndex();
        if (seen.add(key))
            out.add(new ConnSnapshot(c.getSource(), c.getSourcePortIndex(),
                                     c.getTarget(), c.getTargetPortIndex()));
    }

    /** 드래그 종료 시 실제로 움직인 노드들을 MoveNodesCommand로 기록한다. */
    private void commitMove() {
        if (preDragPositions.isEmpty()) return;
        List<NodeView> moved = new ArrayList<>();
        List<GraphCommands.Pos> from = new ArrayList<>();
        List<GraphCommands.Pos> to   = new ArrayList<>();
        for (Map.Entry<NodeView, GraphCommands.Pos> en : preDragPositions.entrySet()) {
            NodeView nv = en.getKey();
            GraphCommands.Pos p0 = en.getValue();
            double nx = nv.getLayoutX(), ny = nv.getLayoutY();
            if (p0.x() != nx || p0.y() != ny) {
                moved.add(nv);
                from.add(p0);
                to.add(new GraphCommands.Pos(nx, ny));
            }
        }
        preDragPositions.clear();
        if (!moved.isEmpty()) {
            editorUndo.record(new GraphCommands.MoveNodesCommand(this, moved, from, to));
            updateUndoRedo();
        }
    }

    private void updateCascade(GraphNode start) {
        if (isUpdatingCascade) return;
        try {
            isUpdatingCascade = true;
            for (GraphNode n : getSortedDownstream(start)) {
                if (n instanceof OperationNode op) editGraph().evaluateNode(op);
                NodeViewModel nvm = viewModelMap.get(n);
                if (nvm != null) { nvm.syncFromNode(); nvm.notifyListeners(); }
            }
        } finally {
            isUpdatingCascade = false;
        }
    }

    private List<GraphNode> getSortedDownstream(GraphNode start) {
        List<GraphNode> sorted = new ArrayList<>();
        Set<GraphNode> visited = new HashSet<>();
        dfsVisit(start, visited, sorted);
        Collections.reverse(sorted);
        return sorted;
    }

    private void dfsVisit(GraphNode n, Set<GraphNode> visited, List<GraphNode> out) {
        if (visited.contains(n)) return;
        visited.add(n);
        if (n instanceof OperationNode op) op.setDirty();
        for (ConnectionModel c : editGraph().getOutputsFrom(n)) dfsVisit(c.getTarget(), visited, out);
        out.add(n);
    }

    private void handleNodeDataUpdate(NodeViewModel nvm) {
        if (isUpdatingCascade) return;
        GraphNode node = nvm.getNode();
        int newOut = node.getOutputPortCount();
        Set<GraphNode> orphaned = new HashSet<>();
        for (ConnectionModel c : editGraph().getOutputsFrom(node))
            if (c.getSourcePortIndex() >= newOut) orphaned.add(c.getTarget());
        editGraph().sanitizeOutputConnections(node);
        rebuildVisualConnections(node);
        updateCascade(node);
        orphaned.forEach(this::updateCascade);
    }

    // ══════════════════════════════════════════════════
    //  CanvasContext 구현 (GraphCommand가 editGraph를 조작하는 위임 창구)
    //  모든 메서드는 서브에디터의 editGraph()/canvasPane/viewModelMap 대상.
    // ══════════════════════════════════════════════════

    @Override public void restoreNode(NodeView nv, double x, double y) {
        GraphNode g = nv.getViewModel().getNode();
        editGraph().addNode(g);
        editGraph().setNodePosition(g, (int) x, (int) y);
        viewModelMap.put(g, nv.getViewModel());
        canvasPane.addNode(nv, x, y);   // 리스너는 최초 배선 것을 재사용 — 재배선 안 함
    }

    @Override public void eraseNode(NodeView nv) { removeCanvasNode(nv); }

    @Override public void moveNode(NodeView nv, double x, double y) {
        nv.setLayoutX(x);
        nv.setLayoutY(y);
    }

    @Override public void connect(GraphNode src, int srcPort, GraphNode dst, int dstPort) {
        editGraph().connect(src, srcPort, dst, dstPort);
        rebuildVisualConnections(dst);
        updateCascade(dst);
    }

    @Override public void disconnect(GraphNode dst, int dstPort) {
        editGraph().removeConnectionTo(dst, dstPort);
        rebuildVisualConnections(dst);
        updateCascade(dst);
    }

    @Override public void rebuildConnections(GraphNode node) { rebuildVisualConnections(node); }
    @Override public void cascade(GraphNode node)            { updateCascade(node); }

    @Override public void selectNodes(List<NodeView> nodes) {
        canvasPane.clearSelection();
        for (NodeView nv : nodes) canvasPane.addToSelection(nv);
    }

    @Override public void clearSelection() { canvasPane.clearSelection(); }

    @Override public void redrawConnections() { canvasPane.getConnectionLayer().redraw(); }

    // ══════════════════════════════════════════════════
    //  Undo / Redo 실행부
    // ══════════════════════════════════════════════════

    private void doUndo() { editorUndo.undo(); afterUndoRedo(); }
    private void doRedo() { editorUndo.redo(); afterUndoRedo(); }

    /** undo/redo 적용 후 VM 파생 UI(이름 필드·외부 포트 패널)와 버튼 상태를 재동기화. */
    private void afterUndoRedo() {
        rebuilding = true;
        try {
            refreshNameFields();
            populateExternalPorts();
            canvasPane.getConnectionLayer().redraw();
        } finally {
            rebuilding = false;
        }
        updateUndoRedo();
    }

    /** 현재 VM draft 상태를 이름 필드에 반영 (undo/redo 후). */
    private void refreshNameFields() {
        if (parentGraph == null) {
            operationNameField.setText(vm.getEditName());
        } else {
            applyInstanceNameField(vm.getDraftInstanceName());
        }
    }

    private void updateUndoRedo() {
        undoBtn.setDisable(!editorUndo.canUndo());
        redoBtn.setDisable(!editorUndo.canRedo());
    }

    // ── 키보드 단축키 (scene 레벨 필터; 메인 캔버스와 동일) ─────────────

    private void handleCanvasKey(javafx.scene.input.KeyEvent e) {
        // 텍스트 입력 중에는 가로채지 않음 (이름 필드·다이얼로그 타이핑 보호)
        Node fo = canvasPane.getScene().getFocusOwner();
        if (fo instanceof TextInputControl) return;

        switch (e.getCode()) {
            case DELETE, BACK_SPACE -> { deleteSelectedRecorded(); e.consume(); }
            case C -> { if (e.isShortcutDown()) { copySelected();   e.consume(); } }
            case V -> { if (e.isShortcutDown()) { pasteClipboard(); e.consume(); } }
            case A -> { if (e.isShortcutDown()) { canvasPane.selectAll(); e.consume(); } }
            case Z -> { if (e.isShortcutDown()) { if (e.isShiftDown()) doRedo(); else doUndo(); e.consume(); } }
            case Y -> { if (e.isShortcutDown()) { doRedo(); e.consume(); } }
            default -> { }
        }
    }

    // ── 복사 / 붙여넣기 (메인 캔버스와 동일 로직, editGraph 대상) ────────

    /** 붙여넣기 프로토타입: 클론된 노드 + 선택 중심으로부터의 상대 위치 */
    private record ClipNode(GraphNode proto, double relX, double relY) {}
    /** 선택 내부 연결: 리스트 인덱스 기준 */
    private record ClipConn(int fromIdx, int fromPort, int toIdx, int toPort) {}

    private final List<ClipNode> clipNodes = new ArrayList<>();
    private final List<ClipConn> clipConns = new ArrayList<>();

    private void copySelected() {
        List<NodeView> sel = new ArrayList<>(canvasPane.getSelectedNodes());
        if (sel.isEmpty()) return;

        double cx = 0, cy = 0;
        for (NodeView nv : sel) { cx += nv.getLayoutX(); cy += nv.getLayoutY(); }
        cx /= sel.size(); cy /= sel.size();

        clipNodes.clear();
        clipConns.clear();
        Map<GraphNode, Integer> idx = new HashMap<>();

        for (int i = 0; i < sel.size(); i++) {
            NodeView  nv   = sel.get(i);
            GraphNode orig = nv.getViewModel().getNode();
            idx.put(orig, i);
            GraphNode proto = NodeFactory.createInstance(orig);   // 복사 시점 스냅샷
            clipNodes.add(new ClipNode(proto, nv.getLayoutX() - cx, nv.getLayoutY() - cy));
        }

        // 양 끝이 모두 선택에 포함된 연결만 보존
        for (GraphNode g : idx.keySet()) {
            for (ConnectionModel c : editGraph().getOutputsFrom(g)) {
                Integer toIdx = idx.get(c.getTarget());
                if (toIdx != null)
                    clipConns.add(new ClipConn(
                        idx.get(g), c.getSourcePortIndex(), toIdx, c.getTargetPortIndex()));
            }
        }
        setStatus(sel.size() + " node(s) copied");
    }

    private void pasteClipboard() {
        if (clipNodes.isEmpty()) return;
        javafx.geometry.Point2D center = canvasPane.getViewportCenterInWorld();

        List<GraphCommand> subCmds = new ArrayList<>();
        List<NodeView> pasted = new ArrayList<>();
        for (ClipNode cn : clipNodes) {
            GraphNode clone = NodeFactory.createInstance(cn.proto());
            if (clone == null) continue;
            editGraph().addNode(clone);
            NodeViewModel nvm = createOrGetViewModel(clone);   // 맵 등록 + 리스너 + shapeListener
            if (nvm == null) continue;
            NodeView nv = new NodeView(nvm);

            double lx = center.getX() + cn.relX() - NodeView.WIDTH  / 2.0;
            double ly = center.getY() + cn.relY() - NodeView.HEIGHT / 2.0;
            canvasPane.addNode(nv, lx, ly);
            editGraph().setNodePosition(clone, (int) lx, (int) ly);
            wireCanvasNode(nv);
            pasted.add(nv);
            subCmds.add(new GraphCommands.AddNodeCommand(this, nv, lx, ly));
        }

        // 내부 연결 복원
        for (ClipConn cc : clipConns) {
            GraphNode s = pasted.get(cc.fromIdx()).getViewModel().getNode();
            GraphNode t = pasted.get(cc.toIdx()).getViewModel().getNode();
            try {
                editGraph().connect(s, cc.fromPort(), t, cc.toPort());
                subCmds.add(new GraphCommands.AddConnectionCommand(this, s, cc.fromPort(), t, cc.toPort()));
            } catch (Exception ignore) { /* 포트 불일치 시 스킵 */ }
        }
        for (NodeView nv : pasted) rebuildVisualConnections(nv.getViewModel().getNode());
        for (NodeView nv : pasted) updateCascade(nv.getViewModel().getNode());

        // 붙여넣은 노드를 선택 상태로
        canvasPane.clearSelection();
        for (NodeView nv : pasted) canvasPane.addToSelection(nv);

        // 선택 복원까지 포함해 전체를 하나의 원자적 작업으로 기록
        subCmds.add(new GraphCommands.SelectionCommand(this, pasted));
        editorUndo.record(new GraphCommands.CompositeCommand(new ArrayList<>(subCmds)));
        updateUndoRedo();
        setStatus(pasted.size() + " node(s) pasted");
    }

    // ── 중첩 에디터 save → 이 에디터의 editorUndo로 라우팅 ──────────

    /** 서브에디터 안에서 연 텐서의 save 1회 → editorUndo에 TensorEditCommand 기록. */
    private void onNestedTensorSaved(TensorNode node, Tensor before, String beforeName,
                                     Tensor after, String afterName) {
        editorUndo.record(new GraphCommands.TensorEditCommand(node, before, beforeName, after, afterName));
        updateUndoRedo();
    }

    /** 서브에디터 안에서 연 연산 노드의 Apply 1회 → editorUndo에 OperationEditCommand 기록.
     *  ctx=this 이므로 cascade·연결 복원이 editGraph 기준으로 이뤄진다. */
    private void onNestedOperationSaved(OperationViewModel ovm,
                                        OperationViewModel.CommittedState before,
                                        OperationViewModel.CommittedState after,
                                        List<ConnSnapshot> beforeConns,
                                        List<ConnSnapshot> afterConns) {
        editorUndo.record(new GraphCommands.OperationEditCommand(this, ovm, before, after, beforeConns, afterConns));
        updateUndoRedo();
    }

    // ── 에디터-내부 전용 Command (이름·외부포트 draft / 유틸리티 이름) ─────────

    /** operation/instance 이름 + 외부 포트 재배치 draft 1단위. VM 스냅샷만 스왑한다. */
    private final class DraftStateCommand implements GraphCommand {
        private final CustomOperationViewModel.DraftSnapshot before, after;
        DraftStateCommand(CustomOperationViewModel.DraftSnapshot before,
                          CustomOperationViewModel.DraftSnapshot after) {
            this.before = before; this.after = after;
        }
        @Override public void undo() { vm.restoreDraftSnapshot(before); }
        @Override public void redo() { vm.restoreDraftSnapshot(after);  }
    }

    /** Input/Output 유틸리티 노드 이름 변경 1단위. */
    private final class RenameUtilityCommand implements GraphCommand {
        private final UtilityNodeViewModel uvm;
        private final UtilityNode node;
        private final String before, after;
        RenameUtilityCommand(UtilityNodeViewModel uvm, UtilityNode node, String before, String after) {
            this.uvm = uvm; this.node = node; this.before = before; this.after = after;
        }
        @Override public void undo() { node.setName(before); uvm.notifyListeners(); }
        @Override public void redo() { node.setName(after);  uvm.notifyListeners(); }
    }

    // ── 노드 추가 ─────────────────────────────────────
    private void addInputDialog() {
        promptName("Add Input", "Input name (e.g. query, key, value):").ifPresent(name -> {
            placeNode(NodeFactory.createInputNode(name));
            setStatus("Added input: " + name + "   (unsaved)");
        });
    }

    private void addOutputDialog() {
        promptName("Add Output", "Output name (e.g. score, output):").ifPresent(name -> {
            placeNode(NodeFactory.createOutputNode(name));
            setStatus("Added output: " + name + "   (unsaved)");
        });
    }

    private void showAddOperationMenu(Button anchor) {
        ContextMenu menu = new ContextMenu();

        Menu builtin = new Menu("Built-in");
        for (OperationNode op : builtinOpTemplates) {
            MenuItem mi = new MenuItem(op.getOperationType());
            mi.setOnAction(e -> {
                GraphNode inst = NodeFactory.createInstance(op);
                if (inst != null) { placeNode(inst); setStatus("Added operation: " + op.getOperationType() + "   (unsaved)"); }
            });
            builtin.getItems().add(mi);
        }
        menu.getItems().add(builtin);

        List<CustomOperationNode> safe = filterAcyclicTemplates(vm.getSubGraph());
        if (!safe.isEmpty()) {
            Menu custom = new Menu("Custom");
            for (CustomOperationNode cop : safe) {
                MenuItem mi = new MenuItem(cop.getNodeName());
                mi.setOnAction(e -> {
                    GraphNode inst = NodeFactory.createInstance(cop);
                    if (inst != null) { placeNode(inst); setStatus("Added custom op: " + cop.getNodeName() + "   (unsaved)"); }
                });
                custom.getItems().add(mi);
            }
            menu.getItems().add(custom);
        }
        menu.show(anchor, Side.BOTTOM, 0, 0);
    }

    private void addTensorDialog() {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Add Tensor");
        var dlgCss = getClass().getResource("/com/hemisus/flola/css/main.css");
        if (dlgCss != null) dlg.getDialogPane().getStylesheets().add(dlgCss.toExternalForm());
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        TextField nameF  = new TextField("constant");
        TextField shapeF = new TextField("2,2");
        TextField valsF  = new TextField();
        GridPane gp = new GridPane();
        gp.setHgap(8); gp.setVgap(8);
        gp.addRow(0, new Label("Name:"),               nameF);
        gp.addRow(1, new Label("Shape (e.g. 2,3,4):"), shapeF);
        gp.addRow(2, new Label("Values (optional):"),  valsF);
        dlg.getDialogPane().setContent(gp);
        if (dlg.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        try {
            String name = nameF.getText().trim();
            String[] parts = shapeF.getText().trim().split(",");
            int[] shape = new int[parts.length];
            for (int i = 0; i < parts.length; i++) shape[i] = Integer.parseInt(parts[i].trim());
            TensorNode tn = new TensorNode(new Tensor(name, shape));
            if (shapeListener != null) tn.setShapeChangeListener(shapeListener);
            String vals = valsF.getText().trim();
            if (!vals.isEmpty()) {
                Tensor parsed = DataConverter.stringToTensor(vals);
                if (parsed == null) throw new IllegalArgumentException("value parse failed");
                tn.updateValuesFrom(parsed);
            }
            placeNode(tn);
            setStatus("Added tensor: " + name + "   (unsaved)");
        } catch (Exception ex) {
        	DialogHelper.showWarning("Invalid input: " + ex.getMessage());
        }
    }

    private void renameUtility(UtilityNodeViewModel uvm) {
        TextInputDialog d = new TextInputDialog(uvm.getName());
        d.setTitle(uvm.isInput() ? "Rename Input" : "Rename Output");
        d.setHeaderText(null);
        d.setContentText("New name:");
        d.showAndWait().ifPresent(n -> {
            String nm = n.trim();
            if (nm.isBlank() || nm.equals(uvm.getName())) return;
            UtilityNode un = (UtilityNode) uvm.getNode();
            String oldName = un.getName();
            uvm.setName(nm);
            editorUndo.record(new RenameUtilityCommand(uvm, un, oldName, nm));
            updateUndoRedo();
        });
    }

    private List<CustomOperationNode> filterAcyclicTemplates(Graph target) {
        List<CustomOperationNode> safe = new ArrayList<>();
        for (CustomOperationNode cop : customOpTemplates) {
            if (cop.getSubGraph() == target) continue;
            if (CustomOperationNode.wouldCreateCycle(target, cop)) continue;
            safe.add(cop);
        }
        return safe;
    }

    // ── Apply / Cancel ────────────────────────────────
    /** @return Apply 성공 시 true, 검증 실패로 중단(창 유지) 시 false. */
    private boolean applyChanges() {
        // 변동 여부 + Apply 직전 상태 캡처 (정의·인스턴스이름은 commit 전이므로 '이전' 상태)
        boolean dirty = editorUndo.canUndo();
        CustomOperationViewModel.CommittedSubGraph beforeState = vm.captureCommittedSubGraph();
        List<ConnSnapshot> beforeConns = captureExternalConns();

        if (parentGraph == null) {
            String newOp = operationNameField.getText().trim();
            if (newOp.isEmpty()) {
            	DialogHelper.showWarning("Operation Name must be defined");
                return false;                               // Apply 중단, 창 유지
            }
            if (isOpNameTaken(newOp)) {
            	DialogHelper.showWarning("A Custom Operation named \"" + newOp + "\" already exists.\nPlease enter a different name.");
                return false;                               // Apply 중단, 창 유지
            }
            vm.setEditName(newOp);
        } 
        else {
            String newNode = nodeNameField.getText().trim();
            vm.getCustomNode().setInstanceName(newNode.isEmpty() ? null : newNode);
            vm.setEditName(vm.getOperation().getName());
        }
        // 노드 위치 캡처 → editGraph
        for (NodeView nv : canvasPane.getNodes()) {
            GraphNode n = nv.getViewModel().getNode();
            editGraph().setNodePosition(n, (int) nv.getLayoutX(), (int) nv.getLayoutY());
        }
        Map<ConnectionModel, Integer> imSnap = new HashMap<>(vm.getTempInputPortMap());
        Map<ConnectionModel, Integer> omSnap = new HashMap<>(vm.getTempOutputPortMap());
        vm.save();   // 서브그래프 commit + refreshPortCounts + temp 맵 clear
        applyExternalPortReassignments(imSnap, omSnap);   // parentGraph 연결 재배치 (canvas 모드만)
        if (onOperationChanged != null) onOperationChanged.accept(vm.getOperation());

        // Apply 직후 상태 캡처 → 변동이 있었으면 캔버스 history에 1개 Command로 기록
        CustomOperationViewModel.CommittedSubGraph afterState = vm.captureCommittedSubGraph();
        List<ConnSnapshot> afterConns = captureExternalConns();
        SaveListener listener = (sessionSaveListener != null) ? sessionSaveListener : saveListener;
        if (listener != null && dirty) {
            listener.onCustomOperationSaved(vm, beforeState, afterState, beforeConns, afterConns);
        }

        stage.setTitle("Custom Operation Editor  —  " + vm.getNodeName());
        populateExternalPorts();   // Panel reconstruct
        // Apply로 편집 세션이 확정됨 → 에디터-내부 히스토리 초기화 (버튼 비활성화)
        editorUndo.clear();
        updateUndoRedo();
        setStatus("Applied.");
        return true;
    }

    /** 편집된 인스턴스의 부모 그래프 외부 연결 스냅샷 (template 모드는 외부 연결 없음 → 빈 목록). */
    private List<ConnSnapshot> captureExternalConns() {
        List<ConnSnapshot> list = new ArrayList<>();
        if (parentGraph == null) return list;
        CustomOperationNode inst = vm.getCustomNode();
        for (ConnectionModel c : parentGraph.getInputsFor(inst))
            list.add(new ConnSnapshot(c.getSource(), c.getSourcePortIndex(), c.getTarget(), c.getTargetPortIndex()));
        for (ConnectionModel c : parentGraph.getOutputsFrom(inst))
            list.add(new ConnSnapshot(c.getSource(), c.getSourcePortIndex(), c.getTarget(), c.getTargetPortIndex()));
        return list;
    }

    private void cancelChanges() {
        vm.cancel();
        if (onOperationChanged != null) onOperationChanged.accept(vm.getOperation());
    }

    /**
     * 닫기 시 미저장 변경 확인. editorUndo에 쌓인 게 있으면(=Apply 안 한 편집) 저장 여부를 묻는다.
     * @return true면 닫아도 됨, false면 닫기 취소(창 유지).
     */
    private boolean confirmCloseAndDispose() {
        if (!editorUndo.canUndo()) {   // 변동 없음(또는 모두 undo됨) → 그냥 닫기
            cancelChanges();
            return true;
        }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.initOwner(stage);
        a.setTitle("Unsaved Changes");
        a.setHeaderText(null);
        a.setContentText("Do you want to save changes?");
        var css = getClass().getResource("/com/hemisus/flola/css/main.css");
        if (css != null) a.getDialogPane().getStylesheets().add(css.toExternalForm());

        ButtonType save   = new ButtonType("Save",       ButtonBar.ButtonData.YES);
        ButtonType dont   = new ButtonType("Don't Save", ButtonBar.ButtonData.NO);
        ButtonType cancel = new ButtonType("Cancel",     ButtonBar.ButtonData.CANCEL_CLOSE);
        a.getButtonTypes().setAll(save, dont, cancel);

        ButtonType result = a.showAndWait().orElse(cancel);
        if (result == save) {
            return applyChanges();     // 성공하면 닫기, 검증 실패면 창 유지
        } else if (result == dont) {
            cancelChanges();           // 변경 폐기
            return true;
        }
        return false;                  // Cancel → 닫기 취소
    }

    private ScrollPane buildExternalPortsPanel() {
        extInputBox  = new VBox(4);
        extOutputBox = new VBox(4);

        VBox left = new VBox(8,
            sectionCard("External Inputs",  styledScroll(extInputBox,  220)),
            sectionCard("External Outputs", styledScroll(extOutputBox, 180)));
        left.setPadding(new Insets(10, 6, 10, 10));
        left.setStyle("-fx-background-color:" + BG + ";");

        ScrollPane outer = new ScrollPane(left);
        outer.setFitToWidth(true);
        outer.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        outer.setStyle("-fx-background:" + BG + ";");
        outer.setPrefWidth(290); outer.setMinWidth(260);
        return outer;
    }

    /** 패널 재구성 — 열 때 + Apply 후. (에디터 열린 동안 부모 캔버스 연결 변경은 스코프 외.) */
    private void populateExternalPorts() {
        if (extInputBox == null) return;
        extInputBox.getChildren().clear();
        extOutputBox.getChildren().clear();

        if (parentGraph == null) {                  // Template mode
            extInputBox.getChildren().add(hint(
                "Template Edit Mode. You can rearrange external input ports inside the instance editor "
              + "after placing instances on the canvas and connecting their ports."));
            extOutputBox.getChildren().add(hint(
                "External output ports can also be rearranged when editing canvas instances."));
            return;
        }

        CustomOperationNode inst = vm.getCustomNode();
        List<ConnectionModel> ins  = parentGraph.getInputsFor(inst);
        List<ConnectionModel> outs = parentGraph.getOutputsFrom(inst);

        if (ins.isEmpty()) extInputBox.getChildren().add(hint("No incoming connections."));
        else for (ConnectionModel c : ins) extInputBox.getChildren().add(extInputRow(c));

        if (outs.isEmpty()) extOutputBox.getChildren().add(hint("No outgoing connections."));
        else for (ConnectionModel c : outs) extOutputBox.getChildren().add(extOutputRow(c));
    }

    private HBox extInputRow(ConnectionModel conn) {
        List<InputNode> ports = vm.getEditInputNodes();
        int maxIdx = Math.max(0, ports.size() - 1);
        int ei = Math.max(0, Math.min(vm.getEffectiveInputPort(conn), maxIdx));

        Spinner<Integer> sp = intSpinner(0, maxIdx, ei);
        sp.setPrefWidth(60);
        sp.valueProperty().addListener((o, ov, nw) -> {
            if (rebuilding) return;
            var before = vm.captureDraftSnapshot();
            vm.setTempInputPort(conn, nw);
            editorUndo.record(new DraftStateCommand(before, vm.captureDraftSnapshot()));
            updateUndoRedo();
            populateExternalPorts();                // Port name, label refresh
        });

        Label portName = new Label("[" + portLabel(ports, ei) + "]");
        portName.setTextFill(Color.web(ACCENT));
        Label src = new Label("← " + conn.getSource().getNodeName()
                            + " (Port " + conn.getSourcePortIndex() + ")");
        src.setTextFill(Color.web(LABEL_FG));

        HBox row = new HBox(6, new Label("Port"), sp, portName, src);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private HBox extOutputRow(ConnectionModel conn) {
        List<OutputNode> ports = vm.getEditOutputNodes();
        int maxIdx = Math.max(0, ports.size() - 1);
        int ei = Math.max(0, Math.min(vm.getEffectiveOutputPort(conn), maxIdx));

        Spinner<Integer> sp = intSpinner(0, maxIdx, ei);
        sp.setPrefWidth(60);
        sp.valueProperty().addListener((o, ov, nw) -> {
            if (rebuilding) return;
            var before = vm.captureDraftSnapshot();
            vm.setTempOutputPort(conn, nw);
            editorUndo.record(new DraftStateCommand(before, vm.captureDraftSnapshot()));
            updateUndoRedo();
            populateExternalPorts();
        });

        Label portName = new Label("[" + portLabel(ports, ei) + "]");
        portName.setTextFill(Color.web(ACCENT));
        Label tgt = new Label("→ " + conn.getTarget().getNodeName()
                            + " (Port " + conn.getTargetPortIndex() + ")");
        tgt.setTextFill(Color.web(LABEL_FG));

        HBox row = new HBox(6, new Label("Port"), sp, portName, tgt);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static String portLabel(List<? extends GraphNode> ports, int idx) {
        if (idx < 0 || idx >= ports.size()) return "?";
        return ports.get(idx).getNodeName();
    }

    private void applyExternalPortReassignments(Map<ConnectionModel, Integer> imSnap,
                                                Map<ConnectionModel, Integer> omSnap) {
        if (parentGraph == null) return;
        if (!imSnap.isEmpty()) parentGraph.batchReassignInputPorts (vm.getCustomNode(), imSnap);
        if (!omSnap.isEmpty()) parentGraph.batchReassignOutputPorts(vm.getCustomNode(), omSnap);
    }

    // UI helper
    private void setStatus(String s) { statusLabel.setText(s); }
    private boolean isOpNameTaken(String name) {
        CustomOperation self = vm.getOperation();
        for (CustomOperationNode t : customOpTemplates) {
            CustomOperation op = t.getOperation();
            if (op == self) continue;
            if (op.getName().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private Optional<String> promptName(String title, String prompt) {
        TextInputDialog d = new TextInputDialog();
        d.setTitle(title); d.setHeaderText(null); d.setContentText(prompt);
        return d.showAndWait().map(String::trim).filter(s -> !s.isBlank());
    }

    private static Label barLabel(String text) {
        Label l = new Label(text); l.setTextFill(Color.web(LABEL_FG)); return l;
    }
    private static Button makeSecBtn(String text) {
        Button b = new Button(text);
        b.getStyleClass().addAll("editor-btn", "editor-btn-secondary");  // :hover/:pressed 피드백
        b.setPadding(new Insets(6, 12, 6, 12));
        b.setFocusTraversable(false);
        return b;
    }
    private static Button makePrimBtn(String text) {
        Button b = new Button(text);
        b.getStyleClass().addAll("editor-btn", "editor-btn-primary");
        b.setPadding(new Insets(6, 14, 6, 14));
        b.setFocusTraversable(false);
        return b;
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

    private static Label hint(String text) {
        Label l = new Label(text);
        l.setTextFill(Color.web("#9a9788"));
        l.setFont(Font.font("SansSerif", 11));
        l.setWrapText(true);
        return l;
    }

    private static Spinner<Integer> intSpinner(int min, int max, int val) {
        Spinner<Integer> sp = new Spinner<>(min, max, Math.max(min, Math.min(val, max)));
        sp.setEditable(true);
        return sp;
    }
}
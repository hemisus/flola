package com.hemisus.flola.controller;

import com.hemisus.flola.controller.GraphCommands.ConnSnapshot;
import com.hemisus.flola.event.ShapeChangeListener;
import com.hemisus.flola.model.ConnectionModel;
import com.hemisus.flola.model.Graph;
import com.hemisus.flola.model.GraphNode;
import com.hemisus.flola.model.OperationNode;
import com.hemisus.flola.model.Tensor;
import com.hemisus.flola.model.TensorNode;
import com.hemisus.flola.model.factory.NodeFactory;
import com.hemisus.flola.ui.CanvasPane;
import com.hemisus.flola.ui.Connection;
import com.hemisus.flola.ui.NodeView;
import com.hemisus.flola.ui.OperationEditorStage;
import com.hemisus.flola.ui.Port;
import com.hemisus.flola.ui.TensorEditorStage;
import com.hemisus.flola.utils.DataConverter;
import com.hemisus.flola.utils.DialogHelper;
import com.hemisus.flola.utils.OperationIcons;
import com.hemisus.flola.utils.OperationRegistry;
import com.hemisus.flola.viewmodel.*;
import com.hemisus.flola.model.CustomOperationNode;
import com.hemisus.flola.model.UtilityNode;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.KeyEvent;
import javafx.geometry.Point2D;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

import com.hemisus.flola.utils.GraphStorageJson;
import javafx.stage.FileChooser;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import com.hemisus.flola.model.CustomOperation;
import com.hemisus.flola.ui.CustomOperationEditorStage;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.Modality;
import com.hemisus.flola.utils.RecentProjects;

public class MainController implements CanvasContext {
    @FXML private TreeView<Object> sidebarTree;
    @FXML private CanvasPane       canvasPane;
    @FXML private VBox             inspectorContent;
    @FXML private Label            statusLabel;
    @FXML private Menu             editMenu;
    @FXML private MenuItem         undoMenuItem, redoMenuItem;

    // Model / State
    private final Graph                             graph             = new Graph();
    private final Map<GraphNode, NodeViewModel>     viewModelMap      = new HashMap<>();
    private final List<TensorNode>                  tensorNodes       = new ArrayList<>();
    private final Map<TensorNode, TreeItem<Object>> tensorTreeItemMap = new IdentityHashMap<>();
    private final List<OperationNode>       builtinOpTemplates = new ArrayList<>();
    private final List<CustomOperationNode> customOpTemplates  = new ArrayList<>();
    // Tree handles
    private TreeItem<Object> builtinOpsItem;
    private TreeItem<Object> userScalarItem;
    private TreeItem<Object> userVectorItem;
    private TreeItem<Object> userMatrixItem;
    private TreeItem<Object> userTensorItem;
    private TreeItem<Object> userOpsItem;

    private ShapeChangeListener globalShapeListener;
    private NodeDragController nodeDragController;
    private boolean isUpdatingCascade = false;
    
    private NodeViewModel inspectedVm = null; //current selected vm

    private final GraphUndoManager undoManager = new GraphUndoManager();
    /** 드래그 시작 시점의 선택 노드 위치 스냅샷 (MoveNodesCommand 생성용). */
    private final Map<NodeView, GraphCommands.Pos> preDragPositions = new HashMap<>();
    private ContextMenu addDataMenu;
    private File lastDirectory;
    /** 현재 프로젝트 폴더 (null = 한 번도 저장 안 한 새 프로젝트). */
    private File currentProjectDir;
    /** 사이드바 데이터 등 undo 스택을 거치지 않는 변경 추적 (캔버스 변경은 undoManager로 판정). */
    private boolean dirty = false;
    /** load/clear 중 dirty 표시 억제. */
    private boolean suppressDirty = false;
    // Initialization

    @FXML
    private void initialize() {
        setupShapeChangeListener();
        addDataMenu = buildAddDataMenu();
        buildSidebarTree();
        loadBuiltinOperations();
        setupTreeSelection();
        nodeDragController = new NodeDragController(canvasPane);
        nodeDragController.setOnDrop(this::onNodeDropped);
        canvasPane.setOnConnect(this::handleConnectionRequest);
        canvasPane.getConnectionLayer().setOnConnectionRemoved(this::handleConnectionRemoval);
        graph.setRemovalListener((removedNode, affectedChildNodes) -> {
            for (GraphNode child : affectedChildNodes) {
                if (child instanceof OperationNode) updateCascade(child);
            }
            viewModelMap.remove(removedNode);
        });
        canvasPane.sceneProperty().addListener((o, oldS, newS) -> {
            if (newS != null) newS.addEventFilter(KeyEvent.KEY_PRESSED, this::handleCanvasKey);
        });
        // 텐서 에디터 save를 캔버스 undo history에 통합 (옵션 A)
        TensorEditorStage.setSaveListener(this::onTensorSaved);
        // 연산 에디터 Apply를 캔버스 undo history에 통합 (옵션 A)
        OperationEditorStage.setSaveListener(this::onOperationSaved);
        // 커스텀 연산 에디터 Apply를 캔버스 undo history에 통합
        CustomOperationEditorStage.setSaveListener(this::onCustomOperationSaved);
        // Edit 메뉴를 열 때마다 Undo/Redo 라벨·활성 상태를 현재 history로 갱신
        if (editMenu != null) editMenu.setOnShowing(e -> refreshEditMenu());
        setStatus("Ready");
    }

    /** Edit 메뉴의 Undo/Redo 항목 텍스트와 활성 상태를 현재 undo history로 갱신. */
    private void refreshEditMenu() {
        String u = undoManager.peekUndoDescription();
        undoMenuItem.setText(u != null ? "Undo: " + u : "Nothing to Undo");
        undoMenuItem.setDisable(u == null);
        String r = undoManager.peekRedoDescription();
        redoMenuItem.setText(r != null ? "Redo: " + r : "Nothing to Redo");
        redoMenuItem.setDisable(r == null);
    }

    @FXML private void handleUndo() { undoManager.undo(); refreshEditMenu(); }
    @FXML private void handleRedo() { undoManager.redo(); refreshEditMenu(); }

    /** 텐서 에디터 save 1회 → TensorEditCommand로 캔버스 history에 기록. */
    private void onTensorSaved(TensorNode node, Tensor before, String beforeName,
                               Tensor after, String afterName) {
        undoManager.record(new GraphCommands.TensorEditCommand(
            node, before, beforeName, after, afterName));
    }

    /** 연산 에디터 Apply 1회 → OperationEditCommand로 캔버스 history에 기록. */
    private void onOperationSaved(
    	    OperationViewModel vm,
    	    OperationViewModel.CommittedState before,
    	    OperationViewModel.CommittedState after,
    	    List<ConnSnapshot> beforeConns,
    	    List<ConnSnapshot> afterConns)
    	{
    	    undoManager.record(
    	        new GraphCommands.OperationEditCommand(
    	            this,
    	            vm,
    	            before,
    	            after, beforeConns, afterConns
    	        )
    	    );
    	}

    /** 커스텀 연산 에디터 Apply 1회 → CustomOperationEditCommand로 캔버스 history에 기록. */
    private void onCustomOperationSaved(
            CustomOperationViewModel vm,
            CustomOperationViewModel.CommittedSubGraph before,
            CustomOperationViewModel.CommittedSubGraph after,
            List<ConnSnapshot> beforeConns,
            List<ConnSnapshot> afterConns) {
        CustomOperation op = vm.getOperation();
        undoManager.record(new GraphCommands.CustomOperationEditCommand(
            this, vm, before, after, beforeConns, afterConns,
            () -> onCustomOperationChanged(op)));
    }

    // ── 캔버스 단축키 ─────────────────────────────────────

    private void handleCanvasKey(KeyEvent e) {
        // 텍스트 입력 중에는 가로채지 않음 (인스펙터/다이얼로그 타이핑 보호)
        Node fo = canvasPane.getScene().getFocusOwner();
        if (fo instanceof TextInputControl) return;

        switch (e.getCode()) {
            case DELETE, BACK_SPACE -> { deleteSelected();  e.consume(); }
            case C -> { if (e.isShortcutDown()) { copySelected();   e.consume(); } }
            case V -> { if (e.isShortcutDown()) { pasteClipboard(); e.consume(); } }
            case A -> { if (e.isShortcutDown()) { canvasPane.selectAll(); e.consume(); } }
            case Z -> {
                if (e.isShortcutDown()) {
                    if (e.isShiftDown()) undoManager.redo(); else undoManager.undo();
                    e.consume();
                }
            }
            case Y -> { if (e.isShortcutDown()) { undoManager.redo(); e.consume(); } }
            default -> { }
        }
    }

    private void deleteSelected() {
        List<NodeView> sel = new ArrayList<>(canvasPane.getSelectedNodes());
        if (sel.isEmpty()) return;
        removeNodesRecorded(sel);
        showInspectorPlaceholder("No node selected");
        setStatus(sel.size() + " node(s) deleted");
    }
    
    private void setupShapeChangeListener() {
        globalShapeListener = DialogHelper::confirmShapeChange;
    }
    
    private ContextMenu buildAddDataMenu() {
        ContextMenu menu = new ContextMenu();
        MenuItem mScalar = new MenuItem("New Scalar");
        MenuItem mVector = new MenuItem("New Vector");
        MenuItem mMatrix = new MenuItem("New Matrix");
        MenuItem mTensor = new MenuItem("New Tensor");
        mScalar.setOnAction(e -> createScalarDialog());
        mVector.setOnAction(e -> createVectorDialog());
        mMatrix.setOnAction(e -> createMatrixDialog());
        mTensor.setOnAction(e -> createTensorDialog());
        MenuItem mCustomOp = new MenuItem("New Custom Operation");
        mCustomOp.setOnAction(e -> createCustomOperationDialog());
        menu.getItems().addAll(mScalar, mVector, mMatrix, mTensor, new SeparatorMenuItem(), mCustomOp);
        return menu;
    }
    
    // ── 사이드바 트리 구축 ────────────────────────────────
    private void buildSidebarTree() {
        TreeItem<Object> root = new TreeItem<>("root");
        // Built-in
        TreeItem<Object> builtin = new TreeItem<>("Built-in");
        builtin.setExpanded(true);
        TreeItem<Object> builtinData = new TreeItem<>("Data");
        builtinOpsItem = new TreeItem<>("Operation");
        builtin.getChildren().addAll(builtinData, builtinOpsItem);

        // In Project
        TreeItem<Object> inProject = new TreeItem<>("In Project");
        inProject.setExpanded(true);
        TreeItem<Object> userData = new TreeItem<>("Data");
        userScalarItem = new TreeItem<>("Scalar");
        userVectorItem = new TreeItem<>("Vector");
        userMatrixItem = new TreeItem<>("Matrix");
        userTensorItem = new TreeItem<>("Tensor");
        userData.getChildren().addAll(userScalarItem, userVectorItem, userMatrixItem, userTensorItem);
        userOpsItem = new TreeItem<>("Operation");
        inProject.getChildren().addAll(userData, userOpsItem);

        root.getChildren().addAll(builtin, inProject);
        sidebarTree.setRoot(root);
        sidebarTree.setShowRoot(false);
        sidebarTree.setContextMenu(addDataMenu); 
        sidebarTree.setCellFactory(tv -> new SidebarTreeCell());
    }

    private void loadBuiltinOperations() {
        Map<String, TreeItem<Object>> categoryItems = new LinkedHashMap<>();
        for (OperationNode op : NodeFactory.createDefaultOperations()) {
        	builtinOpTemplates.add(op); 
            OperationViewModel vm = new OperationViewModel(op);
            viewModelMap.put(op, vm);
        
            String category = OperationRegistry.getConfig(op.getOperationType()).category();
            TreeItem<Object> catItem = categoryItems.computeIfAbsent(category, c -> {
                TreeItem<Object> item = new TreeItem<>(c);
                builtinOpsItem.getChildren().add(item);
                return item;
            });
            
            catItem.getChildren().add(new TreeItem<>(vm));
        }
    }

    private void setupTreeSelection() {
        sidebarTree.getSelectionModel().selectedItemProperty().addListener((obs, oldItem, newItem) -> {
            if (newItem == null) { showInspectorPlaceholder("No node selected"); return; }
            Object value = newItem.getValue();
            if (value instanceof NodeViewModel vm) {
                showInspectorFor(vm);
                setStatus(vm.getNodeName() + " selected");
            }
        });
        sidebarTree.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                Object v = sidebarTree.getSelectionModel().getSelectedItem() != null
                    ? sidebarTree.getSelectionModel().getSelectedItem().getValue() : null;
                if (v instanceof TensorViewModel tvm) TensorEditorStage.open(tvm);
                else if (v instanceof CustomOperationViewModel cvm) openCustomOpEditor(cvm, null);
                else if (v instanceof OperationViewModel ovm) OperationEditorStage.open(ovm, graph, false);
            }
        });
    }
    // ── 텐서 추가 / 자동 버킷 이동 / 삭제 ────────────────

    private void addTensorNode(TensorNode node) {
        markDirty();   // 사이드바 데이터 추가 (load 중에는 억제됨)
        node.setShapeChangeListener(globalShapeListener);
        tensorNodes.add(node);
        TensorViewModel vm = new TensorViewModel(node);
        viewModelMap.put(node, vm);
        TreeItem<Object> treeItem = new TreeItem<>(vm);
        tensorTreeItemMap.put(node, treeItem);
        TreeItem<Object> bucket = getKindBucket(node.getKind());
        bucket.getChildren().add(treeItem);
        expandAncestors(treeItem);
        sidebarTree.scrollTo(sidebarTree.getRow(treeItem));

        // Tensor 변경 시: kind 바뀌었으면 버킷 이동, 항상 트리 갱신
        vm.addListener(() -> Platform.runLater(() -> {
            TreeItem<Object> ti = tensorTreeItemMap.get(node);
            if (ti == null) return;
            TreeItem<Object> correct = getKindBucket(node.getKind());
            TreeItem<Object> parent  = ti.getParent();
            if (parent != null && parent != correct) {
                parent.getChildren().remove(ti);
                correct.getChildren().add(ti);
                expandAncestors(ti);
                sidebarTree.scrollTo(sidebarTree.getRow(ti));
            }
            sidebarTree.refresh();
        }));
    }

    private TreeItem<Object> getKindBucket(Tensor.Kind kind) {
        return switch (kind) {
            case SCALAR -> userScalarItem;
            case VECTOR -> userVectorItem;
            case MATRIX -> userMatrixItem;
            case TENSOR -> userTensorItem;
        };
    }

    private void deleteTensor(TensorViewModel vm) {
        TensorNode node = (TensorNode) vm.getNode();
        TreeItem<Object> ti = tensorTreeItemMap.get(node);
        String tname = node.getTensor().getName();
        Tensor tensor = node.getTensor();

        // 같은 Tensor 객체를 참조하는 캔버스 노드 수집 (인스턴스는 new TensorNode(공유 tensor))
        List<NodeView> canvasRefs = new ArrayList<>();
        for (NodeView nv : canvasPane.getNodes()) {
            GraphNode g = nv.getViewModel().getNode();
            if (g instanceof TensorNode tnode && tnode.getTensor() == tensor) canvasRefs.add(nv);
        }

        GraphCommand sidebarRemoval = new GraphCommand() {
            @Override public void undo() { attachSidebarTensor(node, vm, ti); }
            @Override public void redo() { detachSidebarTensor(node, ti); }
            @Override public String describe() { return "Delete " + tname; }
        };
        recordOriginalDeletion("Delete " + tname, canvasRefs, sidebarRemoval);
        setStatus("'" + tname + "' deleted"
            + (canvasRefs.isEmpty() ? "" : " (" + canvasRefs.size() + " canvas node(s))"));
    }

    /** 사이드바 텐서를 트리·목록·맵에서 제거 (vm·TreeItem 객체는 파기하지 않음 → 복원 가능). */
    private void detachSidebarTensor(TensorNode node, TreeItem<Object> ti) {
        tensorTreeItemMap.remove(node);
        if (ti != null && ti.getParent() != null) ti.getParent().getChildren().remove(ti);
        tensorNodes.remove(node);
        viewModelMap.remove(node);
        if (inspectedVm != null && inspectedVm.getNode() == node)
            showInspectorPlaceholder("No node selected");
    }

    /** detach된 사이드바 텐서를 같은 객체 그대로 복원 (kind에 맞는 버킷으로). */
    private void attachSidebarTensor(TensorNode node, TensorViewModel vm, TreeItem<Object> ti) {
        tensorNodes.add(node);
        viewModelMap.put(node, vm);
        tensorTreeItemMap.put(node, ti);
        TreeItem<Object> bucket = getKindBucket(node.getKind());
        bucket.getChildren().add(ti);
        expandAncestors(ti);
        sidebarTree.scrollTo(sidebarTree.getRow(ti));
    }

    private void renameTensor(TensorViewModel vm) {
        Tensor t = vm.getNode().getTensor();
        TextInputDialog dialog = new TextInputDialog(t.getName());
        dialog.setTitle("Rename Tensor");
        dialog.setHeaderText(null);
        dialog.setContentText("New name:");
        applyAppStylesheet(dialog.getDialogPane());
        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) return;
        String newName = result.get().trim();
        if (newName.isEmpty() || newName.equals(t.getName())) return;
        t.setName(newName);  // notifyChange → vm.notifyListeners → 트리 자동 갱신
        markDirty();
        setStatus("Renamed to: " + newName);
    }

    // ── Create Dialogs (공통 폼 헬퍼) ─────────────────────

    /** 다이얼로그는 자체 Scene이라 기본적으로 main.css를 안 불러온다.
     *  이걸 붙여 .root 폰트를 다이얼로그에도 상속시킨다. */
    private void applyAppStylesheet(DialogPane pane) {
        var css = getClass().getResource("/com/hemisus/flola/css/main.css");
        if (css != null) pane.getStylesheets().add(css.toExternalForm());
    }

    private record FormData(Map<String, String> values) {
        String get(String key) { return values.getOrDefault(key, "").trim(); }
    }

    private Optional<FormData> showFormDialog(String title, List<String[]> labelAndDefault) {
        Dialog<FormData> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        applyAppStylesheet(dialog.getDialogPane());

        ButtonType createBtn = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createBtn, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10);
        grid.setPadding(new Insets(16, 24, 8, 16));

        Map<String, TextField> fields = new LinkedHashMap<>();
        for (int i = 0; i < labelAndDefault.size(); i++) {
            String[] ld = labelAndDefault.get(i);
            TextField tf = new TextField(ld[1]);
            tf.setPrefColumnCount(16);
            grid.add(new Label(ld[0]), 0, i);
            grid.add(tf, 1, i);
            fields.put(ld[0], tf);
        }
        dialog.getDialogPane().setContent(grid);

        TextField first = fields.values().iterator().next();
        Platform.runLater(() -> { first.requestFocus(); first.selectAll(); });

        dialog.setResultConverter(b -> {
            if (b == createBtn) {
                Map<String, String> v = new LinkedHashMap<>();
                fields.forEach((k, f) -> v.put(k, f.getText()));
                return new FormData(v);
            }
            return null;
        });
        return dialog.showAndWait();
    }

    private void createScalarDialog() {
        try {
            Optional<FormData> r = showFormDialog("Create Scalar",
                List.of(new String[]{"Name:", "New Scalar"},
                        new String[]{"Value:", ""}));
            if (r.isEmpty()) return;
            String name   = r.get().get("Name:");
            String valStr = r.get().get("Value:");
            double val    = valStr.isEmpty() ? 0.0 : Double.parseDouble(valStr);
            addTensorNode(new TensorNode(Tensor.scalar(name, val)));
        } catch (Exception ex) {
            DialogHelper.showWarning("Invalid value. " + ex.getMessage());
        }
    }

    private void createVectorDialog() {
        try {
            Optional<FormData> r = showFormDialog("Create Vector",
                List.of(new String[]{"Name:", "New Vector"},
                        new String[]{"Size:", "3"},
                        new String[]{"Values (optional):", ""}));
            if (r.isEmpty()) return;
            String name   = r.get().get("Name:");
            int    size   = Integer.parseInt(r.get().get("Size:"));
            String valStr = r.get().get("Values (optional):");
            TensorNode node = new TensorNode(Tensor.matrix(name, 1, size));
            node.setShapeChangeListener(globalShapeListener);
            if (!valStr.isEmpty()) {
                Tensor parsed = DataConverter.stringToTensor(valStr);
                if (parsed == null) throw new IllegalArgumentException("Parse failed");
                node.updateValuesFrom(parsed);
            }
            addTensorNode(node);
        } catch (Exception ex) {
        	DialogHelper.showWarning("Invalid input. " + ex.getMessage());
        }
    }

    private void createMatrixDialog() {
        try {
            Optional<FormData> r = showFormDialog("Create Matrix",
                List.of(new String[]{"Name:", "New Matrix"},
                        new String[]{"Rows:", "3"},
                        new String[]{"Cols:", "3"},
                        new String[]{"Values (optional):", ""}));
            if (r.isEmpty()) return;
            String name   = r.get().get("Name:");
            int    rows   = Integer.parseInt(r.get().get("Rows:"));
            int    cols   = Integer.parseInt(r.get().get("Cols:"));
            String valStr = r.get().get("Values (optional):");
            TensorNode node = new TensorNode(Tensor.matrix(name, rows, cols));
            node.setShapeChangeListener(globalShapeListener);
            if (!valStr.isEmpty()) {
                Tensor parsed = DataConverter.stringToTensor(valStr);
                if (parsed == null) throw new IllegalArgumentException("Parse failed");
                node.updateValuesFrom(parsed);
            }
            addTensorNode(node);
        } catch (Exception ex) {
        	DialogHelper.showWarning("Invalid input. " + ex.getMessage());
        }
    }

    private void createTensorDialog() {
        try {
            Optional<FormData> r = showFormDialog("Create Tensor",
                List.of(new String[]{"Name:", "New Tensor"},
                        new String[]{"Shape (e.g. 2,2,2):", "2,2,2"},
                        new String[]{"Values (optional):", ""}));
            if (r.isEmpty()) return;
            String   name   = r.get().get("Name:");
            String[] parts  = r.get().get("Shape (e.g. 2,2,2):").split(",");
            int[]    shape  = new int[parts.length];
            for (int i = 0; i < parts.length; i++) shape[i] = Integer.parseInt(parts[i].trim());
            String   valStr = r.get().get("Values (optional):");
            TensorNode node = new TensorNode(new Tensor(name, shape));
            node.setShapeChangeListener(globalShapeListener);
            if (!valStr.isEmpty()) {
                Tensor parsed = DataConverter.stringToTensor(valStr);
                if (parsed == null) throw new IllegalArgumentException("Parse failed");
                node.updateValuesFrom(parsed);
            }
            addTensorNode(node);
        } catch (Exception ex) {
        	DialogHelper.showWarning("Invalid input. " + ex.getMessage());
        }
    }
    /** 템플릿을 사이드바·viewModelMap에 등록하고 VM을 반환 (생성/로드 공통). */
    private CustomOperationViewModel addCustomOpTemplate(CustomOperationNode template) {
        markDirty();   // 사이드바 custom op 추가 (load 중에는 억제됨)
        customOpTemplates.add(template);
        CustomOperationViewModel vm = new CustomOperationViewModel(template);
        viewModelMap.put(template, vm);
        userOpsItem.getChildren().add(new TreeItem<>(vm));
        userOpsItem.setExpanded(true);
        return vm;
    }

    private void createCustomOperationDialog() {
        String name = "MyOp";
        while (true) {
            TextInputDialog d = new TextInputDialog(name);
            d.setTitle("New Custom Operation");
            d.setHeaderText(null);
            d.setContentText("Operation name:");
            applyAppStylesheet(d.getDialogPane());
            String input = d.showAndWait().orElse(null);
            if (input == null || input.isBlank()) return;     // 취소
            name = input.trim();
            if (isCustomOpNameTaken(name, null)) {
            	DialogHelper.showWarning("이미 \"" + name + "\" 이름의 Custom Operation이 있습니다.\n다른 이름을 입력하세요.");
                continue;                                       // 다이얼로그 재오픈
            }
            break;
        }
        CustomOperationNode template = NodeFactory.createCustomOperationTemplate(name);
        CustomOperationViewModel vm = addCustomOpTemplate(template);
        setStatus("Created custom op: " + name);
        openCustomOpEditor(vm, null);
    }

    private void openCustomOpEditor(CustomOperationViewModel cvm, Graph parentGraph) {
        CustomOperationEditorStage.open(cvm, parentGraph,
            builtinOpTemplates, customOpTemplates,
            this::onCustomOperationChanged, globalShapeListener);
    }

    /** custom op 정의가 바뀌면 모든 인스턴스의 포트/연결/출력 + 사이드바 라벨 갱신. */
    private void onCustomOperationChanged(CustomOperation op) {
        sidebarTree.refresh();   // 이름 변경 라벨 반영
        for (GraphNode node : new ArrayList<>(graph.getAllNodes())) {
            if (node instanceof CustomOperationNode cn && cn.getOperation() == op) {
                cn.refreshPortCounts();
                int maxInput = cn.getInputPortCount();
                for (ConnectionModel c : new ArrayList<>(graph.getInputsFor(cn)))
                    if (c.getTargetPortIndex() >= maxInput) graph.removeConnectionTo(cn, c.getTargetPortIndex());
                cn.setDirty();
                NodeViewModel vm = viewModelMap.get(cn);
                if (vm != null) vm.notifyListeners();   // → NodeView 포트 갱신 + handleNodeDataUpdate(rebuild+cascade)
            }
        }
    }
    /** customOpTemplates에 같은 이름(대소문자 무시)이 있으면 true. exclude는 자기 자신 제외용(이름 변경 시). */
    boolean isCustomOpNameTaken(String name, CustomOperation exclude) {
        for (CustomOperationNode t : customOpTemplates) {
            CustomOperation op = t.getOperation();
            if (op == exclude) continue;
            if (op.getName().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private void renameCustomOp(CustomOperationViewModel cvm) {
        CustomOperation op = cvm.getOperation();
        TextInputDialog dialog = new TextInputDialog(op.getName());
        dialog.setTitle("Rename Custom Operation");
        dialog.setHeaderText(null);
        dialog.setContentText("New name:");
        applyAppStylesheet(dialog.getDialogPane());
        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) return;
        String newName = result.get().trim();
        if (newName.isEmpty() || newName.equals(op.getName())) return;
        if (isCustomOpNameTaken(newName, op)) {
        	DialogHelper.showWarning("이미 \"" + newName + "\" 이름의 Custom Operation이 있습니다.\n다른 이름을 입력하세요.");
            return;
        }
        op.setName(newName);
        markDirty();
        onCustomOperationChanged(op);   // 트리 라벨 + 모든 인스턴스 갱신
        setStatus("Renamed to: " + newName);
    }

    private void deleteCustomOp(CustomOperationViewModel cvm) {
        CustomOperation op = cvm.getOperation();
        if (!DialogHelper.confirm("Delete Custom Operation", "Delete '" + op.getName() + "'?")) return;
        CustomOperationNode template = cvm.getCustomNode();

        // 사이드바 트리 아이템 확보 (복원용)
        TreeItem<Object> ti = null;
        for (TreeItem<Object> child : userOpsItem.getChildren())
            if (child.getValue() == cvm) { ti = child; break; }
        final TreeItem<Object> sidebarTi = ti;

        // 같은 CustomOperation을 참조하는 캔버스 인스턴스 수집
        List<NodeView> canvasRefs = new ArrayList<>();
        for (NodeView nv : canvasPane.getNodes()) {
            GraphNode g = nv.getViewModel().getNode();
            if (g instanceof CustomOperationNode cn && cn.getOperation() == op) canvasRefs.add(nv);
        }

        GraphCommand sidebarRemoval = new GraphCommand() {
            @Override public void undo() { attachSidebarCustomOp(template, cvm, sidebarTi); }
            @Override public void redo() { detachSidebarCustomOp(template, cvm, sidebarTi); }
            @Override public String describe() { return "Delete " + op.getName(); }
        };
        recordOriginalDeletion("Delete " + op.getName(), canvasRefs, sidebarRemoval);
        setStatus("'" + op.getName() + "' deleted"
            + (canvasRefs.isEmpty() ? "" : " (" + canvasRefs.size() + " canvas node(s))"));
    }

    /** 사이드바 custom op를 트리·목록·맵에서 제거 (cvm·TreeItem 객체는 보존 → 복원 가능). */
    private void detachSidebarCustomOp(CustomOperationNode template,
                                       CustomOperationViewModel cvm, TreeItem<Object> ti) {
        customOpTemplates.remove(template);
        viewModelMap.remove(template);
        if (ti != null) userOpsItem.getChildren().remove(ti);
        if (inspectedVm == cvm) showInspectorPlaceholder("No node selected");
    }

    /** detach된 사이드바 custom op를 같은 객체 그대로 복원. */
    private void attachSidebarCustomOp(CustomOperationNode template,
                                       CustomOperationViewModel cvm, TreeItem<Object> ti) {
        customOpTemplates.add(template);
        viewModelMap.put(template, cvm);
        if (ti != null) userOpsItem.getChildren().add(ti);
        userOpsItem.setExpanded(true);
    }
    
    // ---Sidebar->Canvas Drag-----------------
    private void startDragFromTree(NodeViewModel templateVm) {
        if (nodeDragController.isDragging()) return;
        NodeViewModel canvasVm = createCanvasInstance(templateVm);
        NodeView preview = new NodeView(canvasVm);
        nodeDragController.startDrag(preview);
    }

    private NodeViewModel createCanvasInstance(NodeViewModel templateVm) {
        GraphNode newNode = NodeFactory.createInstance(templateVm.getNode());
        if (newNode instanceof TensorNode tn) tn.setShapeChangeListener(globalShapeListener);
        return makeViewModel(newNode);
    }

    // ── 노드 드롭 후 처리 ────────────────────────────────

    private void onNodeDropped(NodeView node) {
        graph.addNode(node.getViewModel().getNode());
        wireCanvasNode(node);
        undoManager.record(new GraphCommands.AddNodeCommand(
            this, node, node.getLayoutX(), node.getLayoutY()));
        setStatus("Added: " + node.getViewModel().getNodeName());
    }

    private void removeCanvasNode(NodeView node) {
        GraphNode gnode = node.getViewModel().getNode();
        if (node.getViewModel() == inspectedVm) showInspectorPlaceholder("No node selected");
        canvasPane.getConnectionLayer().removeConnectionsOf(node);
        canvasPane.removeNode(node);
        graph.removeNode(gnode);
        viewModelMap.remove(gnode);
        setStatus("Removed: " + node.getViewModel().getNodeName());
    }

    // ── CanvasContext 구현 (Command 위임 창구) ──────────────

    @Override public void restoreNode(NodeView nv, double x, double y) {
        GraphNode g = nv.getViewModel().getNode();
        graph.addNode(g);
        viewModelMap.put(g, nv.getViewModel());
        canvasPane.addNode(nv, x, y);   // 리스너는 최초 배선 것을 재사용 — 재배선 안 함
    }

    @Override public void eraseNode(NodeView nv) { removeCanvasNode(nv); }

    @Override public void moveNode(NodeView nv, double x, double y) {
        nv.setLayoutX(x);
        nv.setLayoutY(y);
    }

    @Override public void connect(GraphNode src, int srcPort, GraphNode dst, int dstPort) {
        graph.connect(src, srcPort, dst, dstPort);
        rebuildVisualConnections(dst);
        updateCascade(dst);
    }

    @Override public void disconnect(GraphNode dst, int dstPort) {
        graph.removeConnectionTo(dst, dstPort);
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

    @Override public void redrawConnections() {
        canvasPane.getConnectionLayer().redraw();
    }

    // ── 삭제 (undo 기록 포함) ──────────────────────────────

    /** 노드 묶음을 제거하고 RemoveNodesCommand로 기록한다. */
    private void removeNodesRecorded(List<NodeView> sel) {
        if (sel.isEmpty()) return;
        GraphCommand cmd = buildRemoveNodesCommand(sel);   // 제거 전에 위치·연결 스냅샷
        for (NodeView nv : sel) removeCanvasNode(nv);
        undoManager.record(cmd);
    }

    /**
     * sel의 위치·연결을 스냅샷해 RemoveNodesCommand를 만든다 (제거·기록은 하지 않음).
     * 반드시 실제 제거 <b>이전</b>에 호출해야 한다 (라이브 그래프에서 연결을 읽으므로).
     */
    private GraphCommand buildRemoveNodesCommand(List<NodeView> sel) {
        List<GraphCommands.Pos> positions = new ArrayList<>();
        for (NodeView nv : sel)
            positions.add(new GraphCommands.Pos(nv.getLayoutX(), nv.getLayoutY()));

        List<GraphCommands.ConnSnapshot> conns = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (NodeView nv : sel) {
            GraphNode g = nv.getViewModel().getNode();
            for (ConnectionModel c : graph.getOutputsFrom(g)) addConnSnapshot(conns, seen, c);
            for (ConnectionModel c : graph.getInputsFor(g))   addConnSnapshot(conns, seen, c);
        }
        return new GraphCommands.RemoveNodesCommand(this, sel, positions, conns);
    }

    /**
     * 사이드바 원본(텐서/커스텀op) 삭제를, 그 원본을 참조하는 캔버스 노드 삭제와 함께
     * 하나의 undo 단위(CompositeCommand)로 실행·기록한다.
     *
     * @param label         메뉴 표시용 라벨 (예: "Delete A")
     * @param canvasRefs    함께 삭제할 캔버스 노드들 (연결·위치는 RemoveNodesCommand가 복원)
     * @param sidebarRemoval redo=사이드바 detach, undo=사이드바 attach 를 수행하는 command
     *                       (초기 detach 실행도 이 메서드가 redo()로 처리한다)
     */
    private void recordOriginalDeletion(String label, List<NodeView> canvasRefs,
                                        GraphCommand sidebarRemoval) {
        List<GraphCommand> parts = new ArrayList<>();
        if (!canvasRefs.isEmpty()) {
            GraphCommand removeCmd = buildRemoveNodesCommand(canvasRefs);   // 스냅샷 먼저
            for (NodeView nv : canvasRefs) removeCanvasNode(nv);            // 캔버스 노드 제거
            parts.add(removeCmd);
        }
        sidebarRemoval.redo();   // 사이드바 detach 실행
        parts.add(sidebarRemoval);
        undoManager.record(new GraphCommands.CompositeCommand(parts, label));
    }

    private void addConnSnapshot(List<GraphCommands.ConnSnapshot> out, Set<String> seen, ConnectionModel c) {
        String key = System.identityHashCode(c.getSource()) + ":" + c.getSourcePortIndex()
                   + ">" + System.identityHashCode(c.getTarget()) + ":" + c.getTargetPortIndex();
        if (seen.add(key)) {
            out.add(new GraphCommands.ConnSnapshot(
                c.getSource(), c.getSourcePortIndex(), c.getTarget(), c.getTargetPortIndex()));
        }
    }

    // ── 드래그 이동 커밋 ──────────────────────────────────

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
        if (!moved.isEmpty())
            undoManager.record(new GraphCommands.MoveNodesCommand(this, moved, from, to));
    }

    // ── 복사 / 붙여넣기 ───────────────────────────────────

    /** 붙여넣기 프로토타입: 클론된 노드 + 선택 중심으로부터의 상대 위치 */
    private record ClipNode(GraphNode proto, double relX, double relY) {}
    /** 선택 내부 연결: 리스트 인덱스 기준 */
    private record ClipConn(int fromIdx, int fromPort, int toIdx, int toPort) {}

    private final List<ClipNode> clipNodes = new ArrayList<>();
    private final List<ClipConn> clipConns = new ArrayList<>();

    private void copySelected() {
        List<NodeView> sel = new ArrayList<>(canvasPane.getSelectedNodes());
        if (sel.isEmpty()) return;

        // 선택 노드들의 layout 중심
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
            for (ConnectionModel c : graph.getOutputsFrom(g)) {
                Integer toIdx = idx.get(c.getTarget());
                if (toIdx != null) {
                    clipConns.add(new ClipConn(
                        idx.get(g), c.getSourcePortIndex(), toIdx, c.getTargetPortIndex()));
                }
            }
        }
        setStatus(sel.size() + " node(s) copied");
    }

    private void pasteClipboard() {
        if (clipNodes.isEmpty()) return;
        Point2D center = canvasPane.getViewportCenterInWorld();

        List<GraphCommand> subCmds = new ArrayList<>();
        List<NodeView> pasted = new ArrayList<>();
        for (ClipNode cn : clipNodes) {
            GraphNode clone = NodeFactory.createInstance(cn.proto());
            if (clone instanceof TensorNode tn) tn.setShapeChangeListener(globalShapeListener);
            NodeViewModel vm = makeViewModel(clone);
            NodeView nv = new NodeView(vm);

            double lx = center.getX() + cn.relX() - NodeView.WIDTH  / 2.0;
            double ly = center.getY() + cn.relY() - NodeView.HEIGHT / 2.0;
            canvasPane.addNode(nv, lx, ly);
            graph.addNode(clone);
            wireCanvasNode(nv);
            pasted.add(nv);
            subCmds.add(new GraphCommands.AddNodeCommand(this, nv, lx, ly));
        }

        // 내부 연결 복원
        for (ClipConn cc : clipConns) {
            GraphNode s = pasted.get(cc.fromIdx()).getViewModel().getNode();
            GraphNode t = pasted.get(cc.toIdx()).getViewModel().getNode();
            try {
                graph.connect(s, cc.fromPort(), t, cc.toPort());
                subCmds.add(new GraphCommands.AddConnectionCommand(
                    this, s, cc.fromPort(), t, cc.toPort()));
            } catch (Exception ignore) { /* 포트 불일치 시 스킵 */ }
        }
        for (NodeView nv : pasted) rebuildVisualConnections(nv.getViewModel().getNode());
        for (NodeView nv : pasted) updateCascade(nv.getViewModel().getNode());

        // 붙여넣은 노드를 선택 상태로
        canvasPane.clearSelection();
        for (NodeView nv : pasted) canvasPane.addToSelection(nv);

        // 선택 복원까지 포함해 전체를 하나의 원자적 작업으로 기록
        subCmds.add(new GraphCommands.SelectionCommand(this, pasted));
        String label = pasted.size() == 1 ? "Paste " + pasted.get(0).getViewModel().getNodeName()
                                          : "Paste " + pasted.size() + " nodes";
        undoManager.record(new GraphCommands.CompositeCommand(subCmds, label));

        setStatus(pasted.size() + " node(s) pasted");
    }

    // 인스펙터 갱신 — 트리/캔버스 선택 양쪽에서 호출.
    // 모든 데이터는 모델(node)에서 직접 읽어 에디터의 draft가 아닌 '원본'을 표시한다.
    private void showInspectorFor(NodeViewModel vm) {
        inspectedVm = vm;
        GraphNode node = vm.getNode();
        VBox box = new VBox(12);
        box.setPadding(new Insets(12, 16, 16, 16));

        if (vm instanceof TensorViewModel tvm) {
            Tensor t = tvm.getNode().getTensor();   // originalTensor (editTensor 아님)
            box.getChildren().add(inspSection("Identity",
                inspRow("Node",   node.getNodeName()),
                inspRow("Tensor", t.getName()),
                inspRow("Kind",   t.getKind().toString())));
            box.getChildren().add(inspSection("Data",
                inspRow("Shape", t.getSummary()),
                inspValueBlock(t.getValueString())));
        } else if (vm instanceof CustomOperationViewModel cvm) {
            CustomOperationNode cn = cvm.getCustomNode();
            box.getChildren().add(inspSection("Identity",
                inspRow("Node",      node.getNodeName()),
                inspRow("Custom Op", cvm.getOperationType()),   // 공유 operation 이름
                inspRow("Ports",     cn.getInputPortCount() + " in / " + cn.getOutputPortCount() + " out")));
            box.getChildren().add(inspResultSection(cn.getOutputs()));
        } else if (vm instanceof OperationViewModel ovm) {
            OperationNode on = (OperationNode) node;
            box.getChildren().add(inspSection("Identity",
                inspRow("Node",      node.getNodeName()),
                inspRow("Operation", ovm.getOperationType()),   // 연산 종류
                inspRow("Ports",     ovm.getInputCount()  + (ovm.isInputVariadic()  ? "+" : "") + " in / "
                                   + ovm.getOutputCount() + (ovm.isOutputVariadic() ? "+" : "") + " out")));
            box.getChildren().add(inspResultSection(on.getOutputs()));
        } else {
            box.getChildren().add(inspSection("Identity", inspRow("Node", node.getNodeName())));
        }

        box.getChildren().add(inspConnectionsSection(node));   // graph 기준 (템플릿이면 비어있음)
        inspectorContent.getChildren().setAll(box);
    }

    private void showInspectorPlaceholder(String text) {
        inspectedVm = null;
        Label l = new Label(text);
        l.getStyleClass().add("placeholder-label");
        l.setWrapText(true);
        VBox.setMargin(l, new Insets(24, 16, 16, 16));
        inspectorContent.getChildren().setAll(l);
    }

    // ── 인스펙터 UI 헬퍼 ──────────────────────────────────
    private static final String INSP_HEADER_FG = "#9a9788";
    private static final String INSP_KEY_FG    = "#7a786e";
    private static final String INSP_VAL_FG    = "#2f2e2b";

    private VBox inspSection(String title, Node... rows) {
        Label header = new Label(title.toUpperCase());
        header.setStyle("-fx-font-size:10.5; -fx-font-weight:bold; -fx-text-fill:" + INSP_HEADER_FG + ";");
        VBox sec = new VBox(5, header);
        sec.getChildren().addAll(rows);
        return sec;
    }

    private HBox inspRow(String key, String value) {
        Label k = new Label(key);
        k.setMinWidth(76); k.setPrefWidth(76);
        k.setStyle("-fx-font-size:12; -fx-text-fill:" + INSP_KEY_FG + ";");
        Label v = new Label((value == null || value.isBlank()) ? "—" : value);
        v.setWrapText(true);
        v.setStyle("-fx-font-size:12; -fx-text-fill:" + INSP_VAL_FG + ";");
        HBox.setHgrow(v, Priority.ALWAYS);
        HBox row = new HBox(8, k, v);
        return row;
    }

    private VBox inspValueBlock(String value) {
        Label v = new Label((value == null || value.isBlank()) ? "—" : value);
        v.setWrapText(true);
        // TensorEditor의 값 표시 영역과 동일하게 Monospaced (숫자 정렬)
        v.setStyle("-fx-font-family:'Monospaced'; -fx-font-size:11.5; -fx-text-fill:" + INSP_VAL_FG + ";");

        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);                 // 가로는 폭에 맞춰 줄바꿈
        sp.setMaxHeight(140);                   // 미리보기 최대 높이 → 넘치면 세로 스크롤
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        // ScrollPane 기본 테두리/배경 제거 (블록 배경이 비치도록)
        sp.setStyle("-fx-background:transparent; -fx-background-color:transparent; -fx-padding:0;");

        VBox vb = new VBox(sp);
        vb.setStyle("-fx-background-color:#fafaf7; -fx-border-color:#e0dfd9; -fx-border-radius:3; -fx-background-radius:3;");
        vb.setPadding(new Insets(6, 8, 6, 8));
        return vb;
    }

    private VBox inspResultSection(List<Tensor> outputs) {
        Label header = new Label("RESULT");
        header.setStyle("-fx-font-size:10.5; -fx-font-weight:bold; -fx-text-fill:" + INSP_HEADER_FG + ";");
        VBox sec = new VBox(6, header);
        if (outputs == null || outputs.isEmpty()) {
            sec.getChildren().add(inspMuted("Not evaluated."));
            return sec;
        }
        for (int i = 0; i < outputs.size(); i++) {
            Tensor t = outputs.get(i);
            if (t == null) { sec.getChildren().add(inspRow("Port " + i, "—")); continue; }
            sec.getChildren().add(inspRow("Port " + i, t.getName() + "  [" + t.getSummary() + "]"));
            sec.getChildren().add(inspValueBlock(t.getValueString()));
        }
        return sec;
    }

    private VBox inspConnectionsSection(GraphNode node) {
        Label header = new Label("CONNECTIONS");
        header.setStyle("-fx-font-size:10.5; -fx-font-weight:bold; -fx-text-fill:" + INSP_HEADER_FG + ";");
        VBox sec = new VBox(5, header);
        List<ConnectionModel> ins  = graph.getInputsFor(node);
        List<ConnectionModel> outs = graph.getOutputsFrom(node);
        if (ins.isEmpty() && outs.isEmpty()) {
            sec.getChildren().add(inspMuted("No connections."));
            return sec;
        }
        for (ConnectionModel c : ins)
            sec.getChildren().add(inspMuted("in " + c.getTargetPortIndex() + "  ←  "
                + c.getSource().getNodeName() + " (out " + c.getSourcePortIndex() + ")"));
        for (ConnectionModel c : outs)
            sec.getChildren().add(inspMuted("out " + c.getSourcePortIndex() + "  →  "
                + c.getTarget().getNodeName() + " (in " + c.getTargetPortIndex() + ")"));
        return sec;
    }

    private Label inspMuted(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setStyle("-fx-font-size:12; -fx-text-fill:" + INSP_KEY_FG + ";");
        return l;
    }
    // ── 연결 생성 ─────────────────────────────────────────
    private void handleConnectionRequest(Port from, Port to) {
        GraphNode src  = from.getOwner().getViewModel().getNode();
        GraphNode dest = to.getOwner().getViewModel().getNode();
        try {
            List<ConnectionModel> before = new ArrayList<>(graph.getInputsFor(dest));
            graph.connect(src, from.getIndex(), dest, to.getIndex());  // to.index=-1이면 자동 할당
            rebuildVisualConnections(dest);
            updateCascade(dest);
            // 새로 추가된 연결을 찾아 resolved 포트로 기록
            ConnectionModel added = null;
            for (ConnectionModel c : graph.getInputsFor(dest))
                if (!before.contains(c)) { added = c; break; }
            if (added != null)
                undoManager.record(new GraphCommands.AddConnectionCommand(
                    this, added.getSource(), added.getSourcePortIndex(),
                    added.getTarget(), added.getTargetPortIndex()));
            setStatus("Connected: " + src.getNodeName() + " \u2192 " + dest.getNodeName());
        } catch (Exception ex) {
            DialogHelper.showWarning("Connection failed: " + ex.getMessage());
        }
    }

    // ── 연결 제거 ─────────────────────────────────────────
    private void handleConnectionRemoval(Connection c) {
        GraphNode dest = c.getTo().getOwner().getViewModel().getNode();
        int destPort   = c.getTo().getIndex();
        GraphNode src  = c.getFrom().getOwner().getViewModel().getNode();
        int srcPort    = c.getFrom().getIndex();
        graph.removeConnectionTo(dest, destPort);
        rebuildVisualConnections(dest);
        updateCascade(dest);
        undoManager.record(new GraphCommands.RemoveConnectionCommand(this, src, srcPort, dest, destPort));
        setStatus("Connection removed");
    }

    // ── 모델 기준으로 노드의 연결 곡선 재구성 ─────────────
    private void rebuildVisualConnections(GraphNode node) {
        NodeView nv = findNodeView(node);
        if (nv == null) return;

        java.util.List<Connection> conns = new java.util.ArrayList<>();
        for (ConnectionModel cm : graph.getOutputsFrom(node)) {
            NodeView t = findNodeView(cm.getTarget());
            if (t == null) continue;
            conns.add(new Connection(
                new Port(nv, Port.Type.OUTPUT, cm.getSourcePortIndex()),
                new Port(t,  Port.Type.INPUT,  cm.getTargetPortIndex())));
        }
        for (ConnectionModel cm : graph.getInputsFor(node)) {
            NodeView s = findNodeView(cm.getSource());
            if (s == null) continue;
            conns.add(new Connection(
                new Port(s,  Port.Type.OUTPUT, cm.getSourcePortIndex()),
                new Port(nv, Port.Type.INPUT,  cm.getTargetPortIndex())));
        }
        canvasPane.getConnectionLayer().replaceConnectionsOf(nv, conns);
    }
    
    // --- UpdateCascade----------

    private void updateCascade(GraphNode startNode) {
        if (isUpdatingCascade) return;
        try {
            isUpdatingCascade = true;
            for (GraphNode node : getSortedDownstream(startNode)) {
                if (node instanceof OperationNode op) graph.evaluateNode(op);
                NodeViewModel vm = viewModelMap.get(node);
                if (vm != null) { vm.syncFromNode(); vm.notifyListeners(); }
            }
        } finally {
            isUpdatingCascade = false;
            // 데이터 변경 후 인스펙터를 최신 모델 기준으로 재렌더.
            // 모든 변경 경로(연결 추가/제거, 에디터 save, 파일 열기)가 여기를 거친다.
            if (inspectedVm != null) showInspectorFor(inspectedVm);
        }
    }

    private List<GraphNode> getSortedDownstream(GraphNode startNode) {
        List<GraphNode> sorted  = new ArrayList<>();
        Set<GraphNode>  visited = new HashSet<>();
        dfsVisit(startNode, visited, sorted);
        Collections.reverse(sorted);
        return sorted;
    }

    private void dfsVisit(GraphNode node, Set<GraphNode> visited, List<GraphNode> sorted) {
        if (visited.contains(node)) return;
        visited.add(node);
        if (node instanceof OperationNode op) op.setDirty();
        for (ConnectionModel conn : graph.getOutputsFrom(node))
            dfsVisit(conn.getTarget(), visited, sorted);
        sorted.add(node);
    }

    // ── 노드 데이터 변경 처리 (에디터 수정 → 캐스케이드) ─────────
    private void handleNodeDataUpdate(NodeViewModel vm) {
        if (isUpdatingCascade) return;
        GraphNode node = vm.getNode();
        int newOutputCount = node.getOutputPortCount();

        // 출력 포트가 줄어서 끊기는 하류 노드 추적
        Set<GraphNode> orphanedNodes = new HashSet<>();
        for (ConnectionModel conn : graph.getOutputsFrom(node)) {
            if (conn.getSourcePortIndex() >= newOutputCount)
                orphanedNodes.add(conn.getTarget());
        }

        graph.sanitizeOutputConnections(node);
        rebuildVisualConnections(node);
        updateCascade(node);
        orphanedNodes.forEach(this::updateCascade);
    }
    private NodeView findNodeView(GraphNode node) {
        for (NodeView nv : canvasPane.getNodes())
            if (nv.getViewModel().getNode() == node) return nv;
        return null;
    }
    // ── 메뉴 핸들러 ───────────────────────────────────────
    @FXML private void handleNew() {
        if (!confirmSaveIfDirty()) return;
        clearProject();                 // undoManager.clear() 포함 → dirty 리셋
        currentProjectDir = null;
        dirty = false;
        setStatus("New project");
    }

    @FXML private void handleOpen() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Open Project");
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("FLOLA Project (*.flola)", "*.flola"));
        if (lastDirectory != null) fc.setInitialDirectory(lastDirectory);
        File file = fc.showOpenDialog(canvasPane.getScene().getWindow());
        if (file == null) return;                 // 파일 선택 취소
        openProjectFile(file);
    }

    /**
     * 지정한 .flola 프로젝트 파일을 연다 (저장 확인 포함).
     * 메뉴 Open뿐 아니라 파일 연결 더블클릭/시작 인자({@code MainApp})에서도 호출된다.
     */
    public void openProjectFile(File file) {
        if (file == null || !file.exists()) return;
        if (!confirmSaveIfDirty()) return;         // 현재 프로젝트 변경분 저장 확인
        lastDirectory = file.getParentFile();
        try {
            loadProject(file);
            currentProjectDir = file.getParentFile();   // 프로젝트 폴더 기억 → 이후 Save는 바로 이 폴더로
            dirty = false;
            undoManager.markClean();
            RecentProjects.add(file);                    // 최근 프로젝트 목록에 추가
            setStatus("Loaded: " + (file.getParentFile() != null ? file.getParentFile().getName() : file.getName()));
        } catch (Exception e) {
        	DialogHelper.showWarning("Open failed: " + e.getMessage());
        }
    }

    @FXML private void handleSave() { saveProject(); }

    /**
     * 시작 시 표시하는 프로젝트 선택 창 (Eclipse 워크스페이스 선택과 유사).
     * 새 프로젝트로 시작하거나, 기존 .flola를 열거나, 최근 프로젝트를 원클릭으로 연다.
     * {@code MainApp}이 메인 창을 띄운 직후 (파일 인자가 없을 때) 호출한다.
     */
    public void showStartupChooser() {
        Stage dialog = new Stage();
        dialog.initOwner(canvasPane.getScene().getWindow());
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("FLOLA - Open Project");

        Label title = new Label("FLOLA");
        title.setStyle("-fx-font-size:24; -fx-font-weight:bold; -fx-text-fill:#4A7CBF;");
        Label sub = new Label("Linear Algebra Editor");
        sub.setStyle("-fx-font-size:12; -fx-text-fill:#8a8880;");

        Label recentLbl = new Label("Recent Projects");
        recentLbl.setStyle("-fx-font-weight:bold; -fx-text-fill:#6e6c63;");

        ListView<File> recentList = new ListView<>();
        recentList.getItems().addAll(RecentProjects.list());
        recentList.setPrefHeight(190);
        recentList.setPlaceholder(new Label("최근 연 프로젝트가 없습니다"));
        recentList.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(File f, boolean empty) {
                super.updateItem(f, empty);
                if (empty || f == null) { setText(null); return; }
                File dir = f.getParentFile();
                String name = (dir != null) ? dir.getName() : f.getName();
                String path = (dir != null) ? dir.getAbsolutePath() : f.getAbsolutePath();
                setText(name + "\n" + path);
            }
        });

        Button newBtn  = makeStartBtn("New Project", true);
        Button openBtn = makeStartBtn("Open Project…", false);

        // 동작 정의
        Runnable openSelected = () -> {
            File sel = recentList.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            if (!sel.exists()) {                       // 경로가 사라진 항목 정리
                DialogHelper.showWarning("파일을 찾을 수 없습니다:\n" + sel.getAbsolutePath());
                RecentProjects.remove(sel);
                recentList.getItems().remove(sel);
                return;
            }
            dialog.close();
            openProjectFile(sel);
        };
        recentList.setOnMouseClicked(e -> { if (e.getClickCount() == 2) openSelected.run(); });

        newBtn.setOnAction(e -> {
            dialog.close();                            // 메인 창은 이미 빈 새 프로젝트 상태
            currentProjectDir = null;
            dirty = false;
            setStatus("New project");
        });
        openBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Open Project");
            fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("FLOLA Project (*.flola)", "*.flola"));
            if (lastDirectory != null) fc.setInitialDirectory(lastDirectory);
            File f = fc.showOpenDialog(dialog);
            if (f != null) { dialog.close(); openProjectFile(f); }
        });

        HBox buttons = new HBox(10, newBtn, openBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(6,
            title, sub, new Separator(), recentLbl, recentList, buttons);
        VBox.setMargin(buttons, new Insets(8, 0, 0, 0));
        root.setPadding(new Insets(24));
        root.setPrefWidth(480);
        root.getStyleClass().add("editor-root");

        Scene scene = new Scene(root);
        var css = getClass().getResource("/com/hemisus/flola/css/main.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        dialog.setScene(scene);
        dialog.showAndWait();   // 선택할 때까지 대기 (X로 닫으면 빈 새 프로젝트로 시작)
    }

    private Button makeStartBtn(String text, boolean primary) {
        Button b = new Button(text);
        b.getStyleClass().addAll("editor-btn", primary ? "editor-btn-primary" : "editor-btn-secondary");
        b.setFocusTraversable(false);
        return b;
    }

    /**
     * 현재 프로젝트를 저장한다.
     * <ul>
     *   <li>이미 저장 위치({@code currentProjectDir})가 있으면 그 폴더로 바로 저장</li>
     *   <li>없으면(새 프로젝트) 폴더 선택 다이얼로그를 띄운다</li>
     * </ul>
     * @return 저장 성공 시 true, 폴더 선택 취소·오류 시 false.
     */
    private boolean saveProject() {
        File dir = currentProjectDir;
        if (dir == null) {                          // 새 프로젝트 → 저장 위치 지정
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Save Project (select/create a folder)");
            if (lastDirectory != null) dc.setInitialDirectory(lastDirectory);
            dir = dc.showDialog(canvasPane.getScene().getWindow());
            if (dir == null) return false;          // 취소
        }

        Map<GraphNode, double[]> positions = new IdentityHashMap<>();
        for (NodeView nv : canvasPane.getNodes())
            positions.put(nv.getViewModel().getNode(),
                new double[]{ nv.getLayoutX(), nv.getLayoutY() });
        try {
            GraphStorageJson.save(dir, graph,
                new ArrayList<>(tensorNodes), new ArrayList<>(customOpTemplates), positions);
            currentProjectDir = dir;
            lastDirectory = (dir.getParentFile() != null) ? dir.getParentFile() : dir;
            dirty = false;
            undoManager.markClean();
            RecentProjects.add(new File(dir, GraphStorageJson.PROJECT_FILE));   // 최근 목록에 추가
            setStatus("Saved: " + dir.getName());
            return true;
        } catch (IOException e) {
        	DialogHelper.showWarning("Save failed: " + e.getMessage());
            return false;
        }
    }

    /** 변동 여부 = 캔버스(undo) 변경 또는 사이드바 데이터 변경. */
    private boolean isProjectDirty() { return dirty || undoManager.isDirty(); }

    /** 사이드바 등 비-undo 변경 발생 표시 (load/clear 중에는 무시). */
    private void markDirty() { if (!suppressDirty) dirty = true; }

    /**
     * 변동이 있으면 저장 여부를 묻는다(Save / Don't Save / Cancel).
     * @return true면 다음 동작(New/Open)을 진행해도 됨, false면 취소.
     */
    private boolean confirmSaveIfDirty() {
        if (!isProjectDirty()) return true;

        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.initOwner(canvasPane.getScene().getWindow());
        a.setTitle("Unsaved Changes");
        a.setHeaderText(null);
        a.setContentText("Do you want to save changes to the current project?");
        applyAppStylesheet(a.getDialogPane());

        ButtonType save   = new ButtonType("Save",       ButtonBar.ButtonData.YES);
        ButtonType dont   = new ButtonType("Don't Save", ButtonBar.ButtonData.NO);
        ButtonType cancel = new ButtonType("Cancel",     ButtonBar.ButtonData.CANCEL_CLOSE);
        a.getButtonTypes().setAll(save, dont, cancel);

        ButtonType r = a.showAndWait().orElse(cancel);
        if (r == save) return saveProject();   // 저장 성공 시 진행, 폴더 선택 취소 시 중단
        if (r == dont) return true;            // 변경 폐기
        return false;                          // Cancel
    }

    @FXML private void handleExit() {
        if (confirmSaveIfDirty()) Platform.exit();
    }

    /**
     * 창 닫기(X 버튼) 시 호출 — 미저장 변경이 있으면 저장 여부를 묻는다.
     * @return 닫기를 진행해도 되면 true, 취소(닫지 않음)면 false.
     */
    public boolean confirmClose() {
        return confirmSaveIfDirty();
    }

    private void loadProject(File projectFile) throws IOException {
        suppressDirty = true;
        try {
            clearProject();
            GraphStorageJson.LoadResult result = GraphStorageJson.load(projectFile, graph);

            for (TensorNode tn : result.sidebarTensors) addTensorNode(tn);
            for (CustomOperationNode template : result.sidebarCustomOps) addCustomOpTemplate(template);

            for (GraphStorageJson.CanvasNodeEntry entry : result.canvasNodes) {
                GraphNode gnode = entry.node();
                if (gnode instanceof TensorNode tn) tn.setShapeChangeListener(globalShapeListener);
                NodeView nv = new NodeView(makeViewModel(gnode));
                canvasPane.addNode(nv, entry.x(), entry.y());
                wireCanvasNode(nv);
            }
            for (NodeView nv : canvasPane.getNodes())
                rebuildVisualConnections(nv.getViewModel().getNode());
            for (GraphNode n : graph.getAllNodes())
                if (n instanceof TensorNode) updateCascade(n);
        } finally {
            suppressDirty = false;
        }
    }

    private void clearProject() {
        isUpdatingCascade = true;                 // teardown 중 cascade 억제
        try {
            for (NodeView nv : new ArrayList<>(canvasPane.getNodes())) removeCanvasNode(nv);
        } finally {
            isUpdatingCascade = false;
        }
        undoManager.clear();   // 이전 프로젝트의 undo history 폐기 (stale command 방지)
        for (TensorNode tn : new ArrayList<>(tensorNodes)) viewModelMap.remove(tn);
        for (CustomOperationNode t : new ArrayList<>(customOpTemplates)) viewModelMap.remove(t);   // ← 추가
        tensorNodes.clear();
        customOpTemplates.clear();
        tensorTreeItemMap.clear();
        userScalarItem.getChildren().clear();
        userVectorItem.getChildren().clear();
        userMatrixItem.getChildren().clear();
        userTensorItem.getChildren().clear();
        userOpsItem.getChildren().clear();
        showInspectorPlaceholder("No node selected");
    }

    private NodeViewModel makeViewModel(GraphNode node) {
        if (node instanceof TensorNode tn)          return new TensorViewModel(tn);
        if (node instanceof CustomOperationNode cn) return new CustomOperationViewModel(cn);
        if (node instanceof UtilityNode un)         return new UtilityNodeViewModel(un);
        if (node instanceof OperationNode on)       return new OperationViewModel(on);
        throw new IllegalArgumentException("Unsupported node type: " + node.getClass());
    }

    /** 드롭/로드 공통 캔버스 노드 배선 (graph.addNode는 호출부 책임) */
    private void wireCanvasNode(NodeView node) {
        NodeViewModel vm    = node.getViewModel();
        GraphNode     gnode = vm.getNode();
        viewModelMap.put(gnode, vm);
        node.setOnSelected(nv -> {
            canvasPane.selectOnly(nv);
            showInspectorFor(nv.getViewModel());
            setStatus(nv.getViewModel().getNodeName() + " selected");
        });
        node.setOnNodePressed((nv, shift) -> {
            if (shift) canvasPane.toggleSelection(nv);
            else if (!canvasPane.isSelected(nv)) canvasPane.selectOnly(nv);
            // 드래그 이동 추적: 선택 갱신 후 현재 선택 노드들의 위치 스냅샷
            preDragPositions.clear();
            for (NodeView s : canvasPane.getSelectedNodes())
                preDragPositions.put(s, new GraphCommands.Pos(s.getLayoutX(), s.getLayoutY()));
        });
        node.setOnDragDelta((nv, d) -> canvasPane.moveSelectionBy(nv, d.getX(), d.getY()));
        node.setOnDragEnd(nv -> commitMove());
        node.setOnDoubleClicked(nv -> {
            NodeViewModel m = nv.getViewModel();
            if (m instanceof TensorViewModel tvm)        TensorEditorStage.open(tvm);
            else if (m instanceof CustomOperationViewModel cvm) openCustomOpEditor(cvm, graph);
            else if (m instanceof OperationViewModel ovm) OperationEditorStage.open(ovm, graph);
        });
        node.setOnRemove(nv -> removeNodesRecorded(java.util.List.of(nv)));
        node.setOnRemoveSelected(this::deleteSelected);
        node.setOnCopySelected(this::copySelected);
        node.setSelectionCount(() -> canvasPane.getSelectedNodes().size());
        vm.addListener(() -> handleNodeDataUpdate(vm));
        node.layoutXProperty().addListener((o, ov, nv) -> canvasPane.getConnectionLayer().redraw());
        node.layoutYProperty().addListener((o, ov, nv) -> canvasPane.getConnectionLayer().redraw());
    }
    
    // ── Cell Factory ──────────────────────────────────────

    private class SidebarTreeCell extends TreeCell<Object> {
    	private double  pressSceneX, pressSceneY;
        private boolean pressed = false;
        private static final double DRAG_THRESHOLD = 6;

        SidebarTreeCell() {
            setOnMousePressed(e -> {
                if (e.getButton() == MouseButton.PRIMARY && !isEmpty()) {
                    pressSceneX = e.getSceneX();
                    pressSceneY = e.getSceneY();
                    pressed = true;
                }
            });
            setOnMouseDragged(e -> {
                if (!pressed || e.getButton() != MouseButton.PRIMARY) return;
                double dx = e.getSceneX() - pressSceneX;
                double dy = e.getSceneY() - pressSceneY;
                if (Math.hypot(dx, dy) > DRAG_THRESHOLD) {
                    pressed = false;
                    if (getItem() instanceof NodeViewModel vm) {
                        startDragFromTree(vm);
                    }
                }
            });
            setOnMouseReleased(e -> pressed = false);

            // 우클릭(secondary)이 disclosure 화살표의 펼침/접힘 토글을 트리거하지 않게 막는다.
            // 컨텍스트 메뉴는 ContextMenuEvent로 별도 발생하므로 그대로 뜬다.
            addEventFilter(MouseEvent.MOUSE_PRESSED,  e -> {
                if (e.getButton() == MouseButton.SECONDARY && isOnDisclosure(e)) e.consume();
            });
            addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
                if (e.getButton() == MouseButton.SECONDARY && isOnDisclosure(e)) e.consume();
            });
        }

        /** 마우스 이벤트가 이 셀의 disclosure 화살표(또는 그 자식) 위에서 발생했는지. */
        private boolean isOnDisclosure(MouseEvent e) {
            Node disc = lookup(".tree-disclosure-node");
            if (disc == null) return false;   // leaf 셀(텐서/op)엔 화살표 없음
            Node n = e.getPickResult().getIntersectedNode();
            while (n != null) {
                if (n == disc) return true;
                n = n.getParent();
            }
            return false;
        }
        
        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("tree-category", "tree-tensor", "tree-operation");

            if (empty || item == null) {
                setText(null); setGraphic(null); setContextMenu(null);
                return;
            }

            if (item instanceof TensorViewModel tvm) {
                Tensor t = tvm.getNode().getTensor();
                setText("[" + t.getSummary() + "]  " + t.getName());
                setGraphic(null);
                getStyleClass().add("tree-tensor");
                setContextMenu(buildTensorContextMenu(tvm));
            } else if (item instanceof OperationViewModel ovm) {
                setText(ovm.getOperationType());
                setGraphic(OperationIcons.view(ovm.getOperationType(), 20));   // PNG 아이콘 (없으면 null)
                getStyleClass().add("tree-operation");
                setContextMenu(null);
            } 
            else if (item instanceof CustomOperationViewModel cvm) {
            	setText(cvm.getOperationType());
            	setGraphic(null);
            	getStyleClass().add("tree-operation");
            	setContextMenu(buildCustomOpContextMenu(cvm));
            }
            else {
                setText(item.toString());
                setGraphic(null);
                getStyleClass().add("tree-category");
                setContextMenu(addDataMenu);
            }
        }

        private ContextMenu buildTensorContextMenu(TensorViewModel vm) {
            ContextMenu menu = new ContextMenu();
            MenuItem rename = new MenuItem("Rename");
            rename.setOnAction(e -> renameTensor(vm));
            MenuItem delete = new MenuItem("Delete");
            delete.setOnAction(e -> {
                if (DialogHelper.confirm("Delete Tensor",
                        "Delete '" + vm.getNode().getTensor().getName() + "'?")) {
                    deleteTensor(vm);
                }
            });
            menu.getItems().addAll(rename, new SeparatorMenuItem(), delete);
            return menu;
        }

        private ContextMenu buildCustomOpContextMenu(CustomOperationViewModel vm) {
            ContextMenu menu = new ContextMenu();
            MenuItem rename = new MenuItem("Rename");
            rename.setOnAction(e -> renameCustomOp(vm));
            MenuItem delete = new MenuItem("Delete");
            delete.setOnAction(e -> deleteCustomOp(vm));
            menu.getItems().addAll(rename, new SeparatorMenuItem(), delete);
            return menu;
        }
    }
    
    // ---utils--------------------
    public void setStatus(String text) { statusLabel.setText(text); }

    private void expandAncestors(TreeItem<?> item) {
        TreeItem<?> parent = item.getParent();
        while (parent != null) {
            parent.setExpanded(true);
            parent = parent.getParent();
        }
    }
}
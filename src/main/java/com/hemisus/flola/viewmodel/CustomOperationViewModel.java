package com.hemisus.flola.viewmodel;

import com.hemisus.flola.model.ConnectionModel;
import com.hemisus.flola.model.CustomOperation;
import com.hemisus.flola.model.CustomOperationNode;
import com.hemisus.flola.model.Graph;
import com.hemisus.flola.model.GraphNode;
import com.hemisus.flola.model.InputNode;
import com.hemisus.flola.model.OperationNode;
import com.hemisus.flola.model.OutputNode;
import com.hemisus.flola.model.Tensor;
import com.hemisus.flola.model.UtilityNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * CustomOperationNode를 캔버스/Editor에서 표시·조작하기 위한 ViewModel.
 * draft 트랜잭션(editSubGraph/editName/temp 포트맵/baseline 이름)으로 Save/Cancel 지원.
 * save()는 변경을 실제 CustomOperation에 commit하므로, 동일 operation을 참조하는
 * 모든 인스턴스가 영향받음 → MainController가 콜백에서 각 인스턴스 notifyListeners 호출.
 */
public class CustomOperationViewModel extends NodeViewModel {

    private final CustomOperationNode node;
    private final CustomOperation     operation;

    // 드래프트 상태
    private Graph  editSubGraph;
    private String editName;            // operation 이름 draft (template/canvas 공통)
    private String draftInstanceName;   // instance 이름 draft (canvas 모드 전용; null = operation 이름 폴백)
    private final Map<UtilityNode, String> baselineUtilityNames = new IdentityHashMap<>();

    // 외부 포트 재할당 임시 상태
    private final Map<ConnectionModel, Integer> tempInputPortMap  = new HashMap<>();
    private final Map<ConnectionModel, Integer> tempOutputPortMap = new HashMap<>();

    public CustomOperationViewModel(CustomOperationNode node) {
        this.node      = node;
        this.operation = node.getOperation();
        syncFromNode();
    }

    public CustomOperationNode getCustomNode() { return node; }
    public CustomOperation     getOperation()  { return operation; }

    /** 실제(commit된) 서브그래프. */
    public Graph getSubGraph() { return operation.getSubGraph(); }
    /** 편집 작업용 draft 서브그래프 — Controller가 이것에 대해 작업. */
    public Graph getEditSubGraph() { return editSubGraph; }

    public String getEditName()            { return editName; }
    public void   setEditName(String name) { this.editName = name; }

    /** instance 이름 draft. null/blank이면 operation 이름으로 폴백(표시). */
    public String getDraftInstanceName()            { return draftInstanceName; }
    public void   setDraftInstanceName(String name) {
        this.draftInstanceName = (name == null || name.isBlank()) ? null : name;
    }

    // ── 외부 포트 재할당 (OperationViewModel 동일 시그니처) ──
    public void setTempInputPort (ConnectionModel conn, int newTargetIdx) { tempInputPortMap.put (conn, newTargetIdx); }
    public void setTempOutputPort(ConnectionModel conn, int newSourceIdx) { tempOutputPortMap.put(conn, newSourceIdx); }

    public int getEffectiveInputPort (ConnectionModel conn) { return tempInputPortMap .getOrDefault(conn, conn.getTargetPortIndex()); }
    public int getEffectiveOutputPort(ConnectionModel conn) { return tempOutputPortMap.getOrDefault(conn, conn.getSourcePortIndex()); }

    public Map<ConnectionModel, Integer> getTempInputPortMap () { return tempInputPortMap;  }
    public Map<ConnectionModel, Integer> getTempOutputPortMap() { return tempOutputPortMap; }

    public void clearTempPorts() { tempInputPortMap.clear(); tempOutputPortMap.clear(); }

    // ── Draft 스칼라 상태 스냅샷 (이름 + 외부 포트맵) ──────────────────
    //
    // 에디터-내부 undo 타임라인에서 "이름 변경 / 외부 포트 재배치"를 1단위로 다루기 위한 값 스냅샷.
    // 서브그래프 구조(노드·연결·위치)는 여기 포함하지 않는다 — 그쪽은 캔버스 GraphCommand가 담당한다.
    // tempPortMap의 키(ConnectionModel)는 부모 그래프의 안정적 참조이므로 얕은 복사로 충분하다.
    public record DraftSnapshot(
        String editName,
        String draftInstanceName,
        Map<ConnectionModel, Integer> inputPorts,
        Map<ConnectionModel, Integer> outputPorts
    ) {
        public DraftSnapshot {
            inputPorts  = new HashMap<>(inputPorts);
            outputPorts = new HashMap<>(outputPorts);
        }
    }

    /** 현재 draft 스칼라 상태를 스냅샷한다. */
    public DraftSnapshot captureDraftSnapshot() {
        return new DraftSnapshot(editName, draftInstanceName, tempInputPortMap, tempOutputPortMap);
    }

    /** 스냅샷 draft 스칼라 상태를 복원한다 (undo/redo용). 서브그래프 구조는 건드리지 않는다. */
    public void restoreDraftSnapshot(DraftSnapshot s) {
        editName          = s.editName();
        draftInstanceName = s.draftInstanceName();
        tempInputPortMap.clear();  tempInputPortMap.putAll(s.inputPorts());
        tempOutputPortMap.clear(); tempOutputPortMap.putAll(s.outputPorts());
    }

    // ── editSubGraph 기반 조회 ──
    public List<InputNode> getEditInputNodes() {
        List<InputNode> r = new ArrayList<>();
        for (GraphNode n : editSubGraph.getAllNodes())
            if (n instanceof InputNode in) r.add(in);
        return r;
    }
    public List<OutputNode> getEditOutputNodes() {
        List<OutputNode> r = new ArrayList<>();
        for (GraphNode n : editSubGraph.getAllNodes())
            if (n instanceof OutputNode out) r.add(out);
        return r;
    }
    public List<Tensor> getResultTensors() { return node.getOutputs(); }

    // ── 확정 서브그래프 스냅샷 (캔버스 레벨 undo 통합용) ─────────────────
    //
    // Apply로 commit된 "공유 정의(서브그래프+op이름) + 이 인스턴스의 이름"을 1단위로 스냅샷한다.
    // 노드 객체는 공유 참조이므로(add/remove 모두 동일 객체) 참조만 보존하면 복원 가능하다.
    // 외부(부모 그래프) 연결은 여기 포함하지 않는다 — 그쪽은 Stage가 ConnSnapshot으로 캡처해
    // CustomOperationEditCommand가 ctx로 복원한다(OperationEditCommand와 동일 분리).

    /** 서브그래프 내부 연결 1개 (값 기반). */
    public record SubGraphConn(GraphNode src, int srcPort, GraphNode dst, int dstPort) {}

    public record CommittedSubGraph(
        List<GraphNode> nodes,
        Map<GraphNode, Graph.NodePosition> positions,
        List<SubGraphConn> conns,
        String operationName,
        String instanceName
    ) {
        public CommittedSubGraph {
            nodes     = new ArrayList<>(nodes);
            positions = new HashMap<>(positions);
            conns     = new ArrayList<>(conns);
        }
    }

    /** 현재 operation에 commit된 서브그래프 정의 + 인스턴스 이름을 스냅샷한다. */
    public CommittedSubGraph captureCommittedSubGraph() {
        Graph real = operation.getSubGraph();
        List<GraphNode> nodes = new ArrayList<>(real.getAllNodes());
        Map<GraphNode, Graph.NodePosition> pos = new HashMap<>();
        for (GraphNode n : nodes) {
            Graph.NodePosition p = real.getNodePosition(n);
            if (p != null) pos.put(n, p);
        }
        List<SubGraphConn> conns = new ArrayList<>();
        for (ConnectionModel c : real.getAllConnections())
            conns.add(new SubGraphConn(c.getSource(), c.getSourcePortIndex(),
                                       c.getTarget(), c.getTargetPortIndex()));
        return new CommittedSubGraph(nodes, pos, conns, operation.getName(), node.getInstanceName());
    }

    /**
     * 스냅샷 정의를 operation에 다시 적용한다 (undo/redo용). cascade·전파는 호출자(Command)가 한다.
     * draft(editSubGraph)는 건드리지 않는다 — 에디터가 닫혀 있으면 무의미하고, 다시 열 때
     * 생성자의 syncFromNode가 복원된 정의를 그대로 읽어간다.
     */
    public void applyCommittedSubGraph(CommittedSubGraph s) {
        Graph real = operation.getSubGraph();
        for (GraphNode n : new ArrayList<>(real.getAllNodes())) real.removeNode(n);
        for (GraphNode n : s.nodes()) {
            real.addNode(n);
            Graph.NodePosition p = s.positions().get(n);
            if (p != null) real.setNodePosition(n, p.x(), p.y());
        }
        for (SubGraphConn c : s.conns())
            real.connect(c.src(), c.srcPort(), c.dst(), c.dstPort());

        operation.setName(s.operationName());
        node.setInstanceName(s.instanceName());
        node.refreshPortCounts();
        node.setDirty();
    }

    // ── Save / Cancel ──
    public void save() {
        Graph realSubGraph = operation.getSubGraph();
        for (GraphNode n : new ArrayList<>(realSubGraph.getAllNodes())) realSubGraph.removeNode(n);
        for (GraphNode n : editSubGraph.getAllNodes()) {
            realSubGraph.addNode(n);
            Graph.NodePosition pos = editSubGraph.getNodePosition(n);
            if (pos != null) realSubGraph.setNodePosition(n, pos.x(), pos.y());
        }
        for (ConnectionModel c : editSubGraph.getAllConnections())
            realSubGraph.connect(c.getSource(), c.getSourcePortIndex(), c.getTarget(), c.getTargetPortIndex());

        operation.setName(editName);
        node.refreshPortCounts();
        node.setDirty();
        captureBaseline();
        tempInputPortMap.clear();
        tempOutputPortMap.clear();
    }

    public void cancel() {
        for (Map.Entry<UtilityNode, String> e : baselineUtilityNames.entrySet())
            e.getKey().setName(e.getValue());
        editSubGraph      = copySubGraph(operation.getSubGraph());
        editName          = operation.getName();
        draftInstanceName = node.getInstanceName();
        captureBaseline();
        tempInputPortMap.clear();
        tempOutputPortMap.clear();
        for (GraphNode n : operation.getSubGraph().getAllNodes())
            if (n instanceof OperationNode op) op.setDirty();
    }

    @Override
    public void syncFromNode() {
        editSubGraph      = copySubGraph(operation.getSubGraph());
        editName          = operation.getName();
        draftInstanceName = node.getInstanceName();   // canvas 모드 draft 시작값 (template 모드는 null)
        captureBaseline();
        tempInputPortMap.clear();
        tempOutputPortMap.clear();
    }

    private void captureBaseline() {
        baselineUtilityNames.clear();
        for (GraphNode n : operation.getSubGraph().getAllNodes())
            if (n instanceof UtilityNode un) baselineUtilityNames.put(un, un.getName());
    }

    /** 구조(노드+연결+위치)만 복사. 노드 객체 자체는 공유. */
    private static Graph copySubGraph(Graph original) {
        Graph copy = new Graph();
        for (GraphNode n : original.getAllNodes()) {
            copy.addNode(n);
            Graph.NodePosition pos = original.getNodePosition(n);
            if (pos != null) copy.setNodePosition(n, pos.x(), pos.y());
        }
        for (ConnectionModel c : original.getAllConnections())
            copy.connect(c.getSource(), c.getSourcePortIndex(), c.getTarget(), c.getTargetPortIndex());
        return copy;
    }

    @Override public GraphNode getNode()        { return node; }
    @Override public int       getInputCount()  { return node.getInputPortCount();  }
    @Override public int       getOutputCount() { return node.getOutputPortCount(); }
    @Override public String    getIconText()    { return "Cst"; }
    /** 공유 operation 이름 — 모든 인스턴스 동일. typeLabel 표시용. */
    public String getOperationType() { return operation.getName(); }
}
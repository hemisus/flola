package com.hemisus.flola.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 사용자가 정의한 서브그래프를 하나의 {@link OperationNode}로 감싸는 노드.
 *
 * <h3>구조 — Tensor 패턴</h3>
 * {@link TensorNode}가 {@link Tensor}를 wrap하는 것처럼, 본 클래스는
 * {@link CustomOperation}을 wrap한다. 같은 operation을 참조하는 모든 인스턴스는:
 * <ul>
 *   <li>이름·서브그래프 정의를 <b>공유</b> — 한 곳에서 바꾸면 모두 반영</li>
 *   <li>계산 결과(cachedOutputs)·dirty 상태는 인스턴스마다 <b>독립</b></li>
 * </ul>
 */
public class CustomOperationNode extends OperationNode {

    private final CustomOperation operation;
    /** 캔버스 인스턴스별 이름. null이면 operation.getName()으로 폴백. */
    private String instanceName = null;

    public CustomOperationNode(CustomOperation operation) {
        super(operation.getName());
        this.operation = operation;
        refreshPortCounts();
    }

    public CustomOperation getOperation() { return operation; }
    public Graph           getSubGraph()  { return operation.getSubGraph(); }

    /**
     * 인스턴스 이름이 설정돼 있으면 그것을, 없으면 operation 이름을 반환한다.
     * operation 이름은 getOperationType()으로도 항상 얻을 수 있다.
     */
    @Override
    public String getNodeName() {
        return (instanceName != null && !instanceName.isBlank())
               ? instanceName : getOperationType();
    }

    /**
     * 이 인스턴스의 이름만 변경한다. 공유 operation 이름은 건드리지 않는다.
     * operation 이름을 바꾸려면 {@code getOperation().setName()}을 직접 호출해야 한다.
     */
    @Override
    public void setNodeName(String name) {
        this.instanceName = (name == null || name.isBlank()) ? null : name;
    }

    public String getInstanceName() { return instanceName; }
    public void   setInstanceName(String name) { setNodeName(name); }

    @Override
    public String getOperationType() {
        return (operation != null) ? operation.getName() : super.getOperationType();
    }

    // ── Input/Output 조회 ────────────────────────────────────────

    public List<InputNode> getInputNodes() {
        List<InputNode> r = new ArrayList<>();
        for (GraphNode n : operation.getSubGraph().getAllNodes()) {
            if (n instanceof InputNode in) r.add(in);
        }
        return r;
    }

    public List<OutputNode> getOutputNodes() {
        List<OutputNode> r = new ArrayList<>();
        for (GraphNode n : operation.getSubGraph().getAllNodes()) {
            if (n instanceof OutputNode out) r.add(out);
        }
        return r;
    }

    public void refreshPortCounts() {
        this.inputPortCount  = getInputNodes().size();
        this.outputPortCount = getOutputNodes().size();
    }

    // ── compute ──────────────────────────────────────────────────

    @Override
    public void compute(List<Tensor> inputs) {
        if (!isDirty) return;

        List<InputNode>  inputNodes  = getInputNodes();
        List<OutputNode> outputNodes = getOutputNodes();
        Graph            subGraph    = operation.getSubGraph();

        // 1) 입력 주입
        for (int i = 0; i < inputNodes.size(); i++) {
            Tensor in = (inputs != null && i < inputs.size()) ? inputs.get(i) : null;
            inputNodes.get(i).setTensor(in);
        }

        // 2) 서브그래프 평가
        for (GraphNode n : subGraph.getAllNodes()) {
            if (n instanceof OperationNode op) op.setDirty();
        }
        evaluateSubGraphTopologically();

        // 3) OutputNode가 받은 텐서를 깊은 복사하여 cachedOutputs에 저장
        cachedOutputs.clear();
        for (OutputNode out : outputNodes) {
            Tensor t = readIncomingTensor(out);
            cachedOutputs.add(t == null ? null : t.makeCopy());
        }

        this.isDirty = false;
    }

    private Tensor readIncomingTensor(OutputNode out) {
        Graph subGraph = operation.getSubGraph();
        List<ConnectionModel> conns = subGraph.getInputsFor(out);
        if (conns.isEmpty()) return null;
        ConnectionModel c = conns.get(0);
        return c.getSource().getOutputValue(c.getSourcePortIndex());
    }

    private void evaluateSubGraphTopologically() {
        Graph           subGraph  = operation.getSubGraph();
        Set<GraphNode>  visited   = new HashSet<>();
        List<GraphNode> postOrder = new ArrayList<>();

        for (GraphNode n : subGraph.getAllNodes()) {
            if (subGraph.getInputsFor(n).isEmpty()) {
                dfsPostOrder(n, visited, postOrder);
            }
        }
        for (GraphNode n : subGraph.getAllNodes()) {
            if (!visited.contains(n)) dfsPostOrder(n, visited, postOrder);
        }

        Collections.reverse(postOrder);
        for (GraphNode n : postOrder) {
            if (n instanceof OperationNode op) subGraph.evaluateNode(op);
        }
    }

    private void dfsPostOrder(GraphNode node, Set<GraphNode> visited, List<GraphNode> out) {
        if (visited.contains(node)) return;
        visited.add(node);
        for (ConnectionModel c : operation.getSubGraph().getOutputsFrom(node)) {
            dfsPostOrder(c.getTarget(), visited, out);
        }
        out.add(node);
    }

    // ── 순환 참조 방지 ─────────────────────────────────────────

    public static boolean wouldCreateCycle(Graph targetSubGraph, CustomOperationNode candidate) {
        if (candidate == null || targetSubGraph == null) return false;
        return containsGraphReference(candidate.getSubGraph(), targetSubGraph, new HashSet<>());
    }

    private static boolean containsGraphReference(Graph current, Graph target, Set<Graph> seen) {
        if (current == target) return true;
        if (!seen.add(current)) return false;
        for (GraphNode n : current.getAllNodes()) {
            if (n instanceof CustomOperationNode cn) {
                if (containsGraphReference(cn.getSubGraph(), target, seen)) return true;
            }
        }
        return false;
    }
}

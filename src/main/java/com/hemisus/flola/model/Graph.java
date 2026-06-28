package com.hemisus.flola.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Graph {
    private List<GraphNode> nodes = new ArrayList<>();
    private List<ConnectionModel> connections = new ArrayList<>();
    private Map<GraphNode, List<ConnectionModel>> connectionsTo = new HashMap<>();
    private Map<GraphNode, List<ConnectionModel>> connectionsFrom = new HashMap<>();

    /**
     * 노드 화면 좌표 저장 (Editor 재오픈 시 레이아웃 보존용).
     * AWT 의존성을 피하기 위해 record로 정의한다.
     */
    public record NodePosition(int x, int y) {}
    private final Map<GraphNode, NodePosition> nodePositions = new HashMap<>();

    public interface NodeRemovalListener {
        void onNodeRemoved(GraphNode removedNode, List<GraphNode> affectedChildNodes);
    }

    private NodeRemovalListener removalListener;

    public void setRemovalListener(NodeRemovalListener listener) {
        this.removalListener = listener;
    }

    public void addNode(GraphNode node) {
        nodes.add(node);
    }

    public void connect(GraphNode src, int srcIdx, GraphNode dest, int destIdx) {
        if (srcIdx == -1) {
            srcIdx = getNextAvailableOutputIndex(src);
        }
        if (srcIdx < 0 || srcIdx >= src.getOutputPortCount()) {
            throw new IllegalArgumentException("Invalid source port index: " + srcIdx);
        }

        if (destIdx == -1) {
            destIdx = getNextAvailableInputIndex(dest);
        }

        removeConnectionTo(dest, destIdx);
        ConnectionModel newConn = new ConnectionModel(src, srcIdx, dest, destIdx);
        connections.add(newConn);
        connectionsTo.computeIfAbsent(dest, k -> new ArrayList<>()).add(newConn);
        connectionsFrom.computeIfAbsent(src, k -> new ArrayList<>()).add(newConn);

        if (dest instanceof OperationNode op) {
            op.setDirty();
            if (op instanceof GenericOperationNode genOp && genOp.isInputVariadic()) {
                genOp.setInputPortCount(getInputsFor(dest).size());
            }
        }
    }

    private int getNextAvailableInputIndex(GraphNode dest) {
        List<ConnectionModel> currentInputs = connectionsTo.get(dest);
        return (currentInputs == null) ? 0 : currentInputs.size();
    }

    /** variadic output 포트(index=-1)에서 드래그할 때 사용할 다음 출력 포트 인덱스를 반환한다. */
    private int getNextAvailableOutputIndex(GraphNode src) {
        List<ConnectionModel> currentOutputs = connectionsFrom.get(src);
        if (currentOutputs == null || currentOutputs.isEmpty()) return 0;
        int max = currentOutputs.stream()
            .mapToInt(ConnectionModel::getSourcePortIndex)
            .max().orElse(-1);
        return max + 1;
    }

    // ── 연결 제거 ──────────────────────────────────────

    public void removeConnectionTo(GraphNode dest, int destIdx) {
        List<ConnectionModel> inputs = connectionsTo.get(dest);
        if (inputs == null) return;

        ConnectionModel found = null;
        for (ConnectionModel c : inputs) {
            if (c.getTarget() == dest && c.getTargetPortIndex() == destIdx) {
                found = c;
                break;
            }
        }
        if (found != null) {
            connections.remove(found);
            inputs.remove(found);

            List<ConnectionModel> outputs = connectionsFrom.get(found.getSource());
            if (outputs != null) outputs.remove(found);

            if (dest instanceof GenericOperationNode genOp && genOp.isInputVariadic()) {
                reorderVariadicInputs(genOp, inputs);
            }
            if (dest instanceof OperationNode op) {
                op.setDirty();
            }
        }
    }

    private void reorderVariadicInputs(GenericOperationNode node, List<ConnectionModel> inputs) {
        inputs.sort((c1, c2) -> Integer.compare(c1.getTargetPortIndex(), c2.getTargetPortIndex()));
        for (int i = 0; i < inputs.size(); i++) {
            ConnectionModel oldConn = inputs.get(i);
            if (oldConn.getTargetPortIndex() != i) {
                connections.remove(oldConn);
                List<ConnectionModel> sourceOutputs = connectionsFrom.get(oldConn.getSource());
                if (sourceOutputs != null) sourceOutputs.remove(oldConn);

                ConnectionModel newConn = new ConnectionModel(
                    oldConn.getSource(), oldConn.getSourcePortIndex(),
                    oldConn.getTarget(), i
                );
                connections.add(newConn);
                inputs.set(i, newConn);
                if (sourceOutputs != null) sourceOutputs.add(newConn);
            }
        }
        node.setInputPortCount(inputs.size());
    }

    private void removeSpecificConnection(ConnectionModel conn) {
        connections.remove(conn);

        List<ConnectionModel> fromList = connectionsFrom.get(conn.getSource());
        if (fromList != null) fromList.remove(conn);

        List<ConnectionModel> toList = connectionsTo.get(conn.getTarget());
        if (toList != null) {
            toList.remove(conn);
            if (conn.getTarget() instanceof GenericOperationNode genOp && genOp.isInputVariadic()) {
                reorderVariadicInputs(genOp, toList);
            }
        }
    }

    // ── 노드 제거 ──────────────────────────────────────

    public void removeNode(GraphNode node) {
        Set<GraphNode> affectedSet = new HashSet<>();
        for (ConnectionModel conn : getOutputsFrom(node)) {
            affectedSet.add(conn.getTarget());
        }

        for (ConnectionModel conn : new ArrayList<>(getInputsFor(node))) {
            List<ConnectionModel> parentOutputs = connectionsFrom.get(conn.getSource());
            if (parentOutputs != null) parentOutputs.remove(conn);
            connections.remove(conn);
        }

        List<ConnectionModel> outputConns = new ArrayList<>(getOutputsFrom(node));
        connectionsFrom.remove(node);
        for (ConnectionModel conn : outputConns) {
            GraphNode child = conn.getTarget();
            List<ConnectionModel> childInputs = connectionsTo.get(child);
            if (childInputs != null) {
                childInputs.remove(conn);
                if (child instanceof OperationNode op) op.setDirty();
            }
            connections.remove(conn);
        }
        for (GraphNode child : affectedSet) {
            if (child instanceof GenericOperationNode genOp && genOp.isInputVariadic()) {
                List<ConnectionModel> inputs = connectionsTo.get(child);
                if (inputs != null) reorderVariadicInputs(genOp, inputs);
            }
        }

        nodes.remove(node);
        connectionsTo.remove(node);
        nodePositions.remove(node);  // ← 좌표도 정리

        if (removalListener != null) {
            removalListener.onNodeRemoved(node, new ArrayList<>(affectedSet));
        }
    }

    // ── 평가 ───────────────────────────────────────────

    public void evaluateNode(OperationNode node) {
        if (node.isDirty()) {
            node.compute(getIncomingTensors(node));
        }
    }

    // ── 포트 정리 ──────────────────────────────────────

    public void sanitizeOutputConnections(GraphNode node) {
        List<ConnectionModel> outputs = connectionsFrom.get(node);
        if (outputs == null) return;

        int maxPort = node.getOutputPortCount();
        List<ConnectionModel> toRemove = new ArrayList<>();
        for (ConnectionModel conn : outputs) {
            if (conn.getSourcePortIndex() >= maxPort) toRemove.add(conn);
        }
        for (ConnectionModel conn : toRemove) {
            removeSpecificConnection(conn);
        }
    }

    // ── 포트 재할당 ────────────────────────────────────

    public void batchReassignInputPorts(GraphNode dest, Map<ConnectionModel, Integer> reassignments) {
        if (reassignments == null || reassignments.isEmpty()) return;

        GenericOperationNode genOp =
            (dest instanceof GenericOperationNode g && g.isInputVariadic()) ? g : null;
        boolean variadic = genOp != null;

        // 1) 재배치 대상 연결들을 reorder 트리거 없이 직접 제거
        for (ConnectionModel conn : reassignments.keySet()) {
            connections.remove(conn);
            List<ConnectionModel> from = connectionsFrom.get(conn.getSource());
            if (from != null) from.remove(conn);
            List<ConnectionModel> to = connectionsTo.get(conn.getTarget());
            if (to != null) to.remove(conn);
        }

        List<ConnectionModel> destInputs = connectionsTo.computeIfAbsent(dest, k -> new ArrayList<>());

        // 2) 고정 arity 노드만: 목표 인덱스에 남아있던(맵에 없던) 연결을 비켜 중복 방지.
        //    variadic은 4)의 reorder가 압축·정리하므로 여기서 제거하지 않는다(데이터 손실 방지).
        if (!variadic) {
            for (int newIdx : reassignments.values()) {
                ConnectionModel occ = null;
                for (ConnectionModel c : destInputs)
                    if (c.getTargetPortIndex() == newIdx) { occ = c; break; }
                if (occ != null) {
                    connections.remove(occ);
                    destInputs.remove(occ);
                    List<ConnectionModel> from = connectionsFrom.get(occ.getSource());
                    if (from != null) from.remove(occ);
                }
            }
        }

        // 3) 새 인덱스로 연결 재생성 (reorder 트리거 없이 직접 추가)
        for (Map.Entry<ConnectionModel, Integer> entry : reassignments.entrySet()) {
            ConnectionModel old = entry.getKey();
            ConnectionModel n = new ConnectionModel(
                old.getSource(), old.getSourcePortIndex(), dest, entry.getValue());
            connections.add(n);
            destInputs.add(n);
            connectionsFrom.computeIfAbsent(old.getSource(), k -> new ArrayList<>()).add(n);
        }

        // 4) variadic이면 마지막에 한 번만 정렬·포트수 정리
        //    (이 시점엔 맵 키를 더 참조하지 않으므로 객체 교체가 안전)
        if (variadic) reorderVariadicInputs(genOp, destInputs);

        if (dest instanceof OperationNode op) op.setDirty();
    }

    /**
     * 소스 포트 번호만 변경한다. 타겟 포트는 그대로 유지된다.
     *
     * <p>기존 {@code removeSpecificConnection}을 사용하면 variadic 타겟 노드에 대해
     * {@link #reorderVariadicInputs}가 트리거되어 타겟의 포트 번호가 재정렬된다.
     * 이 메서드는 SOURCE 측 정보만 직접 제거하여 타겟 측 재정렬을 방지한다.
     */
    public void batchReassignOutputPorts(GraphNode src, Map<ConnectionModel, Integer> reassignments) {
        for (Map.Entry<ConnectionModel, Integer> entry : reassignments.entrySet()) {
            ConnectionModel old = entry.getKey();
            int newSrcIdx = entry.getValue();
            if (newSrcIdx < 0 || newSrcIdx >= src.getOutputPortCount()) continue;

            connections.remove(old);
            List<ConnectionModel> fromList = connectionsFrom.get(old.getSource());
            if (fromList != null) fromList.remove(old);

            List<ConnectionModel> toList = connectionsTo.get(old.getTarget());
            if (toList != null) toList.remove(old);

            ConnectionModel newConn = new ConnectionModel(
                src, newSrcIdx, old.getTarget(), old.getTargetPortIndex());
            connections.add(newConn);
            connectionsFrom.computeIfAbsent(src, k -> new ArrayList<>()).add(newConn);
            if (toList != null) {
                toList.add(newConn);
            } else {
                connectionsTo.computeIfAbsent(old.getTarget(), k -> new ArrayList<>()).add(newConn);
            }

            if (old.getTarget() instanceof OperationNode op) op.setDirty();
        }
    }

    public void reassignInputPort(ConnectionModel conn, int newTargetIdx) {
        if (conn.getTargetPortIndex() == newTargetIdx) return;

        GraphNode src  = conn.getSource();
        int srcIdx     = conn.getSourcePortIndex();
        GraphNode dest = conn.getTarget();

        removeConnectionTo(dest, newTargetIdx);
        removeSpecificConnection(conn);

        ConnectionModel newConn = new ConnectionModel(src, srcIdx, dest, newTargetIdx);
        connections.add(newConn);
        connectionsTo.computeIfAbsent(dest, k -> new ArrayList<>()).add(newConn);
        connectionsFrom.computeIfAbsent(src, k -> new ArrayList<>()).add(newConn);

        if (dest instanceof OperationNode op) op.setDirty();
    }

    public void reassignOutputPort(ConnectionModel conn, int newSourceIdx) {
        if (conn.getSourcePortIndex() == newSourceIdx) return;

        GraphNode src  = conn.getSource();
        GraphNode dest = conn.getTarget();
        int destIdx    = conn.getTargetPortIndex();

        if (newSourceIdx < 0 || newSourceIdx >= src.getOutputPortCount()) return;

        connections.remove(conn);
        List<ConnectionModel> fromList = connectionsFrom.get(src);
        if (fromList != null) fromList.remove(conn);
        List<ConnectionModel> toList = connectionsTo.get(dest);
        if (toList != null) toList.remove(conn);

        ConnectionModel newConn = new ConnectionModel(src, newSourceIdx, dest, destIdx);
        connections.add(newConn);
        connectionsFrom.computeIfAbsent(src, k -> new ArrayList<>()).add(newConn);
        if (toList != null) {
            toList.add(newConn);
        } else {
            connectionsTo.computeIfAbsent(dest, k -> new ArrayList<>()).add(newConn);
        }

        if (dest instanceof OperationNode op) op.setDirty();
    }

    // ── 조회 ───────────────────────────────────────────

    public List<ConnectionModel> getInputsFor(GraphNode targetNode) {
        return connectionsTo.getOrDefault(targetNode, Collections.emptyList());
    }

    public List<ConnectionModel> getOutputsFrom(GraphNode sourceNode) {
        return connectionsFrom.getOrDefault(sourceNode, Collections.emptyList());
    }

    public List<Tensor> getIncomingTensors(GraphNode targetNode) {
        int count = targetNode.getInputPortCount();
        List<Tensor> inputs = new ArrayList<>(Collections.nCopies(count, null));

        for (ConnectionModel c : getInputsFor(targetNode)) {
            Tensor value = c.getSource().getOutputValue(c.getSourcePortIndex());
            int idx = c.getTargetPortIndex();
            if (idx >= 0 && idx < count) {
                inputs.set(idx, value);
            }
        }
        return inputs;
    }

    public List<GraphNode> getAllNodes() {
        return Collections.unmodifiableList(nodes);
    }

    public List<ConnectionModel> getAllConnections() {
        return Collections.unmodifiableList(connections);
    }

    // ── 노드 좌표 ──────────────────────────────────────

    public void setNodePosition(GraphNode n, int x, int y) {
        nodePositions.put(n, new NodePosition(x, y));
    }

    public NodePosition getNodePosition(GraphNode n) {
        return nodePositions.get(n);
    }
}
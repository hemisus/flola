package com.hemisus.flola.viewmodel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Stack;

import com.hemisus.flola.model.*;
import com.hemisus.flola.utils.TensorOperations;

public class OperationViewModel extends NodeViewModel {
    private OperationNode node;
    private String draftNodeName;
    private Map<String, Object> editParams;
    private List<Tensor> tempOutputs;
    public record InputConnKey(GraphNode source, int sourcePortIndex) {}
    public record OutputConnKey(GraphNode target, int targetPortIndex) {}
    // ── Undo / Redo ──────────────────────────────────────
    private final Stack<Snapshot> undoStack = new Stack<>();
    private final Stack<Snapshot> redoStack = new Stack<>();
    
    private Map<InputConnKey, Integer> tempInputPortMap  = new HashMap<>();
    private Map<OutputConnKey, Integer> tempOutputPortMap = new HashMap<>();
    

    /**
     * 에디터 드래프트 상태의 스냅샷.
     * tempOutputs는 포함하지 않는다 — 외부 Tensor 변경 시
     * 과거 스냅샷의 미리보기가 stale 해지므로, undo 시 현재 입력으로 재계산한다.
     */
    private record Snapshot(
    	    String nodeName,
    	    Map<String, Object> params,
    	    Map<InputConnKey, Integer> inputPorts,
    	    Map<OutputConnKey, Integer> outputPorts) 
    {
    	Snapshot {
    	    params      = new HashMap<>(params);
    	    inputPorts  = new HashMap<>(inputPorts);
    	    outputPorts = new HashMap<>(outputPorts);
    	}
    }

    private Snapshot takeSnapshot() {
        return new Snapshot(draftNodeName, editParams, tempInputPortMap, tempOutputPortMap);
    }

    private void restoreSnapshot(Snapshot snap) {
        draftNodeName    = snap.nodeName;
        editParams       = new HashMap<>(snap.params);
        tempInputPortMap = new HashMap<>(snap.inputPorts);
        tempOutputPortMap = new HashMap<>(snap.outputPorts);
        // tempOutputs는 복원하지 않음 — 호출자가 recomputePreview()로 재계산해야 한다
    }

    // ── 생성자 ───────────────────────────────────────────

    public OperationViewModel(OperationNode node) {
        super();
        this.node = node;
        this.syncFromNode();
        notifyListeners();
    }

    @Override
    public void syncFromNode() {
        draftNodeName = node.getNodeName();
        if (node instanceof GenericOperationNode genNode) {
            this.editParams = new HashMap<>(genNode.getParams());
        } else {
            this.editParams = new HashMap<>();
        }
        List<Tensor> outputs = node.getOutputs();
        this.tempOutputs = (outputs != null) ? outputs : new ArrayList<>();
        tempInputPortMap.clear();
        tempOutputPortMap.clear();
    }

    // ── 노드 이름 (draft) ────────────────────────────────

    public String getDraftNodeName() { return draftNodeName; }

    public void setDraftNodeName(String name) {
        if (Objects.equals(draftNodeName, name)) return;
        undoStack.push(takeSnapshot());
        redoStack.clear();
        draftNodeName = name;
        notifyEditorListeners();
    }

    // ── 포트 임시 변경 ──────────────────────────────────

    public void setTempInputPort(ConnectionModel conn, int newTargetIdx, List<Tensor> reorderedInputs) {
    	InputConnKey key = new InputConnKey(conn.getSource(), conn.getSourcePortIndex());
        int effective = tempInputPortMap.containsKey(key) ? tempInputPortMap.get(key) : conn.getTargetPortIndex();
        if (effective == newTargetIdx) return;
        
        undoStack.push(takeSnapshot());
        redoStack.clear();
        tempInputPortMap.put(key, newTargetIdx);
        
        if (node instanceof GenericOperationNode genNode) {
            this.tempOutputs = genNode.getOperation().apply(reorderedInputs, editParams);
        }
    }

    /** 출력 포트 임시 변경 (라우팅만 바뀌므로 재계산 불필요) */
    public void setTempOutputPort(ConnectionModel conn, int newSourceIdx) {
        OutputConnKey key = new OutputConnKey(conn.getTarget(), conn.getTargetPortIndex());
        int effective = tempOutputPortMap.containsKey(key) ? tempOutputPortMap.get(key) : conn.getSourcePortIndex();
        if (effective == newSourceIdx) return;
        
        undoStack.push(takeSnapshot());
        redoStack.clear();
        tempOutputPortMap.put(key, newSourceIdx);
    }

    // ── 실효 포트 인덱스 조회 (드래프트 우선) ──────────

    public int getEffectiveInputPort(ConnectionModel conn) {
        InputConnKey key = new InputConnKey(conn.getSource(), conn.getSourcePortIndex());
        return tempInputPortMap.getOrDefault(key, conn.getTargetPortIndex());
    }

    public int getEffectiveOutputPort(ConnectionModel conn) {
        OutputConnKey key = new OutputConnKey(conn.getTarget(), conn.getTargetPortIndex());
        return tempOutputPortMap.getOrDefault(key, conn.getSourcePortIndex());
    }

    public Map<InputConnKey, Integer> getTempInputPortMap()  { return tempInputPortMap; }
    public Map<OutputConnKey, Integer> getTempOutputPortMap() { return tempOutputPortMap; }

    // ── 파라미터 편집 ───────────────────────────────────

    public void setEditParam(String key, Object value, List<Tensor> inputs) {
        Object current = editParams.get(key);
        if (Objects.equals(current, value)) return;
        undoStack.push(takeSnapshot());
        redoStack.clear();
        editParams.put(key, value);
        if (node instanceof GenericOperationNode genNode) {
            this.tempOutputs = genNode.getOperation().apply(inputs, editParams);
        }
    }

    // ── Undo / Redo ────────────────────────────────────────────

    public void undo() {
        if (undoStack.isEmpty()) return;
        redoStack.push(takeSnapshot());
        restoreSnapshot(undoStack.pop());
        notifyEditorListeners();   // 에디터만 — 캔버스 캐스케이드 트리거 금지
    }

    public void redo() {
        if (redoStack.isEmpty()) return;
        undoStack.push(takeSnapshot());
        restoreSnapshot(redoStack.pop());
        notifyEditorListeners();   // 에디터만 — 캔버스 캐스케이드 트리거 금지
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }

    /**
     * tempOutputs를 현재 editParams + 주어진 inputs로 재계산한다.
     * undo/redo 후 호출 — 외부 Tensor 변경에도 항상 최신 입력을 반영한다.
     */
    public void recomputePreview(List<Tensor> inputs) {
        if (node instanceof GenericOperationNode genNode) {
            try {
                this.tempOutputs = genNode.getOperation().apply(inputs, editParams);
            } catch (Exception e) {
                this.tempOutputs = new ArrayList<>();
            }
        }
    }

    // ── 저장 / 취소 ─────────────────────────────────────

    public void save() {
        if (node instanceof GenericOperationNode genNode) {
            editParams.forEach(genNode::setParam);
            updatePortCountIfNeeded(genNode);
            genNode.setDirty();
        }
        if (draftNodeName != null && !draftNodeName.isEmpty()) {
            node.setNodeName(draftNodeName);
        }
        undoStack.clear();
        redoStack.clear();
        notifyListeners();
    }

    public void cancel() {
        syncFromNode();
        undoStack.clear();
        redoStack.clear();
        notifyEditorListeners();   // 미저장 드래프트 복원 — 캔버스 캐스케이드 불필요
    }

    // ── 확정 상태 스냅샷 (캔버스 undo 통합용) ──────────────

    /**
     * save로 노드에 확정된 상태의 스냅샷.
     * Apply가 바꿀 수 있는 노드 내부 상태(params·이름·출력 포트 수·outputVariadic)만 담는다.
     * (포트 재할당 같은 그래프 연결 변경은 포함하지 않는다.)
     */
    public record CommittedState(
        Map<String, Object> params,
        String nodeName,
        int outputPortCount,
        boolean outputVariadic
    ) {
        public CommittedState {
            params = new HashMap<>(params);
        }
    }

    /** 현재 노드에 확정된 상태를 스냅샷한다. */
    public CommittedState captureCommittedState() {
        Map<String, Object> p = (node instanceof GenericOperationNode g)
            ? new HashMap<>(g.getParams()) : new HashMap<>();
        return new CommittedState(p, node.getNodeName(),
            node.getOutputPortCount(), node.isOutputVariadic());
    }

    /** 스냅샷 상태를 노드에 다시 적용한다 (undo/redo용). cascade는 호출자가 한다. */
    public void applyCommittedState(CommittedState s) {
        if (node instanceof GenericOperationNode g) {
            s.params().forEach(g::setParam);
            g.setOutputVariadic(s.outputVariadic());
            g.setOutputPortCount(s.outputPortCount());
            g.setDirty();
        }
        if (s.nodeName() != null && !s.nodeName().isEmpty()) {
            node.setNodeName(s.nodeName());
        }
        syncFromNode();   // 드래프트를 복원된 노드 상태로 재동기화
    }

    private void updatePortCountIfNeeded(OperationNode genNode) {
        if (!"Split".equals(genNode.getOperationType())) return;

        TensorOperations.SplitType type =
            (TensorOperations.SplitType) editParams.get("type");

        if (type == TensorOperations.SplitType.CHUNK_SIZE) {
            genNode.setOutputVariadic(true);
            int count = (tempOutputs != null && !tempOutputs.isEmpty()) ? tempOutputs.size() : 1;
            genNode.setOutputPortCount(count);
        } else {
            genNode.setOutputVariadic(false);
            int val = (int) editParams.get("value");
            genNode.setOutputPortCount(val);
        }
    }

    // ── 조회 ────────────────────────────────────────────

    /** 현재 노드의 확정 출력 텐서 목록 */
    public List<Tensor> getResultTensors() { return node.getOutputs(); }

    public boolean isDirty()          { return node.isDirty(); }
    public boolean isInputVariadic()  { return node.isInputVariadic(); }
    public boolean isOutputVariadic() { return node.isOutputVariadic(); }

    public List<Tensor> getTempOutputs()          { return tempOutputs; }
    public Object       getEditParam(String key)  { return editParams.get(key); }

    @Override public OperationNode getNode()    { return node; }
    @Override public String getIconText()       { return "Opr"; }
    @Override public int getInputCount()        { return node.getInputPortCount(); }
    @Override public int getOutputCount()       { return node.getOutputPortCount(); }
    /** 연산의 종류 (사용자가 바꿀 수 없는 식별자) */
    public String getOperationType() { return node.getOperationType(); }
}
package com.hemisus.flola.viewmodel;

import com.hemisus.flola.model.Tensor;
import com.hemisus.flola.model.TensorNode;
import com.hemisus.flola.utils.DataConverter;
import java.util.Stack;
import java.util.Arrays;

public class TensorViewModel extends NodeViewModel {
    private TensorNode node;
    private Tensor originalTensor;
    private Tensor editTensor;
    private String draftNodeName;
    private final Stack<Snapshot> undoStack = new Stack<>();
    private final Stack<Snapshot> redoStack = new Stack<>();
    private String valueString;

    private int rowAxis;
    private int colAxis;
    private int[] fixedIndices;

    // ── Undo / Redo 스냅샷 ────────────────────────────────
    /** 에디터 드래프트의 완전한 상태: 텐서(shape·data·axisNames·name) + 노드 이름 */
    private record Snapshot(Tensor tensor, String nodeName) {}

    private Snapshot takeSnapshot() {
        return new Snapshot(editTensor.makeCopy(), draftNodeName);
    }

    private void restoreSnapshot(Snapshot s) {
        editTensor    = s.tensor().makeCopy();   // 독립 복사본 (스택 보존)
        draftNodeName = s.nodeName();
        validateViewAxes();
    }

    public TensorViewModel(TensorNode node) {
        this.node = node;
        this.originalTensor = node.getTensor();
        this.draftNodeName  = node.getNodeName();

        this.originalTensor.addChangeListener(() -> {
            this.syncFromNode();
            this.editTensor    = originalTensor.makeCopy();
            this.draftNodeName = node.getNodeName();
            validateViewAxes();
            undoStack.clear();
            redoStack.clear();
            this.notifyListeners();   // 외부 텐서 변경 → 캔버스 + 에디터 모두 갱신
        });

        this.editTensor = originalTensor.makeCopy();
        resetViewAxes();
        syncFromNode();
    }

    private void resetViewAxes() {
        int rank = editTensor.getRank();
        fixedIndices = new int[rank];
        if (rank >= 2) {
            rowAxis = rank - 2;
            colAxis = rank - 1;
        } else {
            rowAxis = 0;
            colAxis = 0;
        }
    }

    private void validateViewAxes() {
        int rank = editTensor.getRank();
        if (rank < 2) {
            rowAxis = 0;
            colAxis = 0;
            fixedIndices = new int[rank];
            return;
        }
        if (rowAxis >= rank || colAxis >= rank || rowAxis == colAxis) {
            rowAxis = rank - 2;
            colAxis = rank - 1;
        }
        fixedIndices = new int[rank];
    }

    // ── 뷰 축 제어 ────────────────────────────────────────────

    public void setRowAxis(int axis) {
        this.rowAxis = axis;
        if (colAxis == axis) colAxis = findOtherAxis(axis);
    }

    public void setColAxis(int axis) {
        this.colAxis = axis;
        if (rowAxis == axis) rowAxis = findOtherAxis(axis);
    }

    private int findOtherAxis(int occupied) {
        for (int i = 0; i < editTensor.getRank(); i++) {
            if (i != occupied) return i;
        }
        return 0;
    }

    public void setFixedIndex(int axis, int index) {
        if (axis < 0 || axis >= fixedIndices.length) return;
        fixedIndices[axis] = Math.max(0, Math.min(index, editTensor.getDim(axis) - 1));
    }

    public double[][] getCurrentSlice() {
        return editTensor.to2DArray(rowAxis, colAxis, fixedIndices);
    }

    // ── 셀 편집 ────────────────────────────────────────────────

    public void setSliceValue(int r, int c, double val) {
        int rank = editTensor.getRank();
        int[] idx = null;

        if (rank >= 2) {
            idx = fixedIndices.clone();
            idx[rowAxis] = r;
            idx[colAxis] = c;
        }

        // 현재 값과 비교 — 같으면 스택에 안 쌓음 (Enter + 포커스 해제 중복 방지)
        double cur = (rank == 0) ? editTensor.getFlat(0)
                   : (rank == 1) ? editTensor.getFlat(c)
                   : editTensor.get(idx);
        if (cur == val) return;

        undoStack.push(takeSnapshot());

        if (rank == 0)      editTensor.setFlat(0, val);
        else if (rank == 1) editTensor.setFlat(c, val);
        else                editTensor.set(val, idx);

        redoStack.clear();
        // 셀 편집은 고빈도 — 풀 리빌드 없이 핸들러(updateCell)가 부분 갱신한다.
    }

    // ── 축 편집 ────────────────────────────────────────────────

    /**
     * 마지막 축을 제거한다 (rank가 1 이하이면 무시).
     * 축 이름은 앞쪽 (rank-1)개를 유지한다.
     */
    public void removeLastAxis() {
        int[] oldShape = editTensor.getShape();
        if (oldShape.length <= 1) return;

        String[] oldNames = editTensor.getAxisNames();  // ← NEW
        int[] newShape = Arrays.copyOf(oldShape, oldShape.length - 1);

        undoStack.push(takeSnapshot());
        editTensor = buildReshaped(editTensor, newShape);

        // 앞쪽 이름 보존 (마지막 이름 제거)
        editTensor.setAxisNames(Arrays.copyOf(oldNames, newShape.length));  // ← NEW

        validateViewAxes();
        redoStack.clear();
        notifyEditorListeners();
    }

    /**
     * 뒤에 크기 {@code dimSize}인 새 축을 추가한다.
     * 새 축에는 기본 이름 "axis_N"이 부여된다.
     */
    public void addAxis(int dimSize) {
        String[] oldNames = editTensor.getAxisNames();  // ← NEW
        int[] oldShape = editTensor.getShape();
        int[] newShape = Arrays.copyOf(oldShape, oldShape.length + 1);
        newShape[newShape.length - 1] = dimSize;

        undoStack.push(takeSnapshot());
        editTensor = buildReshaped(editTensor, newShape);

        // 기존 이름 보존 + 새 축에 기본 이름 부여
        String[] newNames = Arrays.copyOf(oldNames, newShape.length);          // ← NEW
        newNames[newShape.length - 1] = "axis_" + (newShape.length - 1);       // ← NEW
        editTensor.setAxisNames(newNames);                                       // ← NEW

        validateViewAxes();
        redoStack.clear();
        notifyEditorListeners();
    }

    /**
     * 편집 텐서의 각 축 크기를 {@code newShape}로 변경한다.
     * rank가 같으면 기존 축 이름을 보존한다.
     */
    public void reshapeEditTensor(int[] newShape) {
        String[] oldNames = editTensor.getAxisNames();  // ← NEW
        undoStack.push(takeSnapshot());
        editTensor = buildReshaped(editTensor, newShape);

        // rank가 같으면 이름 그대로 보존 (스피너로 크기만 바꾸는 경우)
        if (oldNames.length == newShape.length) {                              // ← NEW
            editTensor.setAxisNames(oldNames);                                 // ← NEW
        }                                                                      // ← NEW

        validateViewAxes();
        redoStack.clear();
        notifyEditorListeners();
    }

    /**
     * old 텐서의 데이터를 newShape으로 복사한다.
     * (축 이름은 호출 측에서 별도로 관리한다)
     */
    private static Tensor buildReshaped(Tensor old, int[] newShape) {
        int[] oldShape = old.getShape();
        int newRank    = newShape.length;
        int oldRank    = oldShape.length;

        Tensor reshaped = new Tensor(old.getName(), newShape);

        // 원소 수가 같으면 순수 reshape → 행우선(flat) 순서 그대로 복사해 데이터 보존.
        // 예: (3,3) → (3,3,1) / (9) / (1,9) 등은 값이 그대로 유지된다.
        int oldTotal = 1; for (int d : oldShape) oldTotal *= d;
        int newTotal = 1; for (int d : newShape) newTotal *= d;
        if (oldTotal == newTotal) {
            for (int i = 0; i < newTotal; i++) reshaped.setFlat(i, old.getFlat(i));
            return reshaped;
        }

        // 원소 수가 다르면(리사이즈) 뒤 정렬로 겹치는 영역만 복사하고 나머지는 0.
        int[] copyLimits = new int[newRank];
        for (int i = 0; i < newRank; i++) {
            int oi = oldRank - newRank + i;
            copyLimits[i] = (oi < 0) ? 1 : Math.min(newShape[i], oldShape[oi]);
        }

        int copyTotal = 1;
        for (int d : copyLimits) copyTotal *= d;

        int[] newIdx = new int[newRank];
        int[] oldIdx = new int[oldRank];

        for (int flat = 0; flat < copyTotal; flat++) {
            int temp = flat;
            for (int i = newRank - 1; i >= 0; i--) {
                newIdx[i] = temp % copyLimits[i];
                temp /= copyLimits[i];
            }
            for (int i = 0; i < oldRank; i++) {
                int ni = newRank - oldRank + i;
                oldIdx[i] = (ni < 0) ? 0 : newIdx[ni];
            }
            reshaped.set(old.get(oldIdx), newIdx);
        }

        return reshaped;
    }

    public void applyValueString(String input) {
        String[] savedNames = editTensor.getAxisNames();  // ← NEW: 이름 미리 저장
        Tensor parsed = DataConverter.stringToTensor(input);
        if (parsed == null) throw new IllegalArgumentException("Invalid tensor format");

        undoStack.push(takeSnapshot());
        editTensor.copyValuesFrom(parsed);

        // rank가 같으면 기존 이름 복원 (parsed tensor는 기본 이름만 가짐)
        if (savedNames.length == editTensor.getRank()) {                       // ← NEW
            editTensor.setAxisNames(savedNames);                               // ← NEW
        }                                                                      // ← NEW

        validateViewAxes();
        redoStack.clear();
        notifyEditorListeners();
    }

    // ── 저장 / 취소 ────────────────────────────────────────────

    public void save() {
        try {
            String pendingNode = draftNodeName;
            node.updateAllFrom(editTensor);              // shape cancel 시 예외 → 아래 catch
            if (pendingNode != null && !pendingNode.isEmpty()) {
                node.setNodeName(pendingNode);           // 데이터 갱신 성공 후 이름 확정
                draftNodeName = pendingNode;             // changeListener가 리셋한 값 복원
            }
            syncFromNode();
            notifyListeners();
        } catch (Exception e) {
            if ("Shape change cancel".equals(e.getMessage())) return;
            throw e;
        }
    }

    public void cancel() {
        editTensor    = originalTensor.makeCopy();
        draftNodeName = node.getNodeName();
        validateViewAxes();
        undoStack.clear();
        redoStack.clear();
        syncFromNode();
        notifyEditorListeners();   // 미저장 복원 — 캔버스 cascade 불필요
    }

    public void syncFromNode() {
        this.valueString = node.getValueString();
    }

    // ── Undo / Redo ────────────────────────────────────────────

    public void undo() {
        if (undoStack.isEmpty()) return;
        redoStack.push(takeSnapshot());
        restoreSnapshot(undoStack.pop());
        notifyEditorListeners();
    }

    public void redo() {
        if (redoStack.isEmpty()) return;
        undoStack.push(takeSnapshot());
        restoreSnapshot(redoStack.pop());
        notifyEditorListeners();
    }

    // ── Transpose ──────────────────────────────────────────────

    /**
     * editTensor의 rowAxis ↔ colAxis 두 축을 교환한다 (swapaxes).
     *
     * <ul>
     *   <li>rank &lt; 2 : 아무 것도 하지 않는다.</li>
     *   <li>rank == 2 : 고전적인 행렬 전치 (shape [R,C] → [C,R]).</li>
     *   <li>rank ≥ 3 : 현재 View에서 선택된 rowAxis ↔ colAxis만 교환.
     *       예) shape [B,H,W] 에서 rowAxis=1, colAxis=2 이면 [B,W,H] 로 변환되며,
     *       배치 축 B에 속하는 모든 슬라이스가 일관되게 전치된다.</li>
     * </ul>
     *
     * 축 이름도 함께 교환되며, undo 스택에 이전 상태가 저장된다.
     */
    public void transpose() {
        int rank = editTensor.getRank();
        if (rank < 2) return;

        undoStack.push(takeSnapshot());
        redoStack.clear();

        int[] oldShape = editTensor.getShape();
        int[] newShape = oldShape.clone();
        newShape[rowAxis] = oldShape[colAxis];   // rowAxis 위치에 colAxis 크기
        newShape[colAxis] = oldShape[rowAxis];   // colAxis 위치에 rowAxis 크기

        Tensor result = new Tensor(editTensor.getName(), newShape);

        int[] newIdx = new int[rank];
        int[] oldIdx = new int[rank];
        int   total  = result.dataSize();

        for (int flat = 0; flat < total; flat++) {
            // flat → newIdx (new shape 기준 언플래튼)
            int temp = flat;
            for (int i = rank - 1; i >= 0; i--) {
                newIdx[i] = temp % newShape[i];
                temp      /= newShape[i];
            }
            // newIdx → oldIdx: rowAxis ↔ colAxis 만 교환, 나머지 그대로
            System.arraycopy(newIdx, 0, oldIdx, 0, rank);
            oldIdx[rowAxis] = newIdx[colAxis];
            oldIdx[colAxis] = newIdx[rowAxis];

            result.set(editTensor.get(oldIdx), newIdx);
        }

        // 축 이름도 함께 교환
        String[] oldNames = editTensor.getAxisNames();
        String[] newNames = oldNames.clone();
        newNames[rowAxis] = oldNames[colAxis];
        newNames[colAxis] = oldNames[rowAxis];
        result.setAxisNames(newNames);

        editTensor = result;
        // rowAxis/colAxis는 그대로 유지한다.
        // editTensor의 데이터가 이미 전치됐으므로,
        // 같은 축 인덱스로 to2DArray()를 호출하면 전치된 뷰가 자연스럽게 출력된다.

        notifyEditorListeners();
    }

    // ── 축 이름 (NEW) ──────────────────────────────────────────

    /**
     * editTensor의 axis번 축 이름을 반환한다.
     */
    public String getAxisName(int axis) {
        return editTensor.getAxisName(axis);
    }

    public void setAxisName(int axis, String name) {
        if (axis < 0 || axis >= editTensor.getRank()) return;
        String norm = (name == null) ? "" : name;
        if (norm.equals(editTensor.getAxisName(axis))) return;   // 변화 없으면 무시 (focus-lost 중복 push 방지)
        undoStack.push(takeSnapshot());
        redoStack.clear();
        editTensor.setAxisName(axis, norm);
        notifyEditorListeners();
    }

    // ── 이름 (draft, undo 대상) ─────────────────────────────────

    public String getDraftNodeName() { return draftNodeName; }

    public void setDraftNodeName(String name) {
        if (name == null || name.equals(draftNodeName)) return;
        undoStack.push(takeSnapshot());
        redoStack.clear();
        draftNodeName = name;
        notifyEditorListeners();
    }

    public void setDraftTensorName(String name) {
        if (name == null || name.equals(editTensor.getName())) return;
        undoStack.push(takeSnapshot());
        redoStack.clear();
        editTensor.setName(name);
        notifyEditorListeners();
    }

    /**
     * 전체 축 이름 배열의 복사본을 반환한다.
     */
    public String[] getAxisNames() {
        return editTensor.getAxisNames();
    }

    // ── 조회 ───────────────────────────────────────────────────

 // TensorViewModel.java
    public boolean isChanged() {
        return !node.getNodeName().equals(draftNodeName)
            || !originalTensor.getName().equals(editTensor.getName())
            || !originalTensor.equalValue(editTensor)
            || !java.util.Arrays.equals(originalTensor.getAxisNames(),
                                        editTensor.getAxisNames()); // ← 추가
    }

    public String      getValueString()  { return editTensor.getValueString(); }
    public Tensor      getEditTensor()   { return editTensor; }
    public Tensor.Kind getEditKind()     { return editTensor.getKind(); }

    public int   getRowAxis()       { return rowAxis; }
    public int   getColAxis()       { return colAxis; }
    public int[] getFixedIndices()  { return fixedIndices.clone(); }

    public int getRows() {
        int rank = editTensor.getRank();
        if (rank == 0) return 1;
        if (rank == 1) return 1;
        return editTensor.getDim(rowAxis);
    }

    public int getCols() {
        int rank = editTensor.getRank();
        if (rank == 0) return 1;
        if (rank == 1) return editTensor.getDim(0);
        return editTensor.getDim(colAxis);
    }

    public int   getRank()  { return editTensor.getRank(); }
    public int[] getShape() { return editTensor.getShape(); }

    public boolean isAxisSelectable() { return editTensor.getRank() >= 3; }

    @Override public TensorNode getNode()       { return node; }
    @Override public int getInputCount()        { return node.getInputPortCount(); }
    @Override public int getOutputCount()       { return node.getOutputPortCount(); }

    @Override
    public String getSubLabel() {
        return node.getTensor().getSummary();
    }

    @Override
    public String getIconText() {
        return switch (editTensor.getKind()) {
            case SCALAR -> "S";
            case VECTOR -> "V";
            case MATRIX -> "M";
            case TENSOR -> "T";
        };
    }
    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }
}
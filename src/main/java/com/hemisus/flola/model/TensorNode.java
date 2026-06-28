package com.hemisus.flola.model;

import com.hemisus.flola.event.ShapeChangeListener;
import java.util.Arrays;

public class TensorNode extends GraphNode {

    private Tensor tensor;
    private ShapeChangeListener shapeListener;

    public TensorNode(Tensor tensor) {
        this.tensor = tensor;
        setNodeName("Node: " + tensor.getName());
    }

    public void setShapeChangeListener(ShapeChangeListener l) {
        this.shapeListener = l;
    }

    @Override
    public Tensor getOutputValue(int portIndex) {
        return portIndex == 0 ? tensor : null;
    }

    /**
     * 이름·Shape·값을 모두 {@code other}로부터 갱신한다.
     * Kind는 shape에서 자동으로 파생된다.
     */
    public void updateAllFrom(Tensor other) {
        if (other == null) return;
        checkShapeChange(other);
        tensor.setName(other.getName());
        tensor.copyValuesFrom(other);
    }

    /**
     * 이름·Kind는 유지하고 Shape과 값만 {@code other}로 갱신한다.
     */
    public void updateValuesFrom(Tensor other) {
        if (other == null) return;
        checkShapeChange(other);
        tensor.copyValuesFrom(other);
    }

    /**
     * shape 변경이 있을 경우 리스너에게 허용 여부를 묻는다.
     * Kind는 shape에서 파생되므로 Kind 기반 검증이 불필요하다.
     */
    private void checkShapeChange(Tensor incoming) {
        int[] os = tensor.getShape();
        int[] ns = incoming.getShape();
        if (!Arrays.equals(os, ns) && shapeListener != null) {
            boolean ok = shapeListener.onShapeChange(tensor.getName(), os, ns);
            if (!ok) throw new RuntimeException("Shape change cancel");
        }
    }
    @Override public int getInputPortCount()  { return 0; }
    @Override public int getOutputPortCount() { return 1; }

    public Tensor     getTensor()      { return tensor; }
    public String     getTensorName()  { return tensor.getName(); }
    public int[]      getShape()       { return tensor.getShape(); }
    public Tensor.Kind getKind()       { return tensor.getKind(); }
    public String     getValueString() { return tensor.getValueString(); }

    /** 마지막에서 두 번째 차원 (없으면 1) */
    public int getRow() {
        int[] s = tensor.getShape();
        return s.length >= 2 ? s[s.length - 2] : 1;
    }

    /** 마지막 차원 (Scalar이면 1) */
    public int getCol() {
        int[] s = tensor.getShape();
        return s.length == 0 ? 1 : s[s.length - 1];
    }
}

package com.hemisus.flola.model;

public class InputNode extends UtilityNode {

    /** 부모 CustomOperationNode.compute() 호출 시 주입되는 텐서. */
    private Tensor tensor;

    public InputNode(String name) {
        super(name);
    }

    public void   setTensor(Tensor t) { this.tensor = t; }
    public Tensor getTensor()         { return tensor; }

    @Override public int getInputPortCount()  { return 0; }
    @Override public int getOutputPortCount() { return 1; }

    @Override
    public Tensor getOutputValue(int portIndex) {
        return portIndex == 0 ? tensor : null;
    }

    @Override protected String defaultName() { return "input"; }
}

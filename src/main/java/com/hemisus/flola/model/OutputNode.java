package com.hemisus.flola.model;

public class OutputNode extends UtilityNode {

    public OutputNode(String name) {
        super(name);
    }

    @Override public int getInputPortCount()  { return 1; }
    @Override public int getOutputPortCount() { return 0; }

    @Override
    public Tensor getOutputValue(int portIndex) {
        // OutputNode는 직접 출력하지 않는다 — CustomOperationNode가 incoming 텐서를 읽어간다.
        return null;
    }

    @Override protected String defaultName() { return "output"; }
}

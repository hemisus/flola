package com.hemisus.flola.model;

import java.util.ArrayList;
import java.util.List;

public abstract class OperationNode extends GraphNode {
    protected List<Tensor> cachedOutputs = new ArrayList<>();
    protected boolean isDirty =true;
    protected int inputPortCount;
    protected int outputPortCount;
    
    protected boolean inputVariadic;
    protected boolean outputVariadic;
    protected String operationType;

    public OperationNode(String operationType) {
        this.operationType = operationType;
        setNodeName(operationType);   // 기본 nodeName = operationType
    }

    public String getOperationType() { return operationType; }
    @Override
    public final Tensor getOutputValue(int portIndex) {
        if (portIndex >= 0 && portIndex < cachedOutputs.size()) {
            return cachedOutputs.get(portIndex);
        }
        return null;
    }
    /**
     * returns new ArrayList of outputs
     */
    public List<Tensor> getOutputs() {
        return new ArrayList<>(cachedOutputs);
    }
    public abstract void compute(List<Tensor> inputs);
    public void setDirty() { this.isDirty=true; }
    public void setOutputPortCount(int count) { this.outputPortCount = count; }
    public boolean isDirty() { return isDirty; }
    @Override public int getInputPortCount() { return inputPortCount; }
    @Override public int getOutputPortCount() { return outputPortCount; }
    public boolean isInputVariadic() { return inputVariadic; }
    public boolean isOutputVariadic() { return outputVariadic; }
    public void setInputVariadic(boolean inputVariadic) { this.inputVariadic = inputVariadic; }
    public void setOutputVariadic(boolean outputVariadic) { this.outputVariadic = outputVariadic; }
    
}
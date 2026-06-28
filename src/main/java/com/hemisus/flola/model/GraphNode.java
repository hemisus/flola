package com.hemisus.flola.model;

public abstract class GraphNode {
	
    protected String nodeName;
    
    public String getNodeName() { return nodeName; }
    public void setNodeName(String name) { nodeName = name; }
    
    public abstract int getInputPortCount();
    public abstract int getOutputPortCount();

    /**
     *  return output at portIndex
     */
    public abstract Tensor getOutputValue(int portIndex);

}

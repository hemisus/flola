package com.hemisus.flola.model;

import java.util.Objects;

public class ConnectionModel {
    private final GraphNode source;
    private final int sourcePortIndex;
    private final GraphNode target;
    private final int targetPortIndex;

    public ConnectionModel(GraphNode source, int sourcePortIndex, GraphNode target, int targetPortIndex) {
        this.source = source;
        this.sourcePortIndex = sourcePortIndex;
        this.target = target;
        this.targetPortIndex = targetPortIndex;
    }
    
    public GraphNode getSource() { return source; }
    public int getSourcePortIndex() { return sourcePortIndex; }
    public GraphNode getTarget() { return target; }
    public int getTargetPortIndex() { return targetPortIndex; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConnectionModel other = (ConnectionModel) o;
        return sourcePortIndex == other.sourcePortIndex && 
               targetPortIndex == other.targetPortIndex && 
               Objects.equals(source, other.source) && 
               Objects.equals(target, other.target);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, sourcePortIndex, target, targetPortIndex);
    }

}

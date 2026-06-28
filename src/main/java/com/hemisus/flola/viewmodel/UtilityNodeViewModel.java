package com.hemisus.flola.viewmodel;

import com.hemisus.flola.model.GraphNode;
import com.hemisus.flola.model.InputNode;
import com.hemisus.flola.model.OutputNode;
import com.hemisus.flola.model.UtilityNode;

public class UtilityNodeViewModel extends NodeViewModel {

    private final UtilityNode node;

    public UtilityNodeViewModel(UtilityNode node) {
        this.node = node;
    }

    public String getName()         { return node.getName(); }
    public void   setName(String n) { node.setName(n); notifyListeners(); }

    public boolean isInput()  { return node instanceof InputNode;  }
    public boolean isOutput() { return node instanceof OutputNode; }

    @Override public GraphNode getNode()       { return node; }
    @Override public int       getInputCount()  { return node.getInputPortCount();  }
    @Override public int       getOutputCount() { return node.getOutputPortCount(); }
    @Override public String    getIconText()    { return isInput() ? "In" : "Out"; }
    @Override public void      syncFromNode()   { /* 캐시할 게 없음 */ }
}
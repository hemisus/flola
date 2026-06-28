package com.hemisus.flola.model;

public abstract class UtilityNode extends GraphNode {

    protected String name;

    public UtilityNode(String name) {
        this.name = (name == null || name.isBlank()) ? defaultName() : name;
        setNodeName(this.name);
    }

    public String getName() { return name; }

    public void setName(String name) {
        if (name == null || name.isBlank()) return;
        this.name = name;
        setNodeName(name);
    }

    protected abstract String defaultName();
}

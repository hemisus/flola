package com.hemisus.flola.ui;

import java.util.Objects;

/** 포트 식별자. NodeView + (type, index) 조합이 유일한 식별. */
public class Port {

    public enum Type { INPUT, OUTPUT }

    private final NodeView owner;
    private final Type     type;
    private final int      index;   // -1 = variadic

    public Port(NodeView owner, Type type, int index) {
        this.owner = owner;
        this.type  = type;
        this.index = index;
    }

    public NodeView getOwner() { return owner; }
    public Type     getType()  { return type;  }
    public int      getIndex() { return index; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Port other)) return false;
        return index == other.index && owner == other.owner && type == other.type;
    }
    @Override
    public int hashCode() { return Objects.hash(owner, type, index); }
}
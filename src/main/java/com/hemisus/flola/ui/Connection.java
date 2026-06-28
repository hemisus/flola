package com.hemisus.flola.ui;

/** OUTPUT 포트 → INPUT 포트 연결 (UI 측 데이터). */
public class Connection {

    private final Port from;   // OUTPUT
    private final Port to;     // INPUT

    public Connection(Port from, Port to) {
        if (from.getType() != Port.Type.OUTPUT)
            throw new IllegalArgumentException("from must be OUTPUT");
        if (to.getType() != Port.Type.INPUT)
            throw new IllegalArgumentException("to must be INPUT");
        if (from.getOwner() == to.getOwner())
            throw new IllegalArgumentException("Cannot connect same node");
        this.from = from;
        this.to = to;
    }

    public Port getFrom() { return from; }
    public Port getTo()   { return to;   }
}
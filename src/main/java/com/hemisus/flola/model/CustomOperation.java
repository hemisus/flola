package com.hemisus.flola.model;

import java.util.UUID;

public class CustomOperation {

    private final String uuid;
    private String       name;
    private final Graph  subGraph;

    public CustomOperation(String name, Graph subGraph) {
        this(UUID.randomUUID().toString(), name, subGraph);
    }

    private CustomOperation(String uuid, String name, Graph subGraph) {
        this.uuid     = (uuid != null && !uuid.isBlank()) ? uuid : UUID.randomUUID().toString();
        this.name     = (name == null || name.isBlank()) ? "Custom" : name;
        this.subGraph = (subGraph != null) ? subGraph : new Graph();
    }

    /** 저장 파일 복원 전용 — UUID를 그대로 복원한다. */
    public static CustomOperation forStorage(String uuid, String name, Graph subGraph) {
        return new CustomOperation(uuid, name, subGraph);
    }

    public String getUuid()     { return uuid; }
    public String getName()     { return name; }
    public Graph  getSubGraph() { return subGraph; }

    public void setName(String name) {
        if (name != null && !name.isBlank()) this.name = name;
    }
}
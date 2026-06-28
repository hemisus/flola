package com.hemisus.flola.viewmodel;

import java.util.ArrayList;
import java.util.List;

import com.hemisus.flola.model.GraphNode;

public abstract class NodeViewModel {
	
    /** 모델 채널: 확정/평가된 데이터 변경. 캔버스 + 에디터가 모두 듣는다. */
    private final List<Runnable> listeners = new ArrayList<>();
    /** 에디터 채널: 드래프트(미리보기)만 변경. 에디터만 듣는다 (캔버스는 못 듣는다). */
    private final List<Runnable> editorListeners = new ArrayList<>();

    public NodeViewModel() {
    	
    }

    public String getNodeName() {
    	return (getNode() != null) ? getNode().getNodeName() : "Unknown";
    }

    public void setNodeName(String name) {
        if (getNode() != null) {
            getNode().setNodeName(name); // update Immediately
        }
        notifyListeners();
    }
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    /** 에디터 전용 리스너 등록. 드래프트 변경과 모델 변경을 모두 받는다. */
    public void addEditorListener(Runnable listener) {
        editorListeners.add(listener);
    }

    /**
     * 모델 변경 알림 — 캔버스 리스너와 에디터 리스너 모두에게 fan-out.
     * (확정 데이터가 바뀌었을 때: save, 외부 캐스케이드 등)
     */
    public void notifyListeners() {
        for (Runnable l : listeners) {
            l.run();
        }
        for (Runnable l : editorListeners) {
            l.run();
        }
    }

    /**
     * 드래프트(미리보기) 변경 알림 — 에디터 리스너에게만 전달.
     * 캔버스는 듣지 못하므로 미저장 상태에서 캐스케이드가 트리거되지 않는다.
     */
    public void notifyEditorListeners() {
        for (Runnable l : editorListeners) {
            l.run();
        }
    }
    public abstract GraphNode getNode();
    public abstract String getIconText();
    public abstract void syncFromNode();
    public abstract int getInputCount();
    public abstract int getOutputCount();

    /** 노드 바디 하단에 표시할 보조 텍스트. 기본값은 빈 문자열(표시 안 함). */
    public String getSubLabel() { return ""; }
}
package com.hemisus.flola.controller;

import com.hemisus.flola.model.GraphNode;
import com.hemisus.flola.ui.NodeView;

/**
 * {@link GraphCommand}가 캔버스를 조작하기 위한 위임 창구.
 *
 * <p>메인 캔버스(MainController)와 서브그래프 캔버스(CustomOperationEditorStage)가
 * 각각 구현하여, 동일한 Command 클래스를 양쪽에서 재사용할 수 있게 한다.
 * 모든 메서드는 호출부의 <b>기존 편집 경로</b>(graph.connect, removeCanvasNode,
 * rebuildVisualConnections, updateCascade 등)에 위임하여 cascade·인스펙터·연결
 * 갱신 이벤트가 정상 동작과 동일하게 트리거되도록 한다.</p>
 */
public interface CanvasContext {

    /**
     * 제거됐던(혹은 다시 추가할) 노드를 그래프·뷰모델맵·캔버스에 되돌린다.
     * NodeView의 리스너는 최초 배선 때 부착된 것을 그대로 재사용하므로
     * <b>재배선하지 않는다</b>(중복 리스너 방지).
     */
    void restoreNode(NodeView nv, double x, double y);

    /** 노드를 완전히 제거한다(연결·그래프·뷰모델맵·캔버스 일괄). */
    void eraseNode(NodeView nv);

    /** 노드를 (x, y) world 좌표로 이동시킨다. */
    void moveNode(NodeView nv, double x, double y);

    /** 연결을 생성하고 시각/평가를 갱신한다. */
    void connect(GraphNode src, int srcPort, GraphNode dst, int dstPort);

    /** dst의 dstPort 입력 연결을 제거하고 시각/평가를 갱신한다. */
    void disconnect(GraphNode dst, int dstPort);

    /** 노드의 연결 곡선을 모델 기준으로 재구성한다. */
    void rebuildConnections(GraphNode node);

    /** 노드부터 하류로 평가를 전파한다. */
    void cascade(GraphNode node);

    /** 주어진 노드들만 선택 상태로 만든다(기존 선택 해제 후). */
    void selectNodes(java.util.List<NodeView> nodes);

    /** 현재 선택을 모두 해제한다. */
    void clearSelection();

    /** 연결 곡선 레이어를 즉시 다시 그린다 (포트 위치가 바뀐 직후 필요). */
    void redrawConnections();
}
package com.hemisus.flola.controller;

import com.hemisus.flola.model.GraphNode;
import com.hemisus.flola.model.Tensor;
import com.hemisus.flola.model.TensorNode;
import com.hemisus.flola.ui.NodeView;
import com.hemisus.flola.viewmodel.OperationViewModel;
import com.hemisus.flola.viewmodel.CustomOperationViewModel;

import java.util.ArrayList;
import java.util.List;

/**
 * 캔버스 편집용 {@link GraphCommand} 구현 모음.
 *
 * <p>모든 Command는 {@link CanvasContext}에 위임만 하므로 메인 캔버스와
 * 서브그래프 캔버스에서 그대로 재사용된다.</p>
 */
public final class GraphCommands {

    private GraphCommands() {}

    /** 노드 위치 스냅샷 (Point2D의 javafx 의존을 피해 컨트롤러 패키지 내부에 둠). */
    public record Pos(double x, double y) {}

    /** 노드 하나에 연결된 연결 정보 스냅샷 (복원용, 값 기반). */
    public record ConnSnapshot(GraphNode src, int srcPort, GraphNode dst, int dstPort) {}

    // ── describe() 라벨 헬퍼 ────────────────────────────────
    private static String nm(GraphNode n) {
        String s = (n != null) ? n.getNodeName() : null;
        return (s != null && !s.isBlank()) ? s : "node";
    }
    private static String nm(NodeView nv) {
        String s = (nv != null) ? nv.getViewModel().getNodeName() : null;
        return (s != null && !s.isBlank()) ? s : "node";
    }

    // ── 노드 추가 ──────────────────────────────────────────

    /** 노드 추가. undo=삭제, redo=재추가. */
    public static final class AddNodeCommand implements GraphCommand {
        private final CanvasContext ctx;
        private final NodeView nv;
        private final double x, y;

        public AddNodeCommand(CanvasContext ctx, NodeView nv, double x, double y) {
            this.ctx = ctx; this.nv = nv; this.x = x; this.y = y;
        }

        @Override public void undo() {
            ctx.eraseNode(nv);   // 관련 이벤트(cascade·인스펙터·연결정리) 정상 트리거
        }

        @Override public void redo() {
            ctx.restoreNode(nv, x, y);
            ctx.cascade(nv.getViewModel().getNode());
        }

        @Override public String describe() { return "Add " + nm(nv) + " to canvas"; }
    }

    // ── 노드 삭제 (단일/다중) ───────────────────────────────

    /**
     * 노드 묶음 삭제. undo=노드+연결 복원, redo=재삭제.
     * 생성 시점에 위치와 관련 연결을 스냅샷해 둔다.
     */
    public static final class RemoveNodesCommand implements GraphCommand {
        private final CanvasContext ctx;
        private final List<NodeView> nodes;
        private final List<Pos> positions;
        private final List<ConnSnapshot> conns;

        public RemoveNodesCommand(CanvasContext ctx, List<NodeView> nodes,
                                  List<Pos> positions, List<ConnSnapshot> conns) {
            this.ctx = ctx;
            this.nodes = new ArrayList<>(nodes);
            this.positions = new ArrayList<>(positions);
            this.conns = new ArrayList<>(conns);
        }

        @Override public void undo() {
            // 1) 노드 먼저 전부 복원 (연결 복원 시 양 끝이 존재해야 함)
            for (int i = 0; i < nodes.size(); i++) {
                Pos p = positions.get(i);
                ctx.restoreNode(nodes.get(i), p.x(), p.y());
            }
            // 2) 연결 복원
            for (ConnSnapshot c : conns) {
                ctx.connect(c.src(), c.srcPort(), c.dst(), c.dstPort());
            }
            // 3) 되살린 노드를 선택 상태로 (삭제 취소 = 복원된 것을 선택)
            ctx.selectNodes(nodes);
        }

        @Override public void redo() {
            for (NodeView nv : nodes) ctx.eraseNode(nv);
        }

        @Override public String describe() {
            return nodes.size() == 1
                ? "Remove " + nm(nodes.get(0)) + " from canvas"
                : "Remove " + nodes.size() + " nodes from canvas";
        }
    }

    // ── 노드 이동 (단일/다중) ───────────────────────────────

    /** 노드 묶음 이동. from→to. undo=from으로, redo=to로. */
    public static final class MoveNodesCommand implements GraphCommand {
        private final CanvasContext ctx;
        private final List<NodeView> nodes;
        private final List<Pos> from;
        private final List<Pos> to;

        public MoveNodesCommand(CanvasContext ctx, List<NodeView> nodes,
                                List<Pos> from, List<Pos> to) {
            this.ctx = ctx;
            this.nodes = new ArrayList<>(nodes);
            this.from = new ArrayList<>(from);
            this.to = new ArrayList<>(to);
        }

        @Override public void undo() {
            for (int i = 0; i < nodes.size(); i++)
                ctx.moveNode(nodes.get(i), from.get(i).x(), from.get(i).y());
        }

        @Override public void redo() {
            for (int i = 0; i < nodes.size(); i++)
                ctx.moveNode(nodes.get(i), to.get(i).x(), to.get(i).y());
        }

        @Override public String describe() {
            return nodes.size() == 1 ? "Move " + nm(nodes.get(0))
                                     : "Move " + nodes.size() + " nodes";
        }
    }

    // ── 연결 추가 ──────────────────────────────────────────

    /** 연결 추가. undo=제거, redo=재연결. dstPort는 연결 후 확정된 인덱스. */
    public static final class AddConnectionCommand implements GraphCommand {
        private final CanvasContext ctx;
        private final GraphNode src, dst;
        private final int srcPort, dstPort;

        public AddConnectionCommand(CanvasContext ctx, GraphNode src, int srcPort,
                                    GraphNode dst, int dstPort) {
            this.ctx = ctx; this.src = src; this.srcPort = srcPort;
            this.dst = dst; this.dstPort = dstPort;
        }

        @Override public void undo() { ctx.disconnect(dst, dstPort); }
        @Override public void redo() { ctx.connect(src, srcPort, dst, dstPort); }

        @Override public String describe() { return "Connect " + nm(src) + " → " + nm(dst); }
    }

    // ── 연결 제거 ──────────────────────────────────────────

    /** 연결 제거. undo=복원, redo=재제거. */
    public static final class RemoveConnectionCommand implements GraphCommand {
        private final CanvasContext ctx;
        private final GraphNode src, dst;
        private final int srcPort, dstPort;

        public RemoveConnectionCommand(CanvasContext ctx, GraphNode src, int srcPort,
                                       GraphNode dst, int dstPort) {
            this.ctx = ctx; this.src = src; this.srcPort = srcPort;
            this.dst = dst; this.dstPort = dstPort;
        }

        @Override public void undo() { ctx.connect(src, srcPort, dst, dstPort); }
        @Override public void redo() { ctx.disconnect(dst, dstPort); }

        @Override public String describe() { return "Disconnect " + nm(src) + " → " + nm(dst); }
    }

    // ── 텐서 편집 (에디터 save를 캔버스 history에 통합) ────

    /**
     * 텐서 에디터에서 save한 한 번의 편집 세션을 캔버스 undo/redo 단위로 만든다.
     * before/after는 save 직전/직후의 노드 상태 스냅샷(텐서 + 노드 이름).
     *
     * <p>{@code copyValuesFrom}이 텐서의 changeListener를 통해 cascade·캔버스
     * 라벨·열린 에디터 갱신을 자동 트리거하므로(= save와 동일 경로),
     * 별도 cascade 호출이 필요 없다. 노드 이름을 먼저 복원한 뒤 텐서를 복원해
     * 단일 알림으로 라벨까지 함께 갱신되게 한다.</p>
     */
    public static final class TensorEditCommand implements GraphCommand {
        private final TensorNode node;
        private final Tensor before, after;
        private final String beforeName, afterName;

        public TensorEditCommand(TensorNode node,
                                 Tensor before, String beforeName,
                                 Tensor after,  String afterName) {
            this.node = node;
            this.before = before.makeCopy();
            this.after  = after.makeCopy();
            this.beforeName = beforeName;
            this.afterName  = afterName;
        }

        @Override public void undo() { applyState(before, beforeName); }
        @Override public void redo() { applyState(after,  afterName); }

        private void applyState(Tensor snapshot, String name) {
            node.setNodeName(name);                       // 먼저 이름 복원
            node.getTensor().copyValuesFrom(snapshot);    // 텐서 복원 → changeListener가 알림/cascade
        }

        @Override public String describe() {
            String tn = node.getTensor().getName();       // 데이터 편집 → 텐서 이름으로 표시
            return "Edit " + ((tn != null && !tn.isBlank()) ? tn : "tensor");
        }
    }

    // ── 연산 편집 (에디터 Apply를 캔버스 history에 통합) ──

    /**
     * 연산 에디터에서 Apply한 한 번의 편집 세션을 캔버스 undo/redo 단위로 만든다.
     * before/after는 Apply 직전/직후의 노드 확정 상태(params·이름·출력 포트 수·outputVariadic).
     *
     * <p>텐서와 달리 자동 cascade 트리거가 없으므로 적용 후 {@code ctx.cascade}로
     * 재평가·캔버스 라벨·열린 에디터 갱신을 명시적으로 전파한다.</p>
     *
     * <p>참고: 포트 재할당(입출력 포트 드래그) 같은 그래프 연결 변경은 포함하지 않는다.</p>
     */
    public static final class OperationEditCommand implements GraphCommand {
        private final CanvasContext ctx;
        private final OperationViewModel vm;
        private final OperationViewModel.CommittedState before, after;
        private List<ConnSnapshot> beforeConns, afterConns;
        public OperationEditCommand(
        	    CanvasContext ctx,
        	    OperationViewModel vm,
        	    OperationViewModel.CommittedState before,
        	    OperationViewModel.CommittedState after,
        	    List<ConnSnapshot> beforeConns,
        	    List<ConnSnapshot> afterConns)
        {
            this.ctx = ctx; this.vm = vm; this.before = before; this.after = after;
            this.beforeConns =beforeConns; this.afterConns = afterConns;
        }

        @Override public void undo() {
            vm.applyCommittedState(before);   // ← 포트 수 먼저 복원, 이후 connect 시 유효 검증 통과
            for (ConnSnapshot c : afterConns) {
                ctx.disconnect(c.dst(), c.dstPort());
            }
            for (ConnSnapshot c : beforeConns) {
                ctx.connect(c.src(), c.srcPort(), c.dst(), c.dstPort());
            }
            ctx.cascade(vm.getNode());
            ctx.redrawConnections();
        }

        @Override public void redo() {
            vm.applyCommittedState(after);    // ← 포트 수 먼저 축소, afterConns만 연결되므로 유효
            for (ConnSnapshot c : beforeConns) {
                ctx.disconnect(c.dst(), c.dstPort());
            }
            for (ConnSnapshot c : afterConns) {
                ctx.connect(c.src(), c.srcPort(), c.dst(), c.dstPort());
            }
            ctx.cascade(vm.getNode());
            ctx.redrawConnections();
        }

        @Override public String describe() { return "Edit " + nm(vm.getNode()); }
    }

    // ── 커스텀 연산 편집 (CustomOperationEditor Apply를 캔버스 history에 통합) ──

    /**
     * CustomOperationEditor에서 Apply한 한 번의 편집 세션을 캔버스 undo/redo 단위로 만든다.
     * before/after는 Apply 직전/직후의 <b>공유 정의(서브그래프+op이름) + 인스턴스 이름</b> 스냅샷.
     *
     * <p>operation 정의 변경은 같은 operation을 참조하는 <b>모든 인스턴스</b>에 영향을 주므로,
     * 정의 복원 후 {@code propagate}(MainController.onCustomOperationChanged 등)로 전 인스턴스의
     * 포트수·연결정리·cascade를 재실행한다.</p>
     *
     * <p>편집된 인스턴스의 <b>부모 그래프 외부 연결</b>은 OperationEditCommand와 동일하게
     * before/afterConns를 ctx로 끊고/잇는다(정의 복원으로 포트수를 먼저 맞춘 뒤 연결).</p>
     *
     * <p><b>알려진 한계(1차):</b> 포트 축소로 <i>다른</i> 인스턴스에서 prune된 연결은 복원하지 않는다.</p>
     */
    public static final class CustomOperationEditCommand implements GraphCommand {
        private final CanvasContext ctx;
        private final CustomOperationViewModel vm;
        private final CustomOperationViewModel.CommittedSubGraph before, after;
        private final List<ConnSnapshot> beforeConns, afterConns;
        private final Runnable propagate;   // 전 인스턴스 전파 (onCustomOperationChanged 등)

        public CustomOperationEditCommand(
                CanvasContext ctx,
                CustomOperationViewModel vm,
                CustomOperationViewModel.CommittedSubGraph before,
                CustomOperationViewModel.CommittedSubGraph after,
                List<ConnSnapshot> beforeConns,
                List<ConnSnapshot> afterConns,
                Runnable propagate) {
            this.ctx = ctx; this.vm = vm;
            this.before = before; this.after = after;
            this.beforeConns = new ArrayList<>(beforeConns);
            this.afterConns  = new ArrayList<>(afterConns);
            this.propagate = propagate;
        }

        @Override public void undo() {
            vm.applyCommittedSubGraph(before);   // 정의·포트수 먼저 복원
            for (ConnSnapshot c : afterConns)  ctx.disconnect(c.dst(), c.dstPort());
            for (ConnSnapshot c : beforeConns) ctx.connect(c.src(), c.srcPort(), c.dst(), c.dstPort());
            if (propagate != null) propagate.run();
            ctx.redrawConnections();
        }

        @Override public void redo() {
            vm.applyCommittedSubGraph(after);
            for (ConnSnapshot c : beforeConns) ctx.disconnect(c.dst(), c.dstPort());
            for (ConnSnapshot c : afterConns)  ctx.connect(c.src(), c.srcPort(), c.dst(), c.dstPort());
            if (propagate != null) propagate.run();
            ctx.redrawConnections();
        }

        @Override public String describe() { return "Edit " + nm(vm.getNode()); }
    }

    /** redo 시 지정 노드들을 선택, undo 시 선택 해제. */
    public static final class SelectionCommand implements GraphCommand {
        private final CanvasContext ctx;
        private final List<NodeView> nodes;

        public SelectionCommand(CanvasContext ctx, List<NodeView> nodes) {
            this.ctx = ctx;
            this.nodes = new ArrayList<>(nodes);
        }

        @Override public void undo() { ctx.clearSelection(); }
        @Override public void redo() { ctx.selectNodes(nodes); }

        @Override public String describe() { return "Change selection"; }
    }

    // ── 합성 (붙여넣기 등 원자적 묶음) ──────────────────────

    /** 여러 Command를 하나로 묶는다. undo는 역순, redo는 정순. */
    public static final class CompositeCommand implements GraphCommand {
        private final List<GraphCommand> cmds;
        private final String label;

        public CompositeCommand(List<GraphCommand> cmds) { this(cmds, "Multiple changes"); }

        public CompositeCommand(List<GraphCommand> cmds, String label) {
            this.cmds = new ArrayList<>(cmds);
            this.label = (label != null && !label.isBlank()) ? label : "Multiple changes";
        }

        @Override public void undo() {
            for (int i = cmds.size() - 1; i >= 0; i--) cmds.get(i).undo();
        }

        @Override public void redo() {
            for (GraphCommand c : cmds) c.redo();
        }

        @Override public String describe() { return label; }

        public boolean isEmpty() { return cmds.isEmpty(); }
    }
}
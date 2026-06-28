package com.hemisus.flola.model.factory;

import com.hemisus.flola.model.CustomOperation;
import com.hemisus.flola.model.CustomOperationNode;
import com.hemisus.flola.model.GenericOperationNode;
import com.hemisus.flola.model.Graph;
import com.hemisus.flola.model.GraphNode;
import com.hemisus.flola.model.InputNode;
import com.hemisus.flola.model.OperationNode;
import com.hemisus.flola.model.OutputNode;
import com.hemisus.flola.model.TensorNode;
import com.hemisus.flola.utils.OperationRegistry;
import com.hemisus.flola.utils.OperationRegistry.OperationConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 노드 객체 조립 전담 팩토리.
 *
 * <p>연산 정의(BiFunction, 포트 수, 파라미터)는 {@link OperationRegistry}에 통합되었으므로
 * 이 클래스는 순수하게 <b>노드 생성·복제</b>만 담당한다.
 *
 * <ul>
 *   <li>{@code opMap} 제거 — 연산 함수는 {@link OperationRegistry}에서 조회</li>
 *   <li>{@code registerAll()} 제거 — {@code OperationRegistry}가 static 블록에서 자체 초기화</li>
 * </ul>
 */
public class NodeFactory {

    // ── 사이드바 템플릿 노드 생성 ─────────────────────────────────────────

    /**
     * 레지스트리에 등록된 순서대로 기본 연산 노드 목록을 생성한다.
     * 사이드바의 Built-in 항목 순서가 {@link OperationRegistry} 등록 순서를 따른다.
     */
    public static List<OperationNode> createDefaultOperations() {
        List<OperationNode> ops = new ArrayList<>();
        for (var entry : OperationRegistry.getAll().entrySet()) {
            String type = entry.getKey();
            OperationConfig cfg = entry.getValue();
            GenericOperationNode node = new GenericOperationNode(
                type,
                cfg.inputPorts(),
                cfg.outputPorts(),
                cfg.inputVariadic(),
                cfg.outputVariadic(),
                cfg.function()
            );
            cfg.defaultParams().forEach(node::setParam);
            ops.add(node);
        }
        return ops;
    }

    // ── 인스턴스 생성 ─────────────────────────────────────────────────────

    public static GraphNode createInstance(GraphNode template) {
        if (template instanceof TensorNode tn) {
            return new TensorNode(tn.getTensor());
        }
        if (template instanceof CustomOperationNode cn) {
            // 같은 CustomOperation 참조 → 정의(name·subGraph) 변경이 모든 인스턴스에 자동 반영
            return new CustomOperationNode(cn.getOperation());
        }
        if (template instanceof InputNode in) {
            return new InputNode(in.getName());
        }
        if (template instanceof OutputNode out) {
            return new OutputNode(out.getName());
        }
        if (template instanceof GenericOperationNode gNode) {
            GenericOperationNode newNode = new GenericOperationNode(
                gNode.getOperationType(),
                gNode.getInputPortCount(),
                gNode.getOutputPortCount(),
                gNode.isInputVariadic(),
                gNode.isOutputVariadic(),
                OperationRegistry.get(gNode.getOperationType())  // ← opMap 대신 레지스트리 사용
            );
            newNode.setNodeName(gNode.getNodeName());
            gNode.getParams().forEach(newNode::setParam);
            return newNode;
        }
        return null;
    }

    // ── 편의 팩토리 메서드 ────────────────────────────────────────────────

    /** 빈 서브그래프를 가진 CustomOperationNode 템플릿을 생성한다. */
    public static CustomOperationNode createCustomOperationTemplate(String name) {
        return new CustomOperationNode(new CustomOperation(name, new Graph()));
    }

    public static InputNode  createInputNode(String name)  { return new InputNode(name);  }
    public static OutputNode createOutputNode(String name) { return new OutputNode(name); }
}

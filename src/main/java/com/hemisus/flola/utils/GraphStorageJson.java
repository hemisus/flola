package com.hemisus.flola.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hemisus.flola.model.*;
import com.hemisus.flola.utils.OperationRegistry.OperationConfig;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** FLOLA 프로젝트 폴더 저장/로드 (Gson). project.flola + customops/<name>.json */
public final class GraphStorageJson {

    private static final int CURRENT_VERSION = 2;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 프로젝트 진입 파일 이름 (폴더 안). 사용자가 직접 더블클릭/선택하는 파일. */
    public static final String PROJECT_FILE = "project.flola";

    private GraphStorageJson() {}

    // ── DTO ───────────────────────────────────────────────
    private record ProjectDTO(
        int version,
        List<TensorDTO>        tensors,
        List<SidebarTensorDTO> sidebarTensors,
        List<String>           sidebarCustomOps,   // CustomOperation uuid
        GraphDTO               mainGraph
    ) {}

    private record GraphDTO(List<NodeDTO> nodes, List<ConnectionDTO> connections) {}
    private record CustomOpDTO(String uuid, String name, List<TensorDTO> tensors, GraphDTO subGraph) {}

    private record TensorDTO(String uuid, String name, int[] shape, double[] data, String[] axisNames) {}
    private record SidebarTensorDTO(String tensorUuid, String nodeName) {}

    private record NodeDTO(
        int id, String type,
        String tensorUuid,        // TensorNode
        String operationType,     // GenericOperationNode
        String customOpUuid,      // CustomOperationNode → 정의 참조
        String nodeName,          // 공통 (utility name / instance name)
        Integer inputPorts, Integer outputPorts,
        Boolean inputVariadic, Boolean outputVariadic,
        Map<String, Object> params,
        double x, double y
    ) {}

    private record ConnectionDTO(int[] from, int[] to) {}

    private interface PosProvider { double[] pos(GraphNode n); }

    // ── 로드 결과 ──────────────────────────────────────────
    public record CanvasNodeEntry(GraphNode node, double x, double y) {}

    public static final class LoadResult {
        public final List<TensorNode>          sidebarTensors;
        public final List<CustomOperationNode> sidebarCustomOps;
        public final List<CanvasNodeEntry>     canvasNodes;
        LoadResult(List<TensorNode> s, List<CustomOperationNode> c, List<CanvasNodeEntry> e) {
            sidebarTensors = s; sidebarCustomOps = c; canvasNodes = e;
        }
    }

    // ── SAVE ──────────────────────────────────────────────
    public static void save(File projectDir, Graph graph,
                            List<TensorNode> sidebarTensors,
                            List<CustomOperationNode> sidebarCustomOps,
                            Map<GraphNode, double[]> positions) throws IOException {

        if (!projectDir.exists()) projectDir.mkdirs();
        File customOpsDir = new File(projectDir, "customops");
        customOpsDir.mkdirs();

        // 1) 도달 가능한 CustomOperation 전이 closure
        LinkedHashMap<String, CustomOperation> customOps = new LinkedHashMap<>();
        for (CustomOperationNode t : sidebarCustomOps) collectCustomOps(t.getOperation(), customOps);
        for (GraphNode n : graph.getAllNodes())
            if (n instanceof CustomOperationNode cn) collectCustomOps(cn.getOperation(), customOps);

        // 2) 메인 Tensor 풀 (사이드바 텐서 + 캔버스 텐서). 서브그래프 텐서는 각 op 파일로.
        LinkedHashMap<String, TensorDTO> tensorPool = new LinkedHashMap<>();
        for (TensorNode tn : sidebarTensors) addTensor(tensorPool, tn.getTensor());

        // 3) 메인 그래프 (위치는 canvas positions)
        GraphDTO mainGraph = serializeGraph(graph, tensorPool,
            n -> positions.getOrDefault(n, new double[]{0, 0}));

        // 4) project.flola
        List<SidebarTensorDTO> sbTensors = new ArrayList<>();
        for (TensorNode tn : sidebarTensors)
            sbTensors.add(new SidebarTensorDTO(tn.getTensor().getUuid(), tn.getNodeName()));
        List<String> sbCustomOps = new ArrayList<>();
        for (CustomOperationNode t : sidebarCustomOps) sbCustomOps.add(t.getOperation().getUuid());

        writeJson(new File(projectDir, PROJECT_FILE), new ProjectDTO(
            CURRENT_VERSION, new ArrayList<>(tensorPool.values()), sbTensors, sbCustomOps, mainGraph));

        // 5) customops/<name>.json (이전 잔재 제거 후 새로 작성, 각 자체 완결)
        File[] stale = customOpsDir.listFiles((d, fn) -> fn.endsWith(".json"));
        if (stale != null) for (File f : stale) f.delete();

        Set<String> usedNames = new HashSet<>();
        for (CustomOperation op : customOps.values()) {
            LinkedHashMap<String, TensorDTO> subPool = new LinkedHashMap<>();
            GraphDTO sub = serializeGraph(op.getSubGraph(), subPool, n -> {
                Graph.NodePosition p = op.getSubGraph().getNodePosition(n);
                return (p != null) ? new double[]{p.x(), p.y()} : new double[]{0, 0};
            });
            writeJson(new File(customOpsDir, uniqueFileName(op.getName(), usedNames)),
                new CustomOpDTO(op.getUuid(), op.getName(), new ArrayList<>(subPool.values()), sub));
        }
    }

    private static void collectCustomOps(CustomOperation op, Map<String, CustomOperation> out) {
        if (op == null || out.containsKey(op.getUuid())) return;
        out.put(op.getUuid(), op);
        for (GraphNode n : op.getSubGraph().getAllNodes())
            if (n instanceof CustomOperationNode cn) collectCustomOps(cn.getOperation(), out);
    }

    private static GraphDTO serializeGraph(Graph g, Map<String, TensorDTO> tensorPool, PosProvider pos) {
        List<GraphNode> nodes = g.getAllNodes();
        Map<GraphNode, Integer> idMap = new IdentityHashMap<>();
        for (int i = 0; i < nodes.size(); i++) idMap.put(nodes.get(i), i);

        List<NodeDTO> nodeDTOs = new ArrayList<>();
        for (GraphNode n : nodes) {
            double[] p = pos.pos(n);
            nodeDTOs.add(toNodeDTO(n, idMap.get(n), p[0], p[1], tensorPool));
        }
        List<ConnectionDTO> connDTOs = new ArrayList<>();
        for (ConnectionModel c : g.getAllConnections()) {
            Integer s = idMap.get(c.getSource()), d = idMap.get(c.getTarget());
            if (s == null || d == null) continue;
            connDTOs.add(new ConnectionDTO(
                new int[]{s, c.getSourcePortIndex()}, new int[]{d, c.getTargetPortIndex()}));
        }
        return new GraphDTO(nodeDTOs, connDTOs);
    }

    private static NodeDTO toNodeDTO(GraphNode n, int id, double x, double y, Map<String, TensorDTO> tensorPool) {
        if (n instanceof TensorNode tn) {
            addTensor(tensorPool, tn.getTensor());
            return new NodeDTO(id, "TensorNode", tn.getTensor().getUuid(), null, null,
                tn.getNodeName(), null, null, null, null, null, x, y);
        }
        if (n instanceof CustomOperationNode cn) {
            return new NodeDTO(id, "CustomOperationNode", null, null, cn.getOperation().getUuid(),
                cn.getInstanceName(), null, null, null, null, null, x, y);
        }
        if (n instanceof InputNode in) {
            return new NodeDTO(id, "InputNode", null, null, null, in.getName(),
                null, null, null, null, null, x, y);
        }
        if (n instanceof OutputNode out) {
            return new NodeDTO(id, "OutputNode", null, null, null, out.getName(),
                null, null, null, null, null, x, y);
        }
        if (n instanceof GenericOperationNode op) {
            Map<String, Object> params = op.getParams();
            return new NodeDTO(id, "GenericOperationNode", null, op.getOperationType(), null,
                op.getNodeName(), op.getInputPortCount(), op.getOutputPortCount(),
                op.isInputVariadic(), op.isOutputVariadic(),
                params.isEmpty() ? null : new LinkedHashMap<>(params), x, y);
        }
        return new NodeDTO(id, "Unknown", null, null, null, n.getNodeName(),
            null, null, null, null, null, x, y);
    }

    private static void addTensor(Map<String, TensorDTO> pool, Tensor t) {
        pool.computeIfAbsent(t.getUuid(), k -> new TensorDTO(
            t.getUuid(), t.getName(), t.getShape(), t.getRawData().clone(), t.getAxisNames()));
    }

    private static String uniqueFileName(String name, Set<String> used) {
        String base = sanitize(name), fn = base + ".json";
        int i = 2;
        while (!used.add(fn)) fn = base + "_" + (i++) + ".json";
        return fn;
    }
    private static String sanitize(String name) {
        String s = (name == null) ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return s.isEmpty() ? "custom" : s;
    }
    private static void writeJson(File f, Object dto) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            GSON.toJson(dto, w);
        }
    }
    // ── LOAD ──────────────────────────────────────────────
    public static LoadResult load(File projectFile, Graph graph) throws IOException {
        if (!projectFile.exists())
            throw new IOException(projectFile.getName() + " (을)를 찾을 수 없습니다");
        File projectDir = projectFile.getParentFile();
        if (projectDir == null) projectDir = new File(".");

        ProjectDTO project;
        try (Reader r = new InputStreamReader(new FileInputStream(projectFile), StandardCharsets.UTF_8)) {
            project = GSON.fromJson(r, ProjectDTO.class);
        }
        if (project == null || project.version() <= 0)
            throw new IOException("유효한 FLOLA 프로젝트 파일이 아닙니다 (" + PROJECT_FILE + "을 선택하세요)");

        File customOpsDir = new File(projectDir, "customops");
        // 1) CustomOp 정의 풀 (폴더 스캔 → 2-pass)
        List<CustomOpDTO> opDTOs = new ArrayList<>();
        File[] opFiles = customOpsDir.listFiles((d, fn) -> fn.endsWith(".json"));
        if (opFiles != null) for (File f : opFiles) {
            try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                CustomOpDTO dto = GSON.fromJson(r, CustomOpDTO.class);
                if (dto != null && dto.uuid() != null) opDTOs.add(dto);
            } catch (Exception ex) { System.err.println("CustomOp 로드 실패: " + f.getName() + " — " + ex.getMessage()); }
        }
        Map<String, CustomOperation> opPool = new HashMap<>();
        for (CustomOpDTO dto : opDTOs)                                  // 1a) shell
            opPool.put(dto.uuid(), CustomOperation.forStorage(dto.uuid(), dto.name(), new Graph()));

        Map<String, Map<Integer, GraphNode>> opIdMaps = new HashMap<>();
        for (CustomOpDTO dto : opDTOs) {                               // 1b) 노드만 생성
            Graph sub = opPool.get(dto.uuid()).getSubGraph();
            Map<String, Tensor> subTensors = restoreTensorPool(dto.tensors());
            Map<Integer, GraphNode> idMap = new HashMap<>();
            if (dto.subGraph() != null && dto.subGraph().nodes() != null)
                for (NodeDTO nd : dto.subGraph().nodes()) {
                    GraphNode node = buildNode(nd, subTensors, opPool);
                    if (node == null) continue;
                    idMap.put(nd.id(), node);
                    sub.addNode(node);
                    sub.setNodePosition(node, (int) nd.x(), (int) nd.y());
                }
            opIdMaps.put(dto.uuid(), idMap);
        }
        for (CustomOperation op : opPool.values())                     // 1c) 포트수 확정
            for (GraphNode n : op.getSubGraph().getAllNodes())
                if (n instanceof CustomOperationNode cn) cn.refreshPortCounts();
        for (CustomOpDTO dto : opDTOs)                                 // 1d) 연결 복원
            connectGraph(dto.subGraph(), opIdMaps.get(dto.uuid()), opPool.get(dto.uuid()).getSubGraph());

        // 2) 메인 Tensor 풀
        Map<String, Tensor> tensorPool = restoreTensorPool(project.tensors());

        // 3) 사이드바 텐서
        List<TensorNode> sidebar = new ArrayList<>();
        if (project.sidebarTensors() != null)
            for (SidebarTensorDTO sd : project.sidebarTensors()) {
                Tensor t = tensorPool.get(sd.tensorUuid());
                if (t == null) continue;
                TensorNode node = new TensorNode(t);
                if (sd.nodeName() != null && !sd.nodeName().isBlank()) node.setNodeName(sd.nodeName());
                sidebar.add(node);
            }

        // 4) 사이드바 CustomOp 템플릿
        List<CustomOperationNode> sidebarOps = new ArrayList<>();
        if (project.sidebarCustomOps() != null)
            for (String uuid : project.sidebarCustomOps()) {
                CustomOperation op = opPool.get(uuid);
                if (op != null) sidebarOps.add(new CustomOperationNode(op));
            }

        // 5) 메인 캔버스 노드 + 연결
        List<CanvasNodeEntry> entries = new ArrayList<>();
        Map<Integer, GraphNode> mainIdMap = new HashMap<>();
        if (project.mainGraph() != null && project.mainGraph().nodes() != null)
            for (NodeDTO nd : project.mainGraph().nodes()) {
                GraphNode node = buildNode(nd, tensorPool, opPool);
                if (node == null) continue;
                mainIdMap.put(nd.id(), node);
                graph.addNode(node);
                entries.add(new CanvasNodeEntry(node, nd.x(), nd.y()));
            }
        if (project.mainGraph() != null) connectGraph(project.mainGraph(), mainIdMap, graph);

        return new LoadResult(sidebar, sidebarOps, entries);
    }

    private static Map<String, Tensor> restoreTensorPool(List<TensorDTO> dtos) {
        Map<String, Tensor> pool = new HashMap<>();
        if (dtos != null) for (TensorDTO td : dtos) {
            Tensor t = Tensor.forStorage(td.uuid(), td.name(), td.shape(), td.data());
            if (td.axisNames() != null && td.axisNames().length > 0) t.setAxisNames(td.axisNames());
            pool.put(t.getUuid(), t);
        }
        return pool;
    }

    private static void connectGraph(GraphDTO dto, Map<Integer, GraphNode> idMap, Graph g) {
        if (dto == null || dto.connections() == null || idMap == null) return;
        for (ConnectionDTO cd : dto.connections()) {
            if (cd.from() == null || cd.to() == null) continue;
            GraphNode src = idMap.get(cd.from()[0]), dest = idMap.get(cd.to()[0]);
            if (src == null || dest == null) continue;
            try { g.connect(src, cd.from()[1], dest, cd.to()[1]); }
            catch (Exception ex) { System.err.println("연결 복원 실패: " + ex.getMessage()); }
        }
    }

    private static GraphNode buildNode(NodeDTO cd, Map<String, Tensor> tensorPool, Map<String, CustomOperation> opPool) {
        switch (cd.type() == null ? "" : cd.type()) {
            case "TensorNode" -> {
                Tensor t = tensorPool.get(cd.tensorUuid());
                if (t == null) { System.err.println("tensorUuid 누락: " + cd.tensorUuid()); return null; }
                TensorNode node = new TensorNode(t);
                if (cd.nodeName() != null) node.setNodeName(cd.nodeName());
                return node;
            }
            case "GenericOperationNode" -> {
                String opType = cd.operationType();
                if (opType == null || !OperationRegistry.contains(opType)) {
                    System.err.println("미등록 연산 → 건너뜀: " + opType); return null;
                }
                OperationConfig cfg = OperationRegistry.getConfig(opType);
                int inPorts  = cd.inputPorts()  != null ? cd.inputPorts()  : cfg.inputPorts();
                int outPorts = cd.outputPorts() != null ? cd.outputPorts() : cfg.outputPorts();
                boolean inVar  = cd.inputVariadic()  != null ? cd.inputVariadic()  : cfg.inputVariadic();
                boolean outVar = cd.outputVariadic() != null ? cd.outputVariadic() : cfg.outputVariadic();
                GenericOperationNode node = new GenericOperationNode(
                    opType, inPorts, outPorts, inVar, outVar, OperationRegistry.get(opType));
                if (cd.nodeName() != null) node.setNodeName(cd.nodeName());
                cfg.defaultParams().forEach(node::setParam);
                if (cd.params() != null)
                    cd.params().forEach((k, v) -> node.setParam(k, coerce(v, cfg.defaultParams().get(k))));
                return node;
            }
            case "CustomOperationNode" -> {
                CustomOperation op = opPool.get(cd.customOpUuid());
                if (op == null) { System.err.println("CustomOp 정의 누락: " + cd.customOpUuid()); return null; }
                CustomOperationNode node = new CustomOperationNode(op);
                if (cd.nodeName() != null && !cd.nodeName().isBlank()) node.setInstanceName(cd.nodeName());
                return node;
            }
            case "InputNode"  -> { return new InputNode (cd.nodeName() != null ? cd.nodeName() : "input");  }
            case "OutputNode" -> { return new OutputNode(cd.nodeName() != null ? cd.nodeName() : "output"); }
            default -> { System.err.println("복원 불가 타입: " + cd.type()); return null; }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object coerce(Object saved, Object def) {
        if (saved == null) return def;
        if (def instanceof Integer) return ((Number) saved).intValue();
        if (def instanceof Long)    return ((Number) saved).longValue();
        if (def instanceof Double)  return ((Number) saved).doubleValue();
        if (def instanceof Float)   return ((Number) saved).floatValue();
        if (def instanceof Boolean) return (saved instanceof Boolean b) ? b : Boolean.parseBoolean(saved.toString());
        if (def instanceof Enum<?> e) return Enum.valueOf((Class) e.getDeclaringClass(), saved.toString());
        if (def instanceof String)  return saved.toString();
        return saved;
    }
}
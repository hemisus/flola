package com.hemisus.flola.utils;

import com.hemisus.flola.model.Tensor;
import com.hemisus.flola.utils.TensorOperations.SplitType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

public class OperationRegistry {

    // ── 카테고리 상수 ────────────────────────────────────────────────
    public static final String CATEGORY_BASIC      = "Basic Operation";
    public static final String CATEGORY_ACTIVATION = "Activation Function";
    public static final String CATEGORY_ADVANCED   = "Advanced";

    /** OperationConfig에 category 필드 추가됨 */
    public record OperationConfig(
        String                                                       category,
        int                                                          inputPorts,
        int                                                          outputPorts,
        boolean                                                      inputVariadic,
        boolean                                                      outputVariadic,
        Map<String, Object>                                          defaultParams,
        BiFunction<List<Tensor>, Map<String, Object>, List<Tensor>> function
    ) {}

    private static final LinkedHashMap<String, OperationConfig> registry = new LinkedHashMap<>();

    static {
        // ── Basic Operation (10) ─────────────────────────────────────
        add(CATEGORY_BASIC, "Add", 2, 1, false, false, null,
            (in, p) -> Collections.singletonList(TensorOperations.add(in.get(0), in.get(1))));
        add(CATEGORY_BASIC, "Subtract", 2, 1, false, false, null,
            (in, p) -> Collections.singletonList(TensorOperations.subtract(in.get(0), in.get(1))));
        add(CATEGORY_BASIC, "Matrix Multiply", 2, 1, false, false, null,
            (in, p) -> Collections.singletonList(TensorOperations.matmul(in.get(0), in.get(1))));
        add(CATEGORY_BASIC, "Elementwise Multiply", 2, 1, false, false, null,
            (in, p) -> Collections.singletonList(TensorOperations.multiply(in.get(0), in.get(1))));
        add(CATEGORY_BASIC, "Divide", 2, 1, false, false, null,
            (in, p) -> Collections.singletonList(TensorOperations.divide(in.get(0), in.get(1))));
        add(CATEGORY_BASIC, "Negate", 1, 1, false, false, null,
            (in, p) -> Collections.singletonList(TensorOperations.negate(in.get(0))));
        add(CATEGORY_BASIC, "Transpose", 1, 1, false, false, null,
            (in, p) -> Collections.singletonList(TensorOperations.transpose(in.get(0))));
        add(CATEGORY_BASIC, "Clear", 1, 1, false, false, null,
            (in, p) -> Collections.singletonList(TensorOperations.clearToZeros(in.get(0))));
        add(CATEGORY_BASIC, "Sum", 1, 1, true, false, null,
            (in, p) -> Collections.singletonList(TensorOperations.sum(in)));
        add(CATEGORY_BASIC, "Average", 1, 1, true, false, null,
            (in, p) -> Collections.singletonList(TensorOperations.average(in)));

        // ── Activation Function (4) ──────────────────────────────────
        add(CATEGORY_ACTIVATION, "ReLU", 1, 1, false, false, null,
            (in, p) -> Collections.singletonList(TensorOperations.relu(in.get(0))));
        add(CATEGORY_ACTIVATION, "Sigmoid", 1, 1, false, false, null,
            (in, p) -> Collections.singletonList(TensorOperations.sigmoid(in.get(0))));
        add(CATEGORY_ACTIVATION, "Tanh", 1, 1, false, false, null,
            (in, p) -> Collections.singletonList(TensorOperations.tanh(in.get(0))));
        add(CATEGORY_ACTIVATION, "Softmax", 1, 1, false, false, Map.of("axis", 1),
            (in, p) -> Collections.singletonList(
                TensorOperations.softmax(in.get(0), (int) p.get("axis"))));

        // ── Advanced (10) ────────────────────────────────────────────
        add(CATEGORY_ADVANCED, "Projection", 2, 2, false, false, null, (in, p) -> {
            Tensor v = in.size() > 0 ? in.get(0) : null;
            Tensor u = in.size() > 1 ? in.get(1) : null;
            return TensorOperations.project(v, u);
        });
        add(CATEGORY_ADVANCED, "Concatenate", 1, 1, true, false, Map.of("axis", 0),
            (in, p) -> Collections.singletonList(
                TensorOperations.concatenate(in, (int) p.get("axis"))));
        add(CATEGORY_ADVANCED, "Split", 1, 2, false, false,
            Map.of("axis", 0, "type", SplitType.NUM_CHUNKS, "value", 2),
            (in, p) -> TensorOperations.split(
                in.get(0), (int) p.get("axis"),
                (SplitType) p.get("type"), (int) p.get("value")));
        add(CATEGORY_ADVANCED, "View", 1, 1, false, false, Map.of("shape", "-1"),
            (in, p) -> {
                String shapeStr = (String) p.getOrDefault("shape", "-1");
                String[] parts  = shapeStr.split(",");
                int[] shape = new int[parts.length];
                for (int i = 0; i < parts.length; i++)
                    shape[i] = Integer.parseInt(parts[i].trim());
                return Collections.singletonList(TensorOperations.view(in.get(0), shape));
            });
        add(CATEGORY_ADVANCED, "Conv2D", 2, 1, false, false,
            Map.of("stride", 1, "padding", 0),
            (in, p) -> Collections.singletonList(
                TensorOperations.conv2d(in.get(0), in.get(1),
                    (int) p.getOrDefault("stride", 1),
                    (int) p.getOrDefault("padding", 0))));
        add(CATEGORY_ADVANCED, "ConvTranspose2D", 2, 1, false, false,
            Map.of("stride", 2, "padding", 0),
            (in, p) -> Collections.singletonList(
                TensorOperations.convTranspose2d(in.get(0), in.get(1),
                    (int) p.getOrDefault("stride", 2),
                    (int) p.getOrDefault("padding", 0))));
        add(CATEGORY_ADVANCED, "MaxPool2D", 1, 1, false, false,
            Map.of("kernel_size", 2, "stride", 2),
            (in, p) -> {
                int ks = (int) p.getOrDefault("kernel_size", 2);
                return Collections.singletonList(
                    TensorOperations.maxPool2d(in.get(0), ks,
                        (int) p.getOrDefault("stride", ks)));
            });
        add(CATEGORY_ADVANCED, "Upsample", 1, 1, false, false,
            Map.of("scale_h", 2, "scale_w", 2, "mode", "nearest"),
            (in, p) -> Collections.singletonList(
                TensorOperations.upsample(in.get(0),
                    (int)    p.getOrDefault("scale_h", 2),
                    (int)    p.getOrDefault("scale_w", 2),
                    (String) p.getOrDefault("mode", "nearest"))));
        add(CATEGORY_ADVANCED, "SVD", 1, 3, false, false, null,
            (in, p) -> TensorOperations.svd(in.get(0)));
        add(CATEGORY_ADVANCED, "Eigenvalues", 1, 2, false, false, null,
            (in, p) -> TensorOperations.eigenDecompose(in.get(0)));
    }

    private static void add(String category, String name,
                             int inPorts, int outPorts,
                             boolean iv, boolean ov,
                             Map<String, Object> defaultParams,
                             BiFunction<List<Tensor>, Map<String, Object>, List<Tensor>> fn) {
        registry.put(name, new OperationConfig(
            category, inPorts, outPorts, iv, ov,
            (defaultParams != null) ? defaultParams : Map.of(), fn));
    }

    public static BiFunction<List<Tensor>, Map<String, Object>, List<Tensor>> get(String name) {
        OperationConfig cfg = registry.get(name);
        return (cfg != null) ? cfg.function() : null;
    }
    public static OperationConfig getConfig(String name) { return registry.get(name); }
    public static LinkedHashMap<String, OperationConfig> getAll() { return registry; }
    public static boolean contains(String name) { return registry.containsKey(name); }
    public static void register(String name, OperationConfig config) { registry.put(name, config); }
}
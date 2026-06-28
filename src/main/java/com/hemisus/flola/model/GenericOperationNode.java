package com.hemisus.flola.model;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

public class GenericOperationNode extends OperationNode {

    private final BiFunction<List<Tensor>, Map<String, Object>, List<Tensor>> operation;
    private final Map<String, Object> params = new HashMap<>();

    /**
     * 포트별 영속 Tensor 객체.
     * compute() 재실행 시 shape이 같으면 데이터만 갱신하여 Tensor 객체 동일성을 유지한다.
     * → 외부에서 이 Tensor에 설정한 axisNames 등이 재계산 후에도 보존된다.
     */
    private final Map<Integer, Tensor> persistentOutputs = new HashMap<>();

    public GenericOperationNode(String name, int inCount, int outCount,
                                boolean inputVariadic, boolean outputVariadic,
                                BiFunction<List<Tensor>, Map<String, Object>, List<Tensor>> operation) {
        super(name);
        this.inputPortCount  = inCount;
        this.outputPortCount = outCount;
        this.inputVariadic   = inputVariadic;
        this.outputVariadic  = outputVariadic;
        this.operation       = operation;
    }

    public void setInputPortCount(int count) {
        this.inputPortCount = (count == 0) ? 1 : count;
    }

    public Object getParam(String key) { return params.get(key); }

    public BiFunction<List<Tensor>, Map<String, Object>, List<Tensor>> getOperation() {
        return operation;
    }

    /** returns new HashMap with params */
    public Map<String, Object> getParams() { return new HashMap<>(params); }

    @Override
    public void compute(List<Tensor> inputs) {
        if (!isDirty) return;

        List<Tensor> results = operation.apply(inputs, params);
        cachedOutputs.clear();

        if (results != null) {
            int portIdx = 0;
            for (Tensor result : results) {
                if (result == null) { portIdx++; continue; }

                Tensor persistent = persistentOutputs.get(portIdx);

                if (persistent != null
                        && Arrays.equals(persistent.getShape(), result.getShape())) {
                    // shape 동일: 데이터만 갱신 → Tensor 객체 재사용, axisNames 보존
                    persistent.copyDataFrom(result);
                } else {
                    // 첫 compute이거나 shape 변경: 새 객체 채택
                    persistent = result;
                    persistentOutputs.put(portIdx, persistent);
                }

                cachedOutputs.add(persistent);
                portIdx++;
            }
        }

        // output 축 이름은 사용자가 직접 편집할 예정
        // (persistentOutputs에 저장된 axisNames는 재계산 후에도 유지됨)

        this.isDirty = false;
    }

    public void setParam(String key, Object value) {
        params.put(key, value);
        setDirty();
    }
}

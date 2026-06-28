package com.hemisus.flola.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import com.hemisus.flola.utils.DataConverter;

public class Tensor {
    private final String uuid;
    private String name;
    private int[]  shape;
    private double[] data;
    private String[] axisNames;
    private String valueString = null;
    private final List<Runnable> changeListeners = new ArrayList<>();

    public enum Kind { SCALAR, VECTOR, MATRIX, TENSOR }

    public Tensor(String name, int... shape) {
        this.uuid      = UUID.randomUUID().toString();
        this.name      = name;
        this.shape     = shape.clone();
        this.data      = new double[totalSize(shape)];
        this.axisNames = defaultAxisNames(shape.length);
    }

    /** for makeCopy / buildReshaped - new UUID assigned */
    private Tensor(String name, int[] shape, double[] data) {
        this.uuid      = UUID.randomUUID().toString();
        this.name      = name;
        this.shape     = shape.clone();
        this.data      = data.clone();
        this.axisNames = defaultAxisNames(shape.length);
    }

    /** 저장 파일 복원 전용 — UUID를 그대로 복원한다 */
    private Tensor(String uuid, String name, int[] shape, double[] data) {
        this.uuid      = (uuid != null && !uuid.isBlank()) ? uuid : UUID.randomUUID().toString();
        this.name      = name;
        this.shape     = shape.clone();
        this.data      = data.clone();
        this.axisNames = defaultAxisNames(shape.length);
    }

    /**
     * 저장 파일 복원 전용 팩토리.
     * UUID를 원본 그대로 복원하여 공유 참조 관계를 재구성한다.
     */
    public static Tensor forStorage(String uuid, String name, int[] shape, double[] data) {
        return new Tensor(uuid, name, shape, data);
    }

    // ── 정적 팩토리 ───────────────────────────────────────────

    public static Tensor scalar(String name, double value) {
        Tensor t = new Tensor(name, 1);
        t.data[0] = value;
        return t;
    }

    public static Tensor vector(String name, int n) {
        return new Tensor(name, 1, n);
    }

    public static Tensor matrix(String name, int rows, int cols) {
        return new Tensor(name, rows, cols);
    }

    // ── Kind 추론 ─────────────────────────────────────────────

    public static Kind inferKind(int[] shape) {
        int rank = shape.length;
        if (rank == 0) return Kind.SCALAR;
        if (rank == 1) return shape[0] == 1 ? Kind.SCALAR : Kind.VECTOR;
        if (rank == 2) {
            int r = shape[0], c = shape[1];
            if (r == 1 && c == 1) return Kind.SCALAR;
            if (r == 1 || c == 1) return Kind.VECTOR;
            return Kind.MATRIX;
        }
        return Kind.TENSOR;
    }

    private static int totalSize(int[] shape) {
        int s = 1;
        for (int d : shape) s *= d;
        return s;
    }

    // ── 데이터 접근 ───────────────────────────────────────────

    public double get(int... indices)           { return data[flatIndex(indices)]; }
    public void   set(double value, int... indices) { data[flatIndex(indices)] = value; notifyChange(); }
    public double getFlat(int i)                { return data[i]; }
    public void   setFlat(int i, double v)      { data[i] = v; }
    public int    dataSize()                    { return data.length; }
    public double[] getRawData()                { return data; }

    public int flatIndex(int[] indices) {
        if (indices.length != shape.length)
            throw new IllegalArgumentException(
                "Rank mismatch: expected " + shape.length + ", got " + indices.length);
        int idx = 0, stride = 1;
        for (int i = shape.length - 1; i >= 0; i--) {
            if (indices[i] < 0 || indices[i] >= shape[i])
                throw new IndexOutOfBoundsException(
                    "Index " + indices[i] + " out of bounds for dim " + i);
            idx += indices[i] * stride;
            stride *= shape[i];
        }
        return idx;
    }

    public static void unflatten(int flat, int[] shape, int[] out) {
        for (int i = shape.length - 1; i >= 0; i--) {
            out[i] = flat % shape[i];
            flat  /= shape[i];
        }
    }

    // ── 복사 ─────────────────────────────────────────────────

    /** 편집용 독립 복사본 (새 UUID) */
    public Tensor makeCopy() {
        Tensor copy = new Tensor(name, shape, data);   // private ctor → 새 UUID
        copy.axisNames = this.axisNames.clone();
        return copy;
    }

    /** shape + data + axisNames 전부 복사. UUID는 변경하지 않는다. */
    public void copyValuesFrom(Tensor other) {
        this.shape     = other.shape.clone();
        this.data      = other.data.clone();
        this.axisNames = other.axisNames.clone();
        notifyChange();
    }

    /** shape + data만 복사. axisNames·UUID는 변경하지 않는다. */
    public void copyDataFrom(Tensor other) {
        this.shape = other.shape.clone();
        this.data  = other.data.clone();
        notifyChange();
    }

    public boolean equalValue(Tensor other) {
        return other != null
            && Arrays.equals(shape, other.shape)
            && Arrays.equals(data,  other.data);
    }

    // ── 2D 슬라이스 뷰 ───────────────────────────────────────

    public double[][] to2DArray(int rowAxis, int colAxis, int[] fixedIndices) {
        int rank = shape.length;
        if (rank == 0) return new double[][]{{data[0]}};
        if (rank == 1) {
            double[][] r = new double[1][shape[0]];
            System.arraycopy(data, 0, r[0], 0, shape[0]);
            return r;
        }
        int rows = shape[rowAxis], cols = shape[colAxis];
        double[][] result = new double[rows][cols];
        int[] idx = fixedIndices.clone();
        for (int r = 0; r < rows; r++) {
            idx[rowAxis] = r;
            for (int c = 0; c < cols; c++) { idx[colAxis] = c; result[r][c] = get(idx); }
        }
        return result;
    }

    public double[][] to2DArray() {
        int rank = shape.length;
        if (rank < 2) return to2DArray(0, 0, new int[Math.max(rank, 1)]);
        return to2DArray(rank - 2, rank - 1, new int[rank]);
    }

    public void set2DSlice(double[][] values, int rowAxis, int colAxis, int[] fixedIndices) {
        int rows = shape[rowAxis], cols = shape[colAxis];
        int[] idx = fixedIndices.clone();
        for (int r = 0; r < Math.min(rows, values.length); r++) {
            idx[rowAxis] = r;
            for (int c = 0; c < Math.min(cols, values[r].length); c++) {
                idx[colAxis] = c; data[flatIndex(idx)] = values[r][c];
            }
        }
        notifyChange();
    }

    // ── 브로드캐스트 ──────────────────────────────────────────

    public static int[] broadcastShape(int[] shapeA, int[] shapeB) {
        int rank = Math.max(shapeA.length, shapeB.length);
        int[] result = new int[rank];
        for (int i = 0; i < rank; i++) {
            int a = paddedDim(shapeA, i, rank), b = paddedDim(shapeB, i, rank);
            if (a != b && a != 1 && b != 1) return null;
            result[i] = Math.max(a, b);
        }
        return result;
    }

    private static int paddedDim(int[] shape, int i, int rank) {
        int offset = rank - shape.length;
        return (i < offset) ? 1 : shape[i - offset];
    }

    public static boolean isBroadcastable(int[] shapeA, int[] shapeB) {
        return broadcastShape(shapeA, shapeB) != null;
    }

    public int broadcastedFlatIndex(int[] outIndices, int[] outShape) {
        int outRank = outShape.length, tRank = shape.length;
        int[] tIndices = new int[tRank];
        for (int i = 0; i < tRank; i++) {
            int outDim = outRank - tRank + i;
            tIndices[i] = (shape[i] == 1) ? 0 : outIndices[outDim];
        }
        return flatIndex(tIndices);
    }

    // ── 축 이름 ───────────────────────────────────────────────

    private static String[] defaultAxisNames(int rank) {
        String[] names = new String[rank];
        for (int i = 0; i < rank; i++) names[i] = "axis_" + i;
        return names;
    }

    private static String[] adjustAxisNames(String[] src, int rank) {
        String[] result = new String[rank];
        for (int i = 0; i < rank; i++)
            result[i] = (i < src.length && src[i] != null && !src[i].isEmpty())
                        ? src[i] : "axis_" + i;
        return result;
    }

    public String getAxisName(int axis) {
        if (axis < 0 || axis >= axisNames.length) return "axis_" + axis;
        String n = axisNames[axis];
        return (n != null && !n.isEmpty()) ? n : "axis_" + axis;
    }

    public void setAxisName(int axis, String name) {
        if (axis < 0 || axis >= axisNames.length) return;
        axisNames[axis] = (name == null) ? "" : name;
    }

    public String[] getAxisNames() { return axisNames.clone(); }

    public void setAxisNames(String[] names) {
        this.axisNames = adjustAxisNames(names, shape.length);
    }

    // ── observers ─────────────────────────────────────────────

    public void addChangeListener(Runnable listener) { changeListeners.add(listener); }
    public void notifyChange() {
        this.valueString = null;
        changeListeners.forEach(Runnable::run);
    }

    public String  getUuid()             { return uuid; }
    public int[]   getShape()            { return shape.clone(); }
    public int     getDim(int axis)      { return shape[axis]; }
    public int     getRank()             { return shape.length; }
    public Kind    getKind()             { return inferKind(shape); }
    public String  getName()             { return name; }
    public void    setName(String name)  { this.name = name; notifyChange(); }

    public String getSummary() {
        if (getKind() == Kind.SCALAR) return "Scalar";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shape.length; i++) {
            if (i > 0) sb.append("×");
            sb.append(shape[i]);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return getKind() + "[" + getSummary() + "](" + name + ")";
    }

    public String getValueString() {
        if (valueString == null)
            valueString = DataConverter.tensorToString(this);
        return valueString;
    }

    public void refreshValueString() {
        valueString = DataConverter.tensorToString(this);
    }

    public void setTensorFromString(String input) {
        Tensor parsed = DataConverter.stringToTensor(input);
        if (parsed != null) { this.copyValuesFrom(parsed); notifyChange(); }
    }
}

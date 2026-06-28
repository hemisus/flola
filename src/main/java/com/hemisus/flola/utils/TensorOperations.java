package com.hemisus.flola.utils;

import com.hemisus.flola.model.Tensor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.DoubleBinaryOperator;

public class TensorOperations {

    private TensorOperations() {}

    // ---------- elementwise operations ---------------
    private static Tensor elementWise(Tensor a, Tensor b, DoubleBinaryOperator op) {
        if (a == null || b == null) return null;

        int[] outShape = Tensor.broadcastShape(a.getShape(), b.getShape());
        if (outShape == null) return null;   // incompatible shapes

        Tensor result = new Tensor("result", outShape);
        int total = result.dataSize();
        int[] outIdx = new int[outShape.length];

        for (int flat = 0; flat < total; flat++) {
            Tensor.unflatten(flat, outShape, outIdx);
            double va = a.getFlat(a.broadcastedFlatIndex(outIdx, outShape));
            double vb = b.getFlat(b.broadcastedFlatIndex(outIdx, outShape));
            result.setFlat(flat, op.applyAsDouble(va, vb));
        }
        return result;
    }

    public static Tensor add(Tensor a, Tensor b) {
        return elementWise(a, b, Double::sum);
    }

    public static Tensor subtract(Tensor a, Tensor b) {
        return elementWise(a, b, (x, y) -> x - y);
    }

    public static Tensor multiply(Tensor a, Tensor b) {
        return elementWise(a, b, (x, y) -> x * y);
    }
    
    public static Tensor divide(Tensor a, Tensor b) {
        return elementWise(a, b, (x, y) -> y != 0 ? x / y : 0.0);
    }

    // ---------- unary operations ---------------
    public static Tensor negate(Tensor t) {
        if (t == null) return null;
        Tensor result = new Tensor("result", t.getShape());
        for (int i = 0; i < t.dataSize(); i++) result.setFlat(i, -t.getFlat(i));
        return result;
    }

    public static Tensor clearToZeros(Tensor t) {
        if (t == null) return null;
        return new Tensor("result", t.getShape());  // initialized default value 0
    }

    // ── Matrix Multiply (batch matmul on last 2 dims) ─────────────────────────

    /**
     * Batch matrix multiply.
     * {@code a}: shape [..., m, k]  ·  {@code b}: shape [..., k, n]  →  [..., m, n]
     *
     * <p>Batch dimensions follow NumPy broadcasting rules:
     * <pre>
     *   [m, k]       × [k, n]       → [m, n]
     *   [b, m, k]    × [k, n]       → [b, m, n]
     *   [b, m, k]    × [b, k, n]    → [b, m, n]
     *   [2, 1, m, k] × [3, k, n]   → [2, 3, m, n]
     * </pre>
     */
    public static Tensor matmul(Tensor a, Tensor b) {
        if (a == null || b == null) return null;
        if (a.getRank() < 2 || b.getRank() < 2) return null;

        int[] shapeA = a.getShape(), shapeB = b.getShape();
        int rankA = shapeA.length, rankB = shapeB.length;

        int m  = shapeA[rankA - 2];
        int k  = shapeA[rankA - 1];
        int k2 = shapeB[rankB - 2];
        int n  = shapeB[rankB - 1];
        if (k != k2) return null;

        // Broadcast over batch dims
        int[] batchA = Arrays.copyOf(shapeA, rankA - 2);
        int[] batchB = Arrays.copyOf(shapeB, rankB - 2);
        int[] batchOut = computeBatchOut(batchA, batchB);
        if (batchOut == null) return null;

        // Output shape = batchOut + [m, n]
        int[] outShape = appendDims(batchOut, m, n);
        Tensor result = new Tensor("matmul", outShape);

        int batchSize = totalSize(batchOut);
        int[] batchIdx = new int[batchOut.length];

        for (int bFlat = 0; bFlat < batchSize; bFlat++) {
            if (batchOut.length > 0) Tensor.unflatten(bFlat, batchOut, batchIdx);

            for (int i = 0; i < m; i++) {
                for (int j = 0; j < n; j++) {
                    double sum = 0;
                    for (int kk = 0; kk < k; kk++) {
                        sum += a.getFlat(batchedMatrixFlat(a, batchIdx, batchA, i, kk))
                             * b.getFlat(batchedMatrixFlat(b, batchIdx, batchB, kk, j));
                    }
                    result.set(sum, concat(batchIdx, new int[]{i, j}));
                }
            }
        }
        return result;
    }

    /** Resolves the flat index for element [r, c] of tensor {@code t} in a batched loop. */
    private static int batchedMatrixFlat(Tensor t, int[] batchIdx,
                                          int[] tBatch, int r, int c) {
        int[] tIdx;
        if (tBatch.length == 0) {
            tIdx = new int[]{r, c};
        } else {
            int[] mappedBatch = new int[tBatch.length];
            int offset = batchIdx.length - tBatch.length;
            for (int i = 0; i < tBatch.length; i++) {
                mappedBatch[i] = (tBatch[i] == 1) ? 0 : batchIdx[offset + i];
            }
            tIdx = concat(mappedBatch, new int[]{r, c});
        }
        return t.flatIndex(tIdx);
    }

    // ── Transpose ─────────────────────────────────────────────────────────────

    /**
     * Transposes the last two dimensions: [..., m, n] → [..., n, m].
     * For rank-1 tensors (vectors) returns a copy unchanged.
     */
    public static Tensor transpose(Tensor t) {
        if (t == null) return null;
        int rank = t.getRank();
        if (rank < 2) return t.makeCopy();

        int[] inShape = t.getShape();
        int[] outShape = inShape.clone();
        outShape[rank - 2] = inShape[rank - 1];
        outShape[rank - 1] = inShape[rank - 2];

        Tensor result = new Tensor("T", outShape);
        int[] outIdx = new int[rank];
        int[] inIdx  = new int[rank];
        int total = result.dataSize();

        for (int flat = 0; flat < total; flat++) {
            Tensor.unflatten(flat, outShape, outIdx);
            System.arraycopy(outIdx, 0, inIdx, 0, rank);
            inIdx[rank - 2] = outIdx[rank - 1];
            inIdx[rank - 1] = outIdx[rank - 2];
            result.setFlat(flat, t.getFlat(t.flatIndex(inIdx)));
        }
        return result;
    }

    /**
     * Full axis permutation (like np.transpose / torch.permute).
     * {@code axes} must be a permutation of [0 .. rank-1].
     */
    public static Tensor permute(Tensor t, int... axes) {
        if (t == null) return null;
        int rank = t.getRank();
        if (axes.length != rank) return null;

        int[] inShape  = t.getShape();
        int[] outShape = new int[rank];
        for (int i = 0; i < rank; i++) outShape[i] = inShape[axes[i]];

        Tensor result = new Tensor("permute", outShape);
        int[] outIdx = new int[rank];
        int[] inIdx  = new int[rank];
        int total = result.dataSize();

        for (int flat = 0; flat < total; flat++) {
            Tensor.unflatten(flat, outShape, outIdx);
            for (int i = 0; i < rank; i++) inIdx[axes[i]] = outIdx[i];
            result.setFlat(flat, t.getFlat(t.flatIndex(inIdx)));
        }
        return result;
    }

    // ── Concatenate ───────────────────────────────────────────────────────────

    /**
     * Concatenates tensors along {@code axis}.
     * All tensors must have identical shapes on every other axis.
     */
    public static Tensor concatenate(List<Tensor> tensors, int axis) {
        if (tensors == null || tensors.isEmpty()) return null;
        for (Tensor t : tensors) if (t == null) return null;

        int rank = tensors.get(0).getRank();
        int[] baseShape = tensors.get(0).getShape();

        int totalOnAxis = 0;
        for (Tensor t : tensors) {
            if (t.getRank() != rank) return null;
            int[] s = t.getShape();
            for (int d = 0; d < rank; d++) {
                if (d == axis) { totalOnAxis += s[d]; }
                else if (s[d] != baseShape[d]) return null;  // shape mismatch
            }
        }

        int[] outShape = baseShape.clone();
        outShape[axis] = totalOnAxis;
        Tensor result = new Tensor("concat", outShape);

        int offset = 0;
        for (Tensor t : tensors) {
            copySliceInto(t, result, axis, offset);
            offset += t.getDim(axis);
        }
        return result;
    }

    private static void copySliceInto(Tensor src, Tensor dst, int axis, int dstOffset) {
        int[] srcShape = src.getShape();
        int[] srcIdx = new int[srcShape.length];
        int[] dstIdx = new int[srcShape.length];
        int total = src.dataSize();

        for (int flat = 0; flat < total; flat++) {
            Tensor.unflatten(flat, srcShape, srcIdx);
            System.arraycopy(srcIdx, 0, dstIdx, 0, srcShape.length);
            dstIdx[axis] = srcIdx[axis] + dstOffset;
            dst.set(src.getFlat(flat), dstIdx);
        }
    }

    // ── Split ─────────────────────────────────────────────────────────────────

    public enum SplitType { CHUNK_SIZE, NUM_CHUNKS }

    /**
     * Splits tensor along {@code axis}.
     * <ul>
     *   <li>NUM_CHUNKS: split into {@code value} equal (or near-equal) chunks</li>
     *   <li>CHUNK_SIZE: each chunk has at most {@code value} elements along the axis</li>
     * </ul>
     */
    public static List<Tensor> split(Tensor t, int axis, SplitType type, int value) {
        if (t == null || value <= 0) return null;

        int dim = t.getDim(axis);
        List<Integer> sizes = chunkSizes(dim, type, value);
        if (sizes == null || sizes.isEmpty()) return null;

        List<Tensor> results = new ArrayList<>();
        int offset = 0;
        for (int size : sizes) {
            results.add(extractSlice(t, axis, offset, size));
            offset += size;
        }
        return results;
    }

    private static List<Integer> chunkSizes(int dim, SplitType type, int value) {
        List<Integer> sizes = new ArrayList<>();
        if (type == SplitType.CHUNK_SIZE) {
            int remaining = dim;
            while (remaining > 0) {
                sizes.add(Math.min(value, remaining));
                remaining -= value;
            }
        } else {
            int base = dim / value, rem = dim % value;
            for (int i = 0; i < value; i++) {
                int s = base + (i < rem ? 1 : 0);
                if (s > 0) sizes.add(s);
            }
        }
        return sizes;
    }

    private static Tensor extractSlice(Tensor src, int axis, int start, int size) {
        int[] srcShape = src.getShape();
        int[] outShape = srcShape.clone();
        outShape[axis] = size;

        Tensor result = new Tensor("slice", outShape);
        int[] outIdx = new int[srcShape.length];
        int[] srcIdx = new int[srcShape.length];
        int total = result.dataSize();

        for (int flat = 0; flat < total; flat++) {
            Tensor.unflatten(flat, outShape, outIdx);
            System.arraycopy(outIdx, 0, srcIdx, 0, srcShape.length);
            srcIdx[axis] = outIdx[axis] + start;
            result.setFlat(flat, src.getFlat(src.flatIndex(srcIdx)));
        }
        return result;
    }

    public static int calculateSplitOutputCount(Tensor t, SplitType type, int value, int axis) {
        if (t == null || value <= 0) return 0;
        int dim = t.getDim(axis);
        if (value > dim && type == SplitType.NUM_CHUNKS) return -1;
        if (type == SplitType.NUM_CHUNKS) return value;
        return (int) Math.ceil((double) dim / value);
    }

    // ── Reduction ─────────────────────────────────────────────────────────────

    /**
     * Element-wise sum of a list of tensors (all shapes must be broadcast-compatible
     * with the first tensor).
     */
    public static Tensor sum(List<Tensor> tensors) {
        if (tensors == null || tensors.isEmpty()) return null;
        Tensor first = tensors.get(0);
        if (first == null) return null;

        Tensor acc = first.makeCopy();
        for (int i = 1; i < tensors.size(); i++) {
            acc = add(acc, tensors.get(i));
            if (acc == null) return null;
        }
        return acc;
    }

    public static Tensor average(List<Tensor> tensors) {
        Tensor total = sum(tensors);
        if (total == null || tensors.isEmpty()) return null;
        double n = tensors.size();
        Tensor result = new Tensor("avg", total.getShape());
        for (int i = 0; i < total.dataSize(); i++) result.setFlat(i, total.getFlat(i) / n);
        return result;
    }

    /**
     * Reduces along {@code axis} by summing, removing that dimension.
     * e.g. shape [2, 3, 4], axis=1 → [2, 4]
     */
    public static Tensor reduceSum(Tensor t, int axis) {
        if (t == null) return null;
        int rank = t.getRank();
        int[] inShape = t.getShape();

        int[] outShape = new int[rank - 1];
        for (int i = 0, j = 0; i < rank; i++) {
            if (i != axis) outShape[j++] = inShape[i];
        }

        Tensor result = new Tensor("reduce_sum", outShape);
        int[] inIdx = new int[rank];
        int[] outIdx = new int[rank - 1];
        int total = t.dataSize();

        for (int flat = 0; flat < total; flat++) {
            Tensor.unflatten(flat, inShape, inIdx);
            for (int i = 0, j = 0; i < rank; i++) {
                if (i != axis) outIdx[j++] = inIdx[i];
            }
            int outFlat = result.flatIndex(outIdx);
            result.setFlat(outFlat, result.getFlat(outFlat) + t.getFlat(flat));
        }
        return result;
    }
    public static Tensor softmax(Tensor t, int axis) {
        if (t == null) return null;
        int[] shape = t.getShape();
        if (axis < 0 || axis >= shape.length) return null;  // 유효하지 않은 axis → null
        Tensor result = new Tensor("softmax", shape);
        int dim = shape[axis];

        // axis 방향으로 슬라이스마다 exp/sum 계산
        int outerSize = 1, innerSize = 1;
        for (int i = 0; i < axis; i++)        outerSize *= shape[i];
        for (int i = axis + 1; i < shape.length; i++) innerSize *= shape[i];

        for (int outer = 0; outer < outerSize; outer++) {
            for (int inner = 0; inner < innerSize; inner++) {
                double max = Double.NEGATIVE_INFINITY;
                for (int d = 0; d < dim; d++) {
                    double v = t.getFlat(outer * dim * innerSize + d * innerSize + inner);
                    if (v > max) max = v;
                }
                double sum = 0;
                for (int d = 0; d < dim; d++) {
                    double e = Math.exp(t.getFlat(outer * dim * innerSize + d * innerSize + inner) - max);
                    result.setFlat(outer * dim * innerSize + d * innerSize + inner, e);
                    sum += e;
                }
                for (int d = 0; d < dim; d++) {
                    int idx = outer * dim * innerSize + d * innerSize + inner;
                    result.setFlat(idx, result.getFlat(idx) / sum);
                }
            }
        }
        return result;
    }

    // ── Activation functions ──────────────────────────────────────────────────

    /** ReLU: max(0, x) elementwise. */
    public static Tensor relu(Tensor t) {
        if (t == null) return null;
        Tensor result = new Tensor("relu", t.getShape());
        for (int i = 0; i < t.dataSize(); i++)
            result.setFlat(i, Math.max(0.0, t.getFlat(i)));
        return result;
    }

    /** Sigmoid: 1 / (1 + exp(-x)) elementwise. */
    public static Tensor sigmoid(Tensor t) {
        if (t == null) return null;
        Tensor result = new Tensor("sigmoid", t.getShape());
        for (int i = 0; i < t.dataSize(); i++)
            result.setFlat(i, 1.0 / (1.0 + Math.exp(-t.getFlat(i))));
        return result;
    }

    /** Tanh elementwise. */
    public static Tensor tanh(Tensor t) {
        if (t == null) return null;
        Tensor result = new Tensor("tanh", t.getShape());
        for (int i = 0; i < t.dataSize(); i++)
            result.setFlat(i, Math.tanh(t.getFlat(i)));
        return result;
    }

    // ── CNN operations ────────────────────────────────────────────────────────

    /**
     * 2D Convolution.
     * <pre>
     *   input  : (N, C_in,  H,  W)
     *   kernel : (C_out, C_in, kH, kW)
     *   output : (N, C_out, H_out, W_out)
     *   H_out = (H + 2*padding - kH) / stride + 1
     * </pre>
     */
    public static Tensor conv2d(Tensor input, Tensor kernel, int stride, int padding) {
        if (input == null || kernel == null) return null;
        if (input.getRank() < 2 || kernel.getRank() < 2) return null;

        int[] inShape = input.getShape();
        int[] kShape  = kernel.getShape();

        // 마지막 두 차원이 (H, W) / (kH, kW)
        int inRank = inShape.length;
        int kRank  = kShape.length;

        int H  = inShape[inRank - 2], W  = inShape[inRank - 1];
        int kH = kShape[kRank - 2],   kW = kShape[kRank - 1];

        int Hout = (H + 2 * padding - kH) / stride + 1;
        int Wout = (W + 2 * padding - kW) / stride + 1;
        if (Hout <= 0 || Wout <= 0) return null;

        // 앞 차원들을 batch로 처리: 입력의 배치 shape
        int[] batchShape = Arrays.copyOf(inShape, inRank - 2);
        int   batchSize  = totalSize(batchShape);

        // 출력 shape = batchShape + [Hout, Wout]
        int[] outShape = appendDims(batchShape, Hout, Wout);
        Tensor result  = new Tensor("conv2d", outShape);

        int[] batchIdx = new int[batchShape.length];
        for (int b = 0; b < batchSize; b++) {
            if (batchShape.length > 0) Tensor.unflatten(b, batchShape, batchIdx);

            for (int oh = 0; oh < Hout; oh++) {
                for (int ow = 0; ow < Wout; ow++) {
                    double sum = 0.0;
                    for (int kh = 0; kh < kH; kh++) {
                        for (int kw = 0; kw < kW; kw++) {
                            int ih = oh * stride - padding + kh;
                            int iw = ow * stride - padding + kw;
                            if (ih >= 0 && ih < H && iw >= 0 && iw < W) {
                                int[] inIdx = concat(batchIdx, new int[]{ih, iw});
                                // 커널도 마지막 두 차원으로 브로드캐스트
                                int[] kIdx  = new int[kRank];
                                int kOffset = kRank - batchShape.length - 2;
                                for (int i = 0; i < batchShape.length; i++) {
                                    int ki = kOffset + i;
                                    // ki가 유효한 배열 인덱스일 때만 할당하도록 수정
                                    if (ki >= 0 && ki < kRank - 2) {
                                        kIdx[ki] = (kShape[ki] > 1) ? batchIdx[i] : 0;
                                    }
                                }
                                kIdx[kRank - 2] = kh;
                                kIdx[kRank - 1] = kw;
                                sum += input.getFlat(input.flatIndex(inIdx))
                                     * kernel.getFlat(kernel.flatIndex(kIdx));
                            }
                        }
                    }
                    result.set(sum, concat(batchIdx, new int[]{oh, ow}));
                }
            }
        }
        return result;
    }

    /**
     * 2D Max Pooling.
     * <pre>
     *   input  : (N, C, H, W)
     *   output : (N, C, H_out, W_out)
     *   H_out = (H - kernelSize) / stride + 1
     * </pre>
     */
    public static Tensor maxPool2d(Tensor input, int kernelSize, int stride) {
        if (input == null) return null;
        if (input.getRank() < 2) return null;

        int[] inShape = input.getShape();
        int   inRank  = inShape.length;

        int H = inShape[inRank - 2];
        int W = inShape[inRank - 1];
        if (H < kernelSize || W < kernelSize) return null;

        int Hout = (H - kernelSize) / stride + 1;
        int Wout = (W - kernelSize) / stride + 1;
        if (Hout <= 0 || Wout <= 0) return null;

        // 앞 차원들을 batch로 처리
        int[] batchShape = Arrays.copyOf(inShape, inRank - 2);
        int   batchSize  = totalSize(batchShape);

        int[] outShape = appendDims(batchShape, Hout, Wout);
        Tensor result  = new Tensor("maxpool2d", outShape);

        int[] batchIdx = new int[batchShape.length];
        for (int b = 0; b < batchSize; b++) {
            if (batchShape.length > 0) Tensor.unflatten(b, batchShape, batchIdx);

            for (int oh = 0; oh < Hout; oh++) {
                for (int ow = 0; ow < Wout; ow++) {
                    double max = Double.NEGATIVE_INFINITY;
                    for (int kh = 0; kh < kernelSize; kh++) {
                        for (int kw = 0; kw < kernelSize; kw++) {
                            int[] idx = concat(batchIdx, new int[]{oh * stride + kh, ow * stride + kw});
                            max = Math.max(max, input.getFlat(input.flatIndex(idx)));
                        }
                    }
                    result.set(max, concat(batchIdx, new int[]{oh, ow}));
                }
            }
        }
        return result;
    }

    /**
     * Flatten: start_dim부터 끝까지의 차원을 단일 차원으로 펼친다.
     * <pre>
     *   (N, C, H, W), start_dim=1  →  (N, C*H*W)   ← CNN 기본
     *   (N, C, H, W), start_dim=2  →  (N, C, H*W)
     *   (N, C, H, W), start_dim=0  →  (N*C*H*W)
     * </pre>
     * start_dim이 음수면 rank 기준 역방향 인덱스 (-1 = 마지막 차원).
     * rank가 0이거나 start_dim이 마지막 차원이면 원본을 복사해 반환한다.
     */
    public static Tensor flatten(Tensor input, int startDim) {
        if (input == null) return null;
        int[] shape = input.getShape();
        int rank = shape.length;
        if (rank == 0) return input.makeCopy();

        // 음수 인덱스 처리
        if (startDim < 0) startDim = rank + startDim;
        startDim = Math.max(0, Math.min(startDim, rank - 1));

        // start_dim 이후를 모두 곱한다
        int flatSize = 1;
        for (int i = startDim; i < rank; i++) flatSize *= shape[i];

        int[] outShape = new int[startDim + 1];
        System.arraycopy(shape, 0, outShape, 0, startDim);
        outShape[startDim] = flatSize;

        Tensor result = new Tensor("flatten", outShape);
        System.arraycopy(input.getRawData(), 0, result.getRawData(), 0, input.dataSize());
        return result;
    }

    /**
     * View: 데이터는 그대로 두고 shape만 변경한다 (PyTorch view와 동일).
     * <pre>
     *   newShape에서 -1은 하나만 허용되며, 나머지 차원으로부터 자동 추론된다.
     *   총 원소 수가 맞지 않으면 null 반환.
     * </pre>
     */
    public static Tensor view(Tensor input, int[] newShape) {
        if (input == null || newShape == null) return null;

        // -1 추론
        int inferAxis = -1;
        int knownSize = 1;
        for (int i = 0; i < newShape.length; i++) {
            if (newShape[i] == -1) {
                if (inferAxis != -1) return null;  // -1이 두 개 이상
                inferAxis = i;
            } else if (newShape[i] <= 0) {
                return null;
            } else {
                knownSize *= newShape[i];
            }
        }

        int total = input.dataSize();
        int[] resolvedShape = newShape.clone();
        if (inferAxis >= 0) {
            if (total % knownSize != 0) return null;
            resolvedShape[inferAxis] = total / knownSize;
        } else if (knownSize != total) {
            return null;  // 원소 수 불일치
        }

        Tensor result = new Tensor("view", resolvedShape);
        System.arraycopy(input.getRawData(), 0, result.getRawData(), 0, total);
        return result;
    }

    // ── Upsampling ────────────────────────────────────────────────────────────

    /**
     * 업샘플링. 마지막 두 차원 (H, W)을 확대한다.
     * <pre>
     *   mode "nearest"  : 가장 가까운 픽셀 복사 (빠름)
     *   mode "bilinear" : 쌍선형 보간 (부드러움, align_corners=false)
     * </pre>
     */
    public static Tensor upsample(Tensor input, int scaleH, int scaleW, String mode) {
        if (input == null || input.getRank() < 2) return null;
        if (scaleH <= 0 || scaleW <= 0) return null;

        int[] inShape  = input.getShape();
        int   inRank   = inShape.length;
        int   H        = inShape[inRank - 2];
        int   W        = inShape[inRank - 1];
        int   Hout     = H * scaleH;
        int   Wout     = W * scaleW;

        int[] batchShape = Arrays.copyOf(inShape, inRank - 2);
        int   batchSize  = totalSize(batchShape);
        int[] outShape   = appendDims(batchShape, Hout, Wout);
        Tensor result    = new Tensor("upsample", outShape);

        int[] batchIdx = new int[batchShape.length];
        for (int b = 0; b < batchSize; b++) {
            if (batchShape.length > 0) Tensor.unflatten(b, batchShape, batchIdx);

            for (int oh = 0; oh < Hout; oh++) {
                for (int ow = 0; ow < Wout; ow++) {
                    double val;

                    if ("bilinear".equalsIgnoreCase(mode)) {
                        // align_corners=false: 픽셀 중심 정렬
                        double ih = (oh + 0.5) / scaleH - 0.5;
                        double iw = (ow + 0.5) / scaleW - 0.5;

                        int ih0 = Math.max(0, (int) Math.floor(ih));
                        int ih1 = Math.min(H - 1, ih0 + 1);
                        int iw0 = Math.max(0, (int) Math.floor(iw));
                        int iw1 = Math.min(W - 1, iw0 + 1);

                        double dh = Math.max(0, ih - ih0);
                        double dw = Math.max(0, iw - iw0);

                        double v00 = input.getFlat(input.flatIndex(concat(batchIdx, new int[]{ih0, iw0})));
                        double v01 = input.getFlat(input.flatIndex(concat(batchIdx, new int[]{ih0, iw1})));
                        double v10 = input.getFlat(input.flatIndex(concat(batchIdx, new int[]{ih1, iw0})));
                        double v11 = input.getFlat(input.flatIndex(concat(batchIdx, new int[]{ih1, iw1})));

                        val = v00 * (1 - dh) * (1 - dw)
                            + v01 * (1 - dh) * dw
                            + v10 * dh       * (1 - dw)
                            + v11 * dh       * dw;
                    } else {
                        // nearest (default)
                        int ih = oh / scaleH;
                        int iw = ow / scaleW;
                        val = input.getFlat(input.flatIndex(concat(batchIdx, new int[]{ih, iw})));
                    }

                    result.set(val, concat(batchIdx, new int[]{oh, ow}));
                }
            }
        }
        return result;
    }

    /**
     * Transposed Convolution (Deconvolution) — 학습 가능한 업샘플링.
     * <pre>
     *   input  : (..., H, W)
     *   kernel : (..., kH, kW)  마지막 두 차원을 커널로 사용
     *   output : (..., Hout, Wout)
     *   Hout = (H - 1) * stride - 2 * padding + kH
     * </pre>
     * scatter 방식: 각 입력 픽셀이 출력의 stride × stride 영역에 기여한다.
     */
    public static Tensor convTranspose2d(Tensor input, Tensor kernel, int stride, int padding) {
        if (input == null || kernel == null) return null;
        if (input.getRank() < 2 || kernel.getRank() < 2) return null;

        int[] inShape = input.getShape();
        int[] kShape  = kernel.getShape();
        int   inRank  = inShape.length;
        int   kRank   = kShape.length;

        int H  = inShape[inRank - 2], W  = inShape[inRank - 1];
        int kH = kShape[kRank - 2],   kW = kShape[kRank - 1];

        int Hout = (H - 1) * stride - 2 * padding + kH;
        int Wout = (W - 1) * stride - 2 * padding + kW;
        if (Hout <= 0 || Wout <= 0) return null;

        int[] batchShape = Arrays.copyOf(inShape, inRank - 2);
        int   batchSize  = totalSize(batchShape);
        int[] outShape   = appendDims(batchShape, Hout, Wout);
        Tensor result    = new Tensor("convtranspose2d", outShape);

        int[] batchIdx = new int[batchShape.length];
        for (int b = 0; b < batchSize; b++) {
            if (batchShape.length > 0) Tensor.unflatten(b, batchShape, batchIdx);

            for (int ih = 0; ih < H; ih++) {
                for (int iw = 0; iw < W; iw++) {
                    double inVal = input.getFlat(
                        input.flatIndex(concat(batchIdx, new int[]{ih, iw})));

                    for (int kh = 0; kh < kH; kh++) {
                        for (int kw = 0; kw < kW; kw++) {
                            int oh = ih * stride - padding + kh;
                            int ow = iw * stride - padding + kw;
                            if (oh < 0 || oh >= Hout || ow < 0 || ow >= Wout) continue;

                            // 커널도 마지막 두 차원 기준으로 브로드캐스트
                            int[] kIdx = new int[kRank];
                            kIdx[kRank - 2] = kh;
                            kIdx[kRank - 1] = kw;
                            double kVal = kernel.getFlat(kernel.flatIndex(kIdx));

                            int[] outIdx = concat(batchIdx, new int[]{oh, ow});
                            int   flat   = result.flatIndex(outIdx);
                            result.setFlat(flat, result.getFlat(flat) + inVal * kVal);
                        }
                    }
                }
            }
        }
        return result;
    }

    // ── SVD & Eigenvalue Decomposition ───────────────────────────────────────────

    /**
     * Thin SVD: A = U Σ V^T
     * <pre>
     *   input  : rank-2 matrix (m × n)
     *   output : [U(m×k), Sigma(k,), Vt(k×n)]   k = min(m, n)
     * </pre>
     * 특이값은 내림차순 정렬된다.
     */
    public static List<Tensor> svd(Tensor input) {
        if (input == null || input.getRank() != 2) return null;
        int m = input.getShape()[0];
        int n = input.getShape()[1];
        int k = Math.min(m, n);

        double[][] A = toMatrix(input, m, n);

        // A^T A (n×n, 대칭)
        double[][] AtA = new double[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                for (int r = 0; r < m; r++)
                    AtA[i][j] += A[r][i] * A[r][j];

        double[][] V = new double[n][n];
        jacobiEigen(AtA, n, V);

        double[] eigenvals = new double[n];
        for (int i = 0; i < n; i++) eigenvals[i] = AtA[i][i];
        sortEigenDesc(eigenvals, V, n);

        double[]   sigma = new double[k];
        double[][] U     = new double[m][k];
        double[][] Vt    = new double[k][n];

        for (int i = 0; i < k; i++) {
            sigma[i] = Math.sqrt(Math.max(0.0, eigenvals[i]));
            for (int j = 0; j < n; j++) Vt[i][j] = V[j][i];
            if (sigma[i] > 1e-10) {
                for (int r = 0; r < m; r++) {
                    double s = 0;
                    for (int j = 0; j < n; j++) s += A[r][j] * V[j][i];
                    U[r][i] = s / sigma[i];
                }
            }
        }

        Tensor tU     = new Tensor("U",     new int[]{m, k});
        Tensor tSigma = new Tensor("Sigma", new int[]{k});
        Tensor tVt    = new Tensor("Vt",    new int[]{k, n});

        for (int i = 0; i < m; i++)
            for (int j = 0; j < k; j++)
                tU.set(U[i][j], new int[]{i, j});
        for (int i = 0; i < k; i++)
            tSigma.set(sigma[i], new int[]{i});
        for (int i = 0; i < k; i++)
            for (int j = 0; j < n; j++)
                tVt.set(Vt[i][j], new int[]{i, j});

        return Arrays.asList(tU, tSigma, tVt);
    }

    /**
     * 고유값 분해 (대칭 행렬 전용): A = Q Λ Q^T
     * <pre>
     *   input  : rank-2 정방 행렬 (n × n)
     *   output : [Eigenvalues(n,), Eigenvectors(n×n)]
     * </pre>
     * 비대칭 입력은 (A + A^T)/2 로 대칭화한 뒤 처리한다.
     */
    public static List<Tensor> eigenDecompose(Tensor input) {
        if (input == null || input.getRank() != 2) return null;
        int m = input.getShape()[0];
        int n = input.getShape()[1];
        if (m != n) return null;

        double[][] A    = toMatrix(input, m, n);
        double[][] Asym = new double[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                Asym[i][j] = (A[i][j] + A[j][i]) / 2.0;

        double[][] V = new double[n][n];
        jacobiEigen(Asym, n, V);

        double[] eigenvals = new double[n];
        for (int i = 0; i < n; i++) eigenvals[i] = Asym[i][i];
        sortEigenDesc(eigenvals, V, n);

        Tensor tVals = new Tensor("Eigenvalues",  new int[]{n});
        Tensor tVecs = new Tensor("Eigenvectors", new int[]{n, n});

        for (int i = 0; i < n; i++)
            tVals.set(eigenvals[i], new int[]{i});
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                tVecs.set(V[i][j], new int[]{i, j});

        return Arrays.asList(tVals, tVecs);
    }

    // ── Jacobi 고유값 분해 헬퍼 (private) ────────────────────────────────────────

    /**
     * Jacobi 반복법으로 n×n 대칭 행렬 a를 고유값 분해한다.
     * 반환 후: a[i][i] = 고유값 i, v의 열 = 대응 고유벡터.
     */
    private static void jacobiEigen(double[][] a, int n, double[][] v) {
        for (int i = 0; i < n; i++) {
            Arrays.fill(v[i], 0.0);
            v[i][i] = 1.0;
        }
        int maxIter = 100 * n * n;
        for (int iter = 0; iter < maxIter; iter++) {
            int p = 0, q = 1;
            double maxOff = 0.0;
            for (int i = 0; i < n; i++)
                for (int j = i + 1; j < n; j++) {
                    double val = Math.abs(a[i][j]);
                    if (val > maxOff) { maxOff = val; p = i; q = j; }
                }
            if (maxOff < 1e-12) break;

            double diff = a[q][q] - a[p][p];
            double t;
            if (Math.abs(a[p][q]) < Math.abs(diff) * 1e-36) {
                t = a[p][q] / diff;
            } else {
                double beta = 0.5 * diff / a[p][q];
                t = 1.0 / (Math.abs(beta) + Math.sqrt(1.0 + beta * beta));
                if (beta < 0) t = -t;
            }
            double c   = 1.0 / Math.sqrt(1.0 + t * t);
            double s   = t * c;
            double tau = s / (1.0 + c);
            double apq = a[p][q];

            a[p][p] -= t * apq;
            a[q][q] += t * apq;
            a[p][q]  = 0.0;
            a[q][p]  = 0.0;

            for (int r = 0; r < n; r++) {
                if (r == p || r == q) continue;
                double arp = a[r][p], arq = a[r][q];
                a[r][p] = a[p][r] = arp - s * (arq + tau * arp);
                a[r][q] = a[q][r] = arq + s * (arp - tau * arq);
            }
            for (int r = 0; r < n; r++) {
                double vrp = v[r][p], vrq = v[r][q];
                v[r][p] = vrp - s * (vrq + tau * vrp);
                v[r][q] = vrq + s * (vrp - tau * vrq);
            }
        }
    }

    /** 고유값 내림차순 정렬 (대응 고유벡터 열 함께 재배열). */
    private static void sortEigenDesc(double[] vals, double[][] vecs, int n) {
        for (int i = 0; i < n - 1; i++) {
            int maxIdx = i;
            for (int j = i + 1; j < n; j++)
                if (vals[j] > vals[maxIdx]) maxIdx = j;
            if (maxIdx != i) {
                double tmp = vals[i]; vals[i] = vals[maxIdx]; vals[maxIdx] = tmp;
                for (int r = 0; r < n; r++) {
                    double tv = vecs[r][i]; vecs[r][i] = vecs[r][maxIdx]; vecs[r][maxIdx] = tv;
                }
            }
        }
    }

    /** Tensor rank-2를 double[][] 로 추출. */
    private static double[][] toMatrix(Tensor t, int m, int n) {
        double[][] A = new double[m][n];
        for (int i = 0; i < m; i++)
            for (int j = 0; j < n; j++)
                A[i][j] = t.get(new int[]{i, j});
        return A;
    }

    private static int[] computeBatchOut(int[] batchA, int[] batchB) {
        if (batchA.length == 0 && batchB.length == 0) return new int[0];
        int[] a = batchA.length == 0 ? new int[]{1} : batchA;
        int[] b = batchB.length == 0 ? new int[]{1} : batchB;
        int[] out = Tensor.broadcastShape(a, b);
        if (out == null) return null;
        return (batchA.length == 0 && batchB.length == 0) ? new int[0] : out;
    }

    private static int[] appendDims(int[] batch, int m, int n) {
        int[] out = Arrays.copyOf(batch, batch.length + 2);
        out[batch.length]     = m;
        out[batch.length + 1] = n;
        return out;
    }

    private static int totalSize(int[] shape) {
        if (shape.length == 0) return 1;
        int s = 1;
        for (int d : shape) s *= d;
        return s;
    }

    static int[] concat(int[] a, int[] b) {
        int[] r = new int[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
 // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 // 아래 내용을 TensorOperations.java의 마지막 } 바로 앞에 추가하세요.
 // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

     // ── Vector utilities ──────────────────────────────────────────────────────

     /**
      * 텐서가 벡터 형태인지 확인한다.
      * rank 1 ([n]) 또는 rank 2에서 한 차원이 1인 경우 ([n,1], [1,n]).
      */
     public static boolean isVectorLike(Tensor t) {
         if (t == null) return false;
         int[] shape = t.getShape();
         if (shape.length == 1) return true;
         if (shape.length == 2) return shape[0] == 1 || shape[1] == 1;
         return false;
     }

     /**
      * 벡터 형태 텐서에서 flat double[]을 추출한다.
      * 지원 shape: [n], [n,1] (열벡터), [1,n] (행벡터).
      * 벡터 형태가 아니면 null 반환.
      */
     public static double[] extractVectorFlat(Tensor t) {
         if (t == null) return null;
         int[] shape = t.getShape();
         if (shape.length == 1) {
             double[] d = new double[shape[0]];
             for (int i = 0; i < shape[0]; i++) d[i] = t.get(new int[]{i});
             return d;
         }
         if (shape.length == 2) {
             if (shape[1] == 1) {                        // [n, 1]
                 double[] d = new double[shape[0]];
                 for (int i = 0; i < shape[0]; i++) d[i] = t.get(new int[]{i, 0});
                 return d;
             }
             if (shape[0] == 1) {                        // [1, n]
                 double[] d = new double[shape[1]];
                 for (int i = 0; i < shape[1]; i++) d[i] = t.get(new int[]{0, i});
                 return d;
             }
         }
         return null;
     }

     /** 주어진 shape으로 Tensor를 생성하고 flat 데이터를 복사한다. */
     private static Tensor tensorFromFlat(String name, double[] flat, int[] shape) {
         Tensor t = new Tensor(name, shape);
         System.arraycopy(flat, 0, t.getRawData(), 0, Math.min(flat.length, t.dataSize()));
         return t;
     }

     // ── Matrix inverse ────────────────────────────────────────────────────────

     /**
      * Gauss-Jordan 소거법(부분 피벗팅)으로 정사각 행렬의 역행렬을 계산한다.
      * 특이 행렬이거나 정사각이 아니면 null 반환.
      */
     public static Tensor inverse(Tensor m) {
         if (m == null || m.getRank() != 2) return null;
         int[] shape = m.getShape();
         if (shape[0] != shape[1]) return null;
         int n = shape[0];

         // 확대 행렬 [M | I] 구성
         double[][] aug = new double[n][2 * n];
         for (int i = 0; i < n; i++) {
             for (int j = 0; j < n; j++) aug[i][j] = m.get(new int[]{i, j});
             aug[i][n + i] = 1.0;
         }

         // Gauss-Jordan (부분 피벗팅)
         for (int col = 0; col < n; col++) {
             int maxRow = col;
             for (int row = col + 1; row < n; row++)
                 if (Math.abs(aug[row][col]) > Math.abs(aug[maxRow][col])) maxRow = row;

             double[] tmp = aug[col]; aug[col] = aug[maxRow]; aug[maxRow] = tmp;

             double pivot = aug[col][col];
             if (Math.abs(pivot) < 1e-12) return null;   // 특이 행렬

             for (int j = 0; j < 2 * n; j++) aug[col][j] /= pivot;

             for (int row = 0; row < n; row++) {
                 if (row == col) continue;
                 double f = aug[row][col];
                 for (int j = 0; j < 2 * n; j++) aug[row][j] -= f * aug[col][j];
             }
         }

         Tensor result = new Tensor("inv", shape);
         for (int i = 0; i < n; i++)
             for (int j = 0; j < n; j++)
                 result.set(aug[i][n + j], new int[]{i, j});
         return result;
     }

     // ── Projection ────────────────────────────────────────────────────────────

     /**
      * 벡터 {@code v}의 직교 정사영을 계산한다.
      *
      * <ul>
      *   <li>{@code u}가 벡터 형태 → 직선 span{u}에 투영<br>
      *       {@code proj = (v·u / u·u) · u}</li>
      *   <li>{@code u}가 행렬 A → A의 열공간에 투영<br>
      *       {@code proj = A(AᵀA)⁻¹Aᵀv}</li>
      * </ul>
      *
      * @return {@code [proj, orth]} (orth = v − proj).
      *         입력이 유효하지 않으면 {@code [null, null]}.
      */
     public static List<Tensor> project(Tensor v, Tensor u) {
         if (v == null || u == null) return Arrays.asList(null, null);
         return isVectorLike(u) ? projectOntoVector(v, u) : projectOntoSubspace(v, u);
     }

     /** 벡터 u의 span에 투영 */
     private static List<Tensor> projectOntoVector(Tensor v, Tensor u) {
         double[] vFlat = extractVectorFlat(v);
         double[] uFlat = extractVectorFlat(u);
         if (vFlat == null || uFlat == null || vFlat.length != uFlat.length)
             return Arrays.asList(null, null);

         double vu = 0, uu = 0;
         for (int i = 0; i < vFlat.length; i++) {
             vu += vFlat[i] * uFlat[i];
             uu += uFlat[i] * uFlat[i];
         }
         if (Math.abs(uu) < 1e-12) return Arrays.asList(null, null);   // u = 영벡터

         double scalar = vu / uu;
         double[] projFlat = new double[vFlat.length];
         for (int i = 0; i < vFlat.length; i++) projFlat[i] = scalar * uFlat[i];

         Tensor proj = tensorFromFlat("proj", projFlat, v.getShape());
         Tensor orth = subtract(v, proj);
         if (orth != null) orth.setName("orth");
         return Arrays.asList(proj, orth);
     }

     /** 행렬 A의 열공간에 투영: proj_A(v) = A(AᵀA)⁻¹Aᵀv */
     private static List<Tensor> projectOntoSubspace(Tensor v, Tensor A) {
         if (A.getRank() != 2) return Arrays.asList(null, null);
         double[] vFlat = extractVectorFlat(v);
         int m = A.getShape()[0];
         if (vFlat == null || vFlat.length != m) return Arrays.asList(null, null);

         // v를 열벡터 [m, 1]로 변환
         Tensor vCol = new Tensor("v", m, 1);
         for (int i = 0; i < m; i++) vCol.set(vFlat[i], new int[]{i, 0});

         Tensor At      = transpose(A);
         Tensor AtA     = matmul(At, A);         // [k, k]
         Tensor AtA_inv = inverse(AtA);
         if (AtA_inv == null) return Arrays.asList(null, null);  // 열 선형 종속

         Tensor Atv     = matmul(At, vCol);      // [k, 1]
         Tensor coeff   = matmul(AtA_inv, Atv);  // [k, 1]
         Tensor projCol = matmul(A, coeff);       // [m, 1]

         double[] projFlat = new double[m];
         for (int i = 0; i < m; i++) projFlat[i] = projCol.get(new int[]{i, 0});

         Tensor proj = tensorFromFlat("proj", projFlat, v.getShape());
         Tensor orth = subtract(v, proj);
         if (orth != null) orth.setName("orth");
         return Arrays.asList(proj, orth);
     }
}

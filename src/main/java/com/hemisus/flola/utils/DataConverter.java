package com.hemisus.flola.utils;

import com.hemisus.flola.model.Tensor;

public class DataConverter {

    /**
     * Convert string to rank-n tensor and return it
     */
    public static Tensor stringToTensor(String input) {
        input = input.replaceAll("\\s+", "");
        if (input.isEmpty()) return null;

        // 1. 차원(Rank) 유추: 연속된 '['의 개수
        int rank = 0;
        while (rank < input.length() && input.charAt(rank) == '[') {
            rank++;
        }

        if (rank == 0) {
            return Tensor.scalar("Temp", Double.parseDouble(input));
        }

        // 2. Shape 유추: 각 괄호 깊이(Depth)별 요소 개수 카운팅
        int[] commasAtDepth = new int[rank];
        int currentDepth = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '[') currentDepth++;
            else if (c == ']') currentDepth--;
            else if (c == ',') {
                if (currentDepth > 0 && currentDepth <= rank) {
                    commasAtDepth[currentDepth - 1]++;
                }
            }
        }

        int[] shape = new int[rank];
        int prevItems = 1;
        for (int d = 0; d < rank; d++) {
            int items = commasAtDepth[d] + prevItems;
            if (items % prevItems != 0) {
                throw new IllegalArgumentException("Jagged tensors (비대칭 배열)은 지원하지 않습니다.");
            }
            shape[d] = items / prevItems;
            prevItems = items;
        }

        // 3. 1차원 데이터 추출 및 텐서 생성
        String cleanNums = input.replaceAll("[\\[\\]]", "");
        Tensor tensor = new Tensor("Temp", shape);
        
        if (!cleanNums.isEmpty()) {
            String[] numStrs = cleanNums.split(",");
            double[] data = tensor.getRawData();
            if (numStrs.length != data.length) {
                throw new IllegalArgumentException("입력된 데이터 요소 개수가 텐서의 Shape과 일치하지 않습니다.");
            }
            for (int i = 0; i < numStrs.length; i++) {
                data[i] = Double.parseDouble(numStrs[i]);
            }
        }
        return tensor;
    }

    /**
     * Convert tensor to string
     */
    public static String tensorToString(Tensor tensor) {
        if (tensor == null) return "";
        if (tensor.getRank() == 0) {
            return formatValue(tensor.getRawData()[0]);
        }
        StringBuilder sb = new StringBuilder();
        buildString(tensor, 0, new int[tensor.getRank()], sb, "");
        return sb.toString();
    }

    private static void buildString(Tensor tensor, int dim, int[] indices, StringBuilder sb, String indent) {
        int rank = tensor.getRank();
        if (dim == rank) {
            sb.append(formatValue(tensor.get(indices)));
            return;
        }

        sb.append("[");
        int size = tensor.getDim(dim);
        boolean isLastDim = (dim == rank - 1);

        for (int i = 0; i < size; i++) {
            indices[dim] = i;
            if (!isLastDim && i == 0) {
                sb.append("\n").append(indent).append(" ");
            }

            buildString(tensor, dim + 1, indices, sb, indent + " ");

            if (i < size - 1) {
                sb.append(",");
                if (isLastDim) {
                    sb.append(" ");
                } else {
                    sb.append("\n").append(indent).append(" ");
                }
            }
        }

        if (!isLastDim) {
            sb.append("\n").append(indent);
        }
        sb.append("]");
    }

    private static String formatValue(double val) {
        return (val == (long) val) ? String.valueOf((long) val) : String.valueOf(val);
    }
}

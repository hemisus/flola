package com.hemisus.flola.utils;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.util.HashMap;
import java.util.Map;

/**
 * 연산(operation) 아이콘 PNG를 클래스패스에서 로드·캐싱한다.
 *
 * <p>아이콘 파일은 {@code src/main/resources/com/hemisus/flola/img/} 아래에 두며,
 * 런타임 클래스패스 경로는 {@code /com/hemisus/flola/img/<파일명>.png} 가 된다.</p>
 *
 * <h3>연산 → 파일명 매핑</h3>
 * 연산 타입 문자열(예: "Matrix Multiply")이 파일명과 다를 수 있으므로,
 * {@link #ICON_NAMES} 에서 <b>각 연산별로 아이콘 파일명을 직접 지정</b>한다(확장자 제외).
 * 매핑에 없으면 연산 타입 문자열을 그대로 파일명으로 시도한다.
 *
 * <p>{@code Image}는 한 번 로드하면 캐시한다 — TreeCell.updateItem 처럼 자주 호출되는
 * 곳에서 매번 파일을 다시 읽지 않기 위함(없는 아이콘은 null로 캐시). {@code ImageView}는
 * Scene Graph에서 한 노드가 한 곳에만 있을 수 있으므로 호출마다 새로 만든다.</p>
 */
public final class OperationIcons {

    private OperationIcons() {}

    /** 아이콘 리소스 폴더 (클래스패스 기준; 앞의 / = 루트부터 절대경로). */
    private static final String BASE = "/com/hemisus/flola/img/";

    /**
     * 연산 타입 → 아이콘 파일명(확장자 제외).
     * <b>우변 값을 실제 PNG 파일명으로 수정하라.</b> (여기 없는 타입은 타입명 그대로 시도)
     */
    private static final Map<String, String> ICON_NAMES = new HashMap<>();
    static {
        // ── Basic Operation ──────────────────────────────
        ICON_NAMES.put("Add",                  "add");
        ICON_NAMES.put("Subtract",             "subtract");
        ICON_NAMES.put("Matrix Multiply",      "matmul");
        ICON_NAMES.put("Elementwise Multiply", "elementwisemul");
        ICON_NAMES.put("Divide",               "divide");
        ICON_NAMES.put("Negate",               "negate");
        ICON_NAMES.put("Transpose",            "transpose");
        ICON_NAMES.put("Clear",                "clear");
        ICON_NAMES.put("Sum",                  "sum");
        ICON_NAMES.put("Average",              "average");
        // ── Activation Function ──────────────────────────
        ICON_NAMES.put("ReLU",                 "relu");
        ICON_NAMES.put("Sigmoid",              "sigmoidtanh");
        ICON_NAMES.put("Tanh",                 "sigmoidtanh");
        ICON_NAMES.put("Softmax",              "softmax");
        // ── Advanced ─────────────────────────────────────
        ICON_NAMES.put("Projection",           "projection");
        ICON_NAMES.put("Concatenate",          "concatenate");
        ICON_NAMES.put("Split",                "split");
        ICON_NAMES.put("View",                 "view");
        ICON_NAMES.put("Conv2D",               "conv2d");
        ICON_NAMES.put("ConvTranspose2D",      "conv2d");
        ICON_NAMES.put("MaxPool2D",            "conv2d");
        ICON_NAMES.put("Upsample",             "upsample");
        ICON_NAMES.put("SVD",                  "svd");
        ICON_NAMES.put("Eigenvalues",          "eigenvalues");
    }

    /** operationType → Image 캐시 (없으면 null도 저장해 반복 조회 방지). */
    private static final Map<String, Image> CACHE = new HashMap<>();

    /**
     * operationType에 해당하는 아이콘 Image를 반환한다. 파일이 없거나 로드 실패면 null.
     * 결과(null 포함)는 캐시된다.
     */
    public static Image image(String operationType) {
        if (operationType == null || operationType.isBlank()) return null;
        if (CACHE.containsKey(operationType)) return CACHE.get(operationType);

        String fileName = ICON_NAMES.getOrDefault(operationType, operationType);
        Image img = null;
        var url = OperationIcons.class.getResource(BASE + fileName + ".png");
        if (url != null) {
            try {
                img = new Image(url.toExternalForm());   // 원본 해상도로 캐시 (크기는 ImageView에서 조절)
                if (img.isError()) img = null;
            } catch (Exception ignore) {
                img = null;
            }
        }
        CACHE.put(operationType, img);
        return img;
    }

    /**
     * 트리 셀·노드 graphic으로 쓸 새 ImageView (size×size, 비율 유지). 아이콘이 없으면 null.
     * setGraphic(...)에 그대로 넣으면 된다.
     */
    public static ImageView view(String operationType, double size) {
        Image img = image(operationType);
        if (img == null) return null;
        ImageView iv = new ImageView(img);
        iv.setFitWidth(size);
        iv.setFitHeight(size);
        iv.setPreserveRatio(true);
        iv.setSmooth(true);
        return iv;
    }
}
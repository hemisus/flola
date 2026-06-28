package com.hemisus.flola.utils;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import java.util.Arrays;

public class DialogHelper {

    public static void showWarning(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message, ButtonType.OK);
        alert.setTitle("Warning");
        alert.setHeaderText(null);
        alert.showAndWait();
    }
    
    public static boolean confirm(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.NO);
        alert.setTitle(title);
        alert.setHeaderText(null);
        return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }
    
    public static boolean confirmShapeChange(String nodeId, int[] oldShape, int[] newShape) {
        String message = """
            Tensor [%s] shape will change
              from %s
              to   %s
            
            Proceed?""".formatted(nodeId, Arrays.toString(oldShape), Arrays.toString(newShape));

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.YES, ButtonType.NO);
        alert.setTitle("Shape Change Warning");
        alert.setHeaderText(null);
        
        return alert.showAndWait().filter(b -> b == ButtonType.YES).isPresent();
    }
    
    
}
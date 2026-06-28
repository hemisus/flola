package com.hemisus.flola;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MainApp extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/hemisus/flola/fxml/Main.fxml"));
        Parent root = loader.load();

        Scene scene = new Scene(root, 1600, 900);
        scene.getStylesheets().add(getClass().getResource("/com/hemisus/flola/css/main.css").toExternalForm());
        stage.setTitle("FLOLA - Linear Algebra Editor");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
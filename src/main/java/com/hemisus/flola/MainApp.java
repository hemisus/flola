package com.hemisus.flola;

import com.hemisus.flola.controller.MainController;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.File;
import java.util.List;

public class MainApp extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/hemisus/flola/fxml/Main.fxml"));
        Parent root = loader.load();
        MainController controller = loader.getController();

        Scene scene = new Scene(root, 1600, 900);
        scene.getStylesheets().add(getClass().getResource("/com/hemisus/flola/css/main.css").toExternalForm());
        stage.setTitle("FLOLA - Linear Algebra Editor");

        // 창/작업표시줄 아이콘 (resources/.../img/flola.png 가 있으면 적용)
        var iconUrl = getClass().getResource("/com/hemisus/flola/img/flola.png");
        if (iconUrl != null) stage.getIcons().add(new Image(iconUrl.toExternalForm()));

        stage.setScene(scene);

        // 창 X 버튼으로 닫을 때도 미저장 변경 저장 확인 (취소 시 닫기 취소)
        stage.setOnCloseRequest(e -> {
            if (!controller.confirmClose()) e.consume();
        });

        stage.show();

        // 파일 연결/명령행으로 .flola 경로가 들어오면 바로 그 프로젝트를 연다.
        // 없으면 시작 선택 창(새 프로젝트 / 열기 / 최근 프로젝트)을 띄운다.
        if (!openFileFromArgs(controller)) {
            controller.showStartupChooser();
        }
    }

    /** 시작 인자에 존재하는 .flola 파일이 있으면 열고 true 반환. */
    private boolean openFileFromArgs(MainController controller) {
        List<String> args = getParameters().getRaw();
        for (String arg : args) {
            File f = new File(arg);
            if (f.exists() && f.getName().toLowerCase().endsWith(".flola")) {
                controller.openProjectFile(f);
                return true;
            }
        }
        return false;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
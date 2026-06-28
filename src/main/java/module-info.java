module com.hemisus.flola {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.graphics;
    requires com.google.gson;
    
    opens com.hemisus.flola             to javafx.graphics, javafx.fxml;
    opens com.hemisus.flola.controller  to javafx.fxml;
    opens com.hemisus.flola.ui to javafx.fxml;
    opens com.hemisus.flola.utils to com.google.gson;

    exports com.hemisus.flola;
}
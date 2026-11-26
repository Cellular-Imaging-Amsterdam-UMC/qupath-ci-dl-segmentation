package qupath.ext.cidl;

import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Ellipse;

import java.util.HashMap;
import java.util.Map;

final class MethodIcons {
    private static final Map<String, Image> ICONS = new HashMap<>();

    private MethodIcons() {}

    static Node view(Engine engine, double size) {
        return switch (engine) {
            case CELLPOSE, CELLPOSE_SAM -> image("cellpose.png", size);
            case STARDIST, STARDIST_QP -> image("stardist.jpg", size);
            case INSTANSEG -> image("instanseg.png", size);
            case SPOTIFLOW -> image("spotiflow.png", size);
            case CELLSAM -> cell(size);
        };
    }

    static Node microSam(double size) {
        return image("microsam.png", size);
    }

    private static Node image(String file, double size) {
        var image = ICONS.computeIfAbsent(file, key -> {
            var url = MethodIcons.class.getResource("method-icons/" + key);
            return new Image(java.util.Objects.requireNonNull(url).toExternalForm(), 96, 96, true, true);
        });
        var view = new ImageView(image);
        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setPreserveRatio(true);
        return view;
    }

    private static Node cell(double size) {
        var body = new Ellipse(size * .43, size * .38);
        body.setRotate(-21);
        body.setFill(Color.web("#206b78"));
        body.setStroke(Color.web("#78dce5"));
        var nucleus = new Circle(size * .145, Color.web("#e9a25e"));
        var badge = new StackPane(body, nucleus);
        badge.setPrefSize(size, size);
        badge.setMaxSize(size, size);
        return badge;
    }
}


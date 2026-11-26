package qupath.ext.cidl;

import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
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
            var original = new Image(java.util.Objects.requireNonNull(url).toExternalForm(), 96, 96, true, true);
            return "stardist.jpg".equals(key) ? withoutWhiteMatte(original) : original;
        });
        var view = new ImageView(image);
        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setPreserveRatio(true);
        return view;
    }

    static Image withoutWhiteMatte(Image source) {
        int width = (int) source.getWidth();
        int height = (int) source.getHeight();
        var icon = new WritableImage(width, height);
        var original = source.getPixelReader();
        var transparent = icon.getPixelWriter();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                var color = original.getColor(x, y);
                double contrast = 1 - Math.min(color.getRed(), Math.min(color.getGreen(), color.getBlue()));
                double alpha = Math.clamp((contrast - .025) / .13, 0, 1);
                if (alpha == 0) {
                    transparent.setColor(x, y, Color.TRANSPARENT);
                } else {
                    transparent.setColor(x, y, new Color(unmatte(color.getRed(), alpha),
                            unmatte(color.getGreen(), alpha), unmatte(color.getBlue(), alpha),
                            alpha * color.getOpacity()));
                }
            }
        }
        return icon;
    }

    private static double unmatte(double value, double alpha) {
        return Math.clamp((value - 1 + alpha) / alpha, 0, 1);
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


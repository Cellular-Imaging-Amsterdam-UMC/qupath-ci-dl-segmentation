package qupath.ext.cidl;

import javafx.application.Platform;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import qupath.lib.common.GeneralTools;

import java.util.List;

final class WindowIcon {
    private static List<Image> images;

    static ImageView view(int size) {
        var view = new ImageView(icons().get(1));
        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        return view;
    }

    static void apply(Stage stage) {
        stage.getIcons().setAll(icons());
        if (GeneralTools.isWindows()) {
            stage.addEventHandler(WindowEvent.WINDOW_SHOWN, event -> Platform.runLater(() -> {
                if (stage.isShowing())
                    WindowsCaptionIcon.apply(stage.getTitle(), icons().getFirst(), icons().get(1));
            }));
        }
    }

    private static List<Image> icons() {
        if (images == null)
            images = List.of(draw(16), draw(32), draw(48), draw(64));
        return images;
    }

    private static Image draw(int size) {
        var canvas = new Canvas(size, size);
        var g = canvas.getGraphicsContext2D();
        g.scale(size / 48.0, size / 48.0);
        g.setFill(new LinearGradient(0, 0, 48, 48, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#1b766b")), new Stop(1, Color.web("#103e3d"))));
        g.fillRoundRect(0, 0, 48, 48, 12, 12);

        g.beginPath();
        g.moveTo(12, 15);
        g.bezierCurveTo(17, 12, 24, 13, 27, 17);
        g.bezierCurveTo(31, 21, 29, 25, 31, 28);
        g.bezierCurveTo(34, 33, 29, 38, 24, 39);
        g.bezierCurveTo(18, 40, 11, 36, 9, 32);
        g.bezierCurveTo(6, 27, 7, 19, 12, 15);
        g.closePath();
        g.setFill(Color.web("#338678"));
        g.fill();
        g.setStroke(Color.web("#a2f5dc"));
        g.setLineWidth(3);
        g.stroke();

        g.setFill(Color.web("#d8fff2"));
        g.fillOval(15, 21, 9, 9);
        g.setFill(Color.web("#ffc47e"));
        g.fillOval(32, 8, 7, 7);
        g.fillOval(36, 19, 5, 5);
        var snapshot = new SnapshotParameters();
        snapshot.setFill(Color.TRANSPARENT);
        return canvas.snapshot(snapshot, null);
    }
}

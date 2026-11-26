package qupath.ext.cidl;

import javafx.beans.InvalidationListener;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.locationtech.jts.index.strtree.STRtree;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.overlays.PathOverlay;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.regions.ImageRegion;
import qupath.lib.roi.interfaces.ROI;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.function.Consumer;

final class ModelComparison {
    private record Result(List<ROI> rois, double seconds) {}
    private record Candidate(int ref, int prediction, double iou) {}
    record Scores(double f1, int missed, int extra, int split, int merged) {
        String summary() {
            return String.format(Locale.ROOT, "F1 %.2f · missed %d · extra %d · splits %d · merges %d",
                    f1, missed, extra, split, merged);
        }
    }

    private final QuPathGUI qupath;
    private final Supplier<Prediction.Settings> current;
    private final Supplier<String> modelName;
    private final Consumer<String> logger;
    private final Label aLabel = new Label("A · Choose a model on any tab");
    private final Label bLabel = new Label("B · Choose a model on any tab");
    private final Label summary = new Label();
    private final Button run = new Button("Compare");
    private final Button stop = new Button("Stop comparison");
    private final Button clear = new Button("Clear preview");
    private final ToggleButton show = new ToggleButton("Show overlay");
    private final ComboBox<String> display = new ComboBox<>();
    private final Slider divider = new Slider(0, 1, 0.5);
    private final Slider opacity = new Slider(0.15, 1, .85);
    private Prediction.Settings a;
    private Prediction.Settings b;
    private final ReadOnlyBooleanWrapper busy = new ReadOnlyBooleanWrapper();
    private Job active;
    private Preview preview;

    ModelComparison(QuPathGUI qupath, Supplier<Prediction.Settings> current, Supplier<String> modelName,
                    Consumer<String> logger) {
        this.qupath = qupath;
        this.current = current;
        this.modelName = modelName;
        this.logger = logger;
        qupath.imageDataProperty().addListener((obs, before, after) -> {
            if (active != null)
                active.cancel();
            detach();
        });
    }

    TitledPane pane() {
        var setA = new Button("Set A");
        var setB = new Button("Set B");
        setA.setOnAction(event -> capture(true));
        setB.setOnAction(event -> capture(false));
        run.setDisable(true);
        run.setOnAction(event -> compare());
        stop.setDisable(true);
        stop.setOnAction(event -> { if (active != null) active.cancel(); });
        show.setDisable(true);
        clear.setDisable(true);
        clear.setOnAction(event -> detach());
        show.setOnAction(event -> {
            if (preview != null)
                preview.setShowing(show.isSelected());
        });
        display.getItems().setAll("Split", "Overlay", "A only", "B only");
        display.setValue("Split");
        display.setAccessibleText("Comparison display mode");
        divider.setAccessibleText("A/B split position");
        divider.disableProperty().bind(display.valueProperty().isNotEqualTo("Split").or(show.selectedProperty().not()));
        opacity.setAccessibleText("Prediction overlay opacity");
        opacity.disableProperty().bind(show.selectedProperty().not());
        HBox.setHgrow(divider, Priority.ALWAYS);
        HBox.setHgrow(opacity, Priority.ALWAYS);
        aLabel.getStyleClass().add("comparison-model-a");
        bLabel.getStyleClass().add("comparison-model-b");
        aLabel.setWrapText(true);
        bLabel.setWrapText(true);
        summary.setWrapText(true);
        var hint = new Label("Use Set A and Set B to capture model settings. Select test regions in QuPath. "
                + "Optional: classify reference objects as Ground truth to score both models.");
        hint.setWrapText(true);
        hint.getStyleClass().add("note");
        var actions = new javafx.scene.layout.FlowPane(8, 6, run, stop, show, clear, display);
        var sliders = new HBox(8, new Label("A/B"), divider, new Label("Opacity"), opacity);
        sliders.setAlignment(Pos.CENTER_LEFT);
        var pane = new TitledPane("Compare models", new VBox(6, hint, new HBox(8, setA, aLabel),
                new HBox(8, setB, bLabel), actions, sliders, summary));
        pane.setExpanded(false);
        return pane;
    }

    ReadOnlyBooleanProperty busyProperty() {
        return busy.getReadOnlyProperty();
    }

    void hidden() {
        if (active != null)
            active.cancel();
        detach();
    }

    private void capture(boolean first) {
        if (active != null)
            return;
        try {
            var settings = current.get();
            if (settings.engine() == Engine.SPOTIFLOW)
                throw new IllegalArgumentException("Choose an instance segmentation model for comparison.");
            if (first) {
                a = settings;
                aLabel.setText("A · " + a.engine().title + " · " + modelName.get());
            } else {
                b = settings;
                bLabel.setText("B · " + b.engine().title + " · " + modelName.get());
            }
            detach();
            summary.setText("");
            run.setDisable(a == null || b == null);
        } catch (RuntimeException e) {
            summary.setText(e.getMessage());
        }
    }

    private static Result compute(Job job, ImageData<BufferedImage> data, List<Prediction.Region> regions,
                                  Prediction.Settings settings) throws Exception {
        long start = System.nanoTime();
        var objects = Prediction.compute(job, data, regions, settings);
        return new Result(objects.stream().flatMap(List::stream).map(PathObject::getROI).toList(),
                (System.nanoTime() - start) / 1e9);
    }

    private void compare() {
        var viewer = qupath.getViewer();
        var data = viewer.getImageData();
        if (data == null) {
            summary.setText("Open an image first.");
            return;
        }
        var regions = Prediction.selectedRegions(data);
        if (regions.isEmpty()) {
            summary.setText("Select one or more area annotations or TMA cores in the viewer.");
            return;
        }
        var references = data.getHierarchy().getAnnotationObjects().stream()
                .filter(object -> object.hasROI() && object.getROI().isArea() && !object.getROI().isEmpty()
                        && object.getPathClass() != null && "Ground truth".equalsIgnoreCase(object.getPathClass().toString()))
                .map(PathObject::getROI).filter(roi -> regions.stream().anyMatch(region ->
                        samePlane(region.roi(), roi) && region.roi().contains(roi.getCentroidX(), roi.getCentroidY())))
                .toList();
        // Score only regions with reference annotations so unlabeled regions do not count as false positives.
        var scoringRegions = regions.stream().map(Prediction.Region::roi).filter(region -> references.stream()
                .anyMatch(ref -> samePlane(region, ref) && region.contains(ref.getCentroidX(), ref.getCentroidY()))).toList();
        detach();
        var job = new Job(logger);
        active = job;
        busy.set(true);
        var first = a;
        var second = b;
        run.setDisable(true);
        stop.setDisable(false);
        summary.setText("Running model A…");
        Thread.ofVirtual().name("CI DL comparison").start(() -> {
            try {
                var left = compute(job, data, regions, first);
                job.log("Running model B…");
                var right = compute(job, data, regions, second);
                String text = String.format(Locale.ROOT, "A · %d objects · %.1f s     B · %d objects · %.1f s",
                        left.rois().size(), left.seconds(), right.rois().size(), right.seconds());
                if (!references.isEmpty())
                    text += "\nA · " + score(job, references, inside(left.rois(), scoringRegions)).summary()
                            + "\nB · " + score(job, references, inside(right.rois(), scoringRegions)).summary();
                var completed = text;
                job.log(completed);
                job.apply(() -> {
                    if (viewer.getImageData() == data && qupath.getViewer() == viewer) {
                        preview = new Preview(viewer, data, left.rois(), right.rois());
                        summary.setText(completed);
                        show.setDisable(false);
                        clear.setDisable(false);
                        show.setSelected(true);
                        preview.setShowing(true);
                    }
                });
            } catch (Exception | LinkageError e) {
                var message = job.isCancelled() ? "Comparison stopped."
                        : "Comparison failed · " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                job.log(message);
                javafx.application.Platform.runLater(() -> summary.setText(message));
            } finally {
                javafx.application.Platform.runLater(() -> {
                    active = null;
                    busy.set(false);
                    run.setDisable(a == null || b == null);
                    stop.setDisable(true);
                });
            }
        });
    }

    private static boolean samePlane(ROI first, ROI second) {
        return first.getZ() == second.getZ() && first.getT() == second.getT();
    }

    private static List<ROI> inside(List<ROI> predictions, List<ROI> regions) {
        return predictions.stream().filter(roi -> regions.stream().anyMatch(region -> samePlane(region, roi)
                && region.contains(roi.getCentroidX(), roi.getCentroidY()))).toList();
    }

    static Scores score(Job job, List<ROI> references, List<ROI> predictions) {
        var tree = new STRtree();
        var ref = references.stream().map(ROI::getGeometry).toList();
        for (int i = 0; i < ref.size(); i++)
            tree.insert(ref.get(i).getEnvelopeInternal(), i);
        var candidates = new ArrayList<Candidate>();
        var overlapsByRef = new int[ref.size()];
        var overlapsByPrediction = new int[predictions.size()];
        for (int i = 0; i < predictions.size(); i++) {
            job.check();
            var geometry = predictions.get(i).getGeometry();
            for (var item : tree.query(geometry.getEnvelopeInternal())) {
                int r = (int) item;
                if (!samePlane(references.get(r), predictions.get(i)))
                    continue;
                var truth = ref.get(r);
                double area = truth.intersection(geometry).getArea();
                double iou = area / (truth.getArea() + geometry.getArea() - area);
                if (iou >= 0.5)
                    candidates.add(new Candidate(r, i, iou));
                if (area >= 0.1 * Math.min(truth.getArea(), geometry.getArea())) {
                    overlapsByRef[r]++;
                    overlapsByPrediction[i]++;
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::iou).reversed());
        var usedRef = new boolean[references.size()];
        var usedPrediction = new boolean[predictions.size()];
        int matched = 0;
        for (var pair : candidates) {
            if (!usedRef[pair.ref()] && !usedPrediction[pair.prediction()]) {
                usedRef[pair.ref()] = true;
                usedPrediction[pair.prediction()] = true;
                matched++;
            }
        }
        return new Scores(2.0 * matched / (references.size() + predictions.size()), references.size() - matched,
                predictions.size() - matched, (int) java.util.Arrays.stream(overlapsByRef).filter(n -> n > 1).count(),
                (int) java.util.Arrays.stream(overlapsByPrediction).filter(n -> n > 1).count());
    }

    private void detach() {
        if (preview != null)
            preview.setShowing(false);
        preview = null;
        show.setSelected(false);
        show.setDisable(true);
        clear.setDisable(true);
    }

    private final class Preview implements PathOverlay {
        private final QuPathViewer viewer;
        private final ImageData<BufferedImage> image;
        private final List<ROI> left;
        private final List<ROI> right;
        private final StackPane handle = new StackPane();
        private final InvalidationListener layout = obs -> position();
        private final InvalidationListener repaint = obs -> viewerRepaint();

        Preview(QuPathViewer viewer, ImageData<BufferedImage> image, List<ROI> left, List<ROI> right) {
            this.viewer = viewer;
            this.image = image;
            this.left = left;
            this.right = right;
            var line = new Region();
            line.setMaxWidth(2);
            line.setStyle("-fx-background-color: white;");
            var grip = new Label("↔");
            grip.setStyle("-fx-background-color: #293031; -fx-text-fill: white; -fx-padding: 3 5;");
            handle.getChildren().setAll(line, grip);
            handle.setManaged(false);
            handle.setCursor(Cursor.H_RESIZE);
            handle.setId("ci-dl-comparison-divider");
            handle.setAccessibleText("Drag to compare model A on the left and model B on the right.");
            handle.setOnMousePressed(event -> event.consume());
            handle.setOnMouseDragged(event -> {
                var view = viewer.getView();
                if (event.isPrimaryButtonDown() && view.getWidth() > 0)
                    divider.setValue(Math.clamp(view.sceneToLocal(event.getSceneX(), event.getSceneY()).getX()
                            / view.getWidth(), 0, 1));
                event.consume();
            });
            handle.setOnMouseReleased(event -> event.consume());
            handle.setOnMouseClicked(event -> event.consume());
        }

        void setShowing(boolean enabled) {
            var view = viewer.getView();
            if (enabled && !viewer.getCustomOverlayLayers().contains(this)) {
                viewer.getCustomOverlayLayers().add(this);
                view.getChildren().add(handle);
                view.widthProperty().addListener(layout);
                view.heightProperty().addListener(layout);
                divider.valueProperty().addListener(layout);
                display.valueProperty().addListener(layout);
                opacity.valueProperty().addListener(repaint);
                position();
            } else if (!enabled && viewer.getCustomOverlayLayers().remove(this)) {
                view.getChildren().remove(handle);
                view.widthProperty().removeListener(layout);
                view.heightProperty().removeListener(layout);
                divider.valueProperty().removeListener(layout);
                display.valueProperty().removeListener(layout);
                opacity.valueProperty().removeListener(repaint);
                viewer.repaint();
            }
        }

        private void viewerRepaint() {
            viewer.repaint();
        }

        private double split() {
            return viewer.getView().getWidth() * divider.getValue();
        }

        private void position() {
            handle.setVisible("Split".equals(display.getValue()));
            handle.resizeRelocate(split() - 10, 0, 20, viewer.getView().getHeight());
            viewer.repaint();
        }

        @Override
        public void paintOverlay(Graphics2D graphics, ImageRegion region, double downsample,
                                 ImageData<BufferedImage> data, boolean complete) {
            if (data != image)
                return;
            var mode = display.getValue();
            if (!"B only".equals(mode))
                draw(graphics, region, downsample, left, new Color(18, 230, 191), "Split".equals(mode)
                        ? clip(0, split()) : null);
            if (!"A only".equals(mode))
                draw(graphics, region, downsample, right, new Color(255, 157, 46), "Split".equals(mode)
                        ? clip(split(), viewer.getView().getWidth()) : null);
        }

        private Shape clip(double x1, double x2) {
            var path = new Path2D.Double();
            double h = viewer.getView().getHeight();
            var p = viewer.componentPointToImagePoint(x1, 0, null, false);
            path.moveTo(p.getX(), p.getY());
            for (var point : new double[][]{{x2, 0},{x2, h},{x1, h}}) {
                p = viewer.componentPointToImagePoint(point[0], point[1], null, false);
                path.lineTo(p.getX(), p.getY());
            }
            path.closePath();
            return path;
        }

        private void draw(Graphics2D graphics, ImageRegion region, double downsample, List<ROI> rois, Color color, Shape clip) {
            var copy = (Graphics2D) graphics.create();
            try {
                if (clip != null)
                    copy.clip(clip);
                copy.setStroke(new BasicStroke((float) Math.max(0.5, downsample * 1.5)));
                var bounds = new Rectangle2D.Double(region.getX(), region.getY(), region.getWidth(), region.getHeight());
                int alpha = (int) (255 * opacity.getValue());
                for (var roi : rois) {
                    if (roi.getZ() != region.getZ() || roi.getT() != region.getT()
                            || !bounds.intersects(roi.getBoundsX(), roi.getBoundsY(), roi.getBoundsWidth(), roi.getBoundsHeight()))
                        continue;
                    var shape = roi.getShape();
                    copy.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha / 5));
                    copy.fill(shape);
                    copy.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
                    copy.draw(shape);
                }
            } finally {
                copy.dispose();
            }
        }
    }
}


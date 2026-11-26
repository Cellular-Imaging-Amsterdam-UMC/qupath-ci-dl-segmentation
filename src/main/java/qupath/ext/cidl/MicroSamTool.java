package qupath.ext.cidl;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.StringProperty;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import qupath.lib.analysis.images.ContourTracing;
import qupath.lib.analysis.images.SimpleImages;
import qupath.lib.geom.Point2;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.overlays.PathOverlay;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.ImageRegion;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MicroSamTool implements PathOverlay {
    private record Prompt(Point2 point, int label) {}
    private static final int PATCH_SIZE = 1024;
    private final QuPathGUI qupath;
    private final StringProperty python;
    private final BooleanProperty enabled = new SimpleBooleanProperty(false);
    private final List<Prompt> points = new ArrayList<>();
    private final EventHandler<MouseEvent> clickHandler = this::clicked;
    private final Label status = new Label("Enable prompts, then Alt-click a cell.");
    private final Button accept = new Button("Add annotation");
    private final Button clear = new Button("Clear prompts");
    private final TextField checkpoint = new TextField();
    private final ComboBox<Channels.Choice> channel = new ComboBox<>();
    private final CheckBox color = new CheckBox("Use RGB");
    private Stage stage;
    private QuPathViewer viewer;
    private ImageData<BufferedImage> image;
    private ImagePlane plane;
    private Job active;
    private ROI preview;

    MicroSamTool(QuPathGUI qupath, StringProperty python) {
        this.qupath = qupath;
        this.python = python;
        var base = System.getenv("CI_DL_MICROSAM_MODELS");
        if (base == null && System.getenv("QUPATH_BASE") != null)
            base = Path.of(System.getenv("QUPATH_BASE"), "models", "microsam").toString();
        if (base != null && Files.isRegularFile(Path.of(base, "vit_b_lm.pt")))
            checkpoint.setText(Path.of(base, "vit_b_lm.pt").toString());
        checkpoint.setPromptText("µSAM light microscopy ViT-B");
        checkpoint.setEditable(false);
        enabled.addListener((obs, before, on) -> {
            if (on)
                attach();
            else
                detach();
        });
        qupath.imageDataProperty().addListener((obs, before, after) -> {
            enabled.set(false);
            updateChannels();
        });
        qupath.getStage().addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDING, event -> enabled.set(false));
    }

    ToggleButton toggle() {
        var toggle = new ToggleButton("µSAM");
        toggle.setTooltip(new Tooltip("Turn interactive µSAM prompts on or off"));
        toggle.selectedProperty().bindBidirectional(enabled);
        toggle.setOnAction(event -> {
            if (enabled.get())
                show();
        });
        return toggle;
    }

    void show() {
        if (stage == null)
            buildWindow();
        updateChannels();
        stage.show();
        stage.toFront();
    }

    private void buildWindow() {
        stage = new Stage();
        stage.initOwner(qupath.getStage());
        stage.setTitle("CI Deep Learning Segmentation · µSAM");
        stage.setMinWidth(460);
        WindowIcon.apply(stage);
        var turnOn = new ToggleButton("Enable µSAM");
        turnOn.selectedProperty().bindBidirectional(enabled);
        enabled.addListener((obs, before, after) -> turnOn.setText(after ? "Disable µSAM" : "Enable µSAM"));
        turnOn.setText(enabled.get() ? "Disable µSAM" : "Enable µSAM");
        var browse = new Button("Browse…");
        browse.setOnAction(event -> {
            var chooser = new FileChooser();
            chooser.setTitle("Choose µSAM ViT-B checkpoint");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Checkpoint", "*.pt", "*.pth"));
            var file = chooser.showOpenDialog(stage);
            if (file != null)
                checkpoint.setText(file.getAbsolutePath());
        });
        var model = new HBox(8, checkpoint, browse);
        HBox.setHgrow(checkpoint, Priority.ALWAYS);
        accept.setDisable(true);
        accept.setOnAction(event -> {
            if (preview != null && image == qupath.getImageData()
                    && plane.getZ() == viewer.getZPosition() && plane.getT() == viewer.getTPosition()) {
                var object = PathObjects.createAnnotationObject(preview);
                image.getHierarchy().addObject(object);
                image.getHierarchy().getSelectionModel().setSelectedObject(object);
                reset();
                status.setText("Annotation added.");
            } else {
                status.setText("Return to the image plane where this preview was created.");
            }
        });
        clear.setOnAction(event -> reset());
        var hint = new Label("Alt-click adds a foreground point. Alt+Shift-click adds a background point. "
                + "Review the preview, then add the annotation.");
        hint.setWrapText(true);
        status.setWrapText(true);
        var executable = new TextField();
        executable.textProperty().bindBidirectional(python);
        executable.setPromptText("python on PATH");
        var browsePython = new Button("Browse…");
        browsePython.setOnAction(event -> {
            var chooser = new FileChooser();
            chooser.setTitle("Choose Python executable");
            var file = chooser.showOpenDialog(stage);
            if (file != null)
                executable.setText(file.getAbsolutePath());
        });
        var pythonRow = new HBox(8, new Label("Python executable"), executable, browsePython);
        HBox.setHgrow(executable, Priority.ALWAYS);
        var advanced = new TitledPane("Advanced settings", pythonRow);
        advanced.setExpanded(false);
        advanced.expandedProperty().addListener((obs, before, after) -> stage.sizeToScene());
        executable.textProperty().addListener((obs, before, after) -> reset());
        var root = new VBox(8, hint, model, new HBox(8, new Label("Image channel"), channel, color),
                new HBox(8, turnOn, clear, accept), status, advanced);
        root.setPadding(new Insets(12));
        root.getStyleClass().add("ci-dl");
        var scene = new Scene(root, 470, 195);
        scene.getStylesheets().add(Workspace.class.getResource("workspace.css").toExternalForm());
        stage.setScene(scene);
        stage.setOnHidden(event -> enabled.set(false));
        channel.disableProperty().bind(color.selectedProperty());
        checkpoint.textProperty().addListener((obs, before, after) -> reset());
        channel.valueProperty().addListener((obs, before, after) -> reset());
        color.selectedProperty().addListener((obs, before, after) -> reset());
    }

    private void updateChannels() {
        var data = qupath.getImageData();
        var choices = data == null ? List.<Channels.Choice>of() : Channels.choices(data.getServer());
        boolean changed = !channel.getItems().equals(choices);
        if (changed) {
            channel.getItems().setAll(choices);
            channel.setValue(Channels.initial(choices));
        }
        boolean rgb = data != null && data.getServer().isRGB() && choices.size() >= 3;
        color.setDisable(!rgb);
        if (!rgb)
            color.setSelected(false);
        else if (changed)
            color.setSelected(true);
    }

    private void attach() {
        viewer = qupath.getViewer();
        image = viewer.getImageData();
        if (image == null) {
            status.setText("Open an image to use µSAM.");
            javafx.application.Platform.runLater(() -> enabled.set(false));
            return;
        }
        viewer.getView().addEventFilter(MouseEvent.MOUSE_CLICKED, clickHandler);
        viewer.getCustomOverlayLayers().add(this);
        status.setText("Alt-click an object in the viewer.");
    }

    private void detach() {
        reset();
        if (viewer != null) {
            viewer.getView().removeEventFilter(MouseEvent.MOUSE_CLICKED, clickHandler);
            viewer.getCustomOverlayLayers().remove(this);
            viewer.repaint();
        }
        viewer = null;
        image = null;
        status.setText("µSAM is off.");
    }

    private void reset() {
        if (active != null) {
            active.cancel();
            active = null;
        }
        preview = null;
        plane = null;
        points.clear();
        accept.setDisable(true);
        if (viewer != null)
            viewer.repaint();
    }

    private void clicked(MouseEvent event) {
        if (event.getButton() != MouseButton.PRIMARY || !event.isAltDown() || !event.isStillSincePress()
                || viewer == null || image == null)
            return;
        event.consume();
        var view = viewer.getView();
        var local = view.sceneToLocal(event.getSceneX(), event.getSceneY());
        var position = viewer.componentPointToImagePoint(local.getX(), local.getY(), null, false);
        var server = image.getServer();
        if (position.getX() < 0 || position.getY() < 0 || position.getX() >= server.getWidth()
                || position.getY() >= server.getHeight())
            return;
        var selectedPlane = ImagePlane.getPlane(viewer.getZPosition(), viewer.getTPosition());
        if (!selectedPlane.equals(plane)) {
            reset();
            plane = selectedPlane;
        }
        if (points.isEmpty() && event.isShiftDown()) {
            status.setText("Add a foreground point first.");
            return;
        }
        var channels = color.isSelected() ? List.of(0, 1, 2)
                : channel.getValue() == null ? List.<Integer>of() : List.of(channel.getValue().index());
        if (channels.isEmpty()) {
            status.setText("Choose an image channel first.");
            return;
        }
        var seed = points.isEmpty() ? new Point2(position.getX(), position.getY()) : points.getFirst().point();
        double w = Math.min(PATCH_SIZE, server.getWidth());
        double h = Math.min(PATCH_SIZE, server.getHeight());
        double x = Math.clamp(Math.floor(seed.getX() - w / 2), 0, server.getWidth() - w);
        double y = Math.clamp(Math.floor(seed.getY() - h / 2), 0, server.getHeight() - h);
        var region = ROIs.createRectangleROI(x, y, w, h, plane);
        if (!region.contains(position.getX(), position.getY())) {
            status.setText("This point is outside the prompt region. Clear prompts to segment another object.");
            return;
        }
        points.add(new Prompt(new Point2(position.getX(), position.getY()), event.isShiftDown() ? 0 : 1));
        viewer.repaint();
        predict(region, channels);
    }

    private void predict(ROI region, List<Integer> channels) {
        if (active != null)
            active.cancel();
        var backendError = new java.util.concurrent.atomic.AtomicReference<String>();
        var job = new Job(line -> {
            if (line.contains("Error:"))
                backendError.set(line.strip());
            if (line.startsWith("µSAM"))
                javafx.application.Platform.runLater(() -> {
                    if (active != null)
                        status.setText(line);
                });
        });
        active = job;
        accept.setDisable(true);
        status.setText("Computing µSAM preview…");
        var requested = List.copyOf(points);
        var data = image;
        var executable = python.get().isBlank() ? "python" : python.get();
        var weights = checkpoint.getText();
        Thread.ofVirtual().name("CI DL µSAM").start(() -> {
            try {
                var result = compute(job, data, region, requested, channels, executable, weights);
                job.apply(() -> {
                    if (active == job && image == data) {
                        preview = result;
                        accept.setDisable(result == null);
                        status.setText(result == null ? "No mask returned. Add a foreground point." : "Preview ready.");
                        viewer.repaint();
                    }
                });
            } catch (Exception | LinkageError e) {
                javafx.application.Platform.runLater(() -> {
                    if (active == job && !job.isCancelled()) {
                        preview = null;
                        String detail = backendError.get();
                        status.setText(detail == null ? e.getMessage() : detail);
                        if (viewer != null)
                            viewer.repaint();
                    }
                });
            } finally {
                javafx.application.Platform.runLater(() -> {
                    if (active == job)
                        active = null;
                });
            }
        });
    }

    private static ROI compute(Job job, ImageData<BufferedImage> data, ROI roi, List<Prompt> prompts,
                               List<Integer> channels, String python, String weights) throws Exception {
        var work = Files.createTempDirectory("qupath-ci-microsam-");
        try {
            var request = PatchIO.request(data.getServer(), roi, 1);
            var patch = PatchIO.export(job, data.getServer(), request, channels, work.resolve("sample.image"));
            var destination = work.resolve("sample.prediction");
            var config = new LinkedHashMap<String, Object>();
            config.put("task", "prompt");
            config.put("engine", "microsam");
            config.put("model", weights);
            config.put("device", "auto");
            config.put("patches", List.of(patch.target(destination, "prediction")));
            config.put("prompts", prompts.stream().map(p -> Map.of("x", p.point().getX() - request.getX(),
                    "y", p.point().getY() - request.getY(), "label", p.label())).toList());
            job.python(python, work, config);
            var labels = PatchIO.readLabels(job, destination, patch.width(), patch.height());
            return ContourTracing.createROIs(SimpleImages.createFloatImage(labels, patch.width(), patch.height()),
                    request, 1, -1).get(1);
        } finally {
            Job.cleanup(work, job::log);
        }
    }

    @Override
    public void paintOverlay(Graphics2D graphics, ImageRegion region, double downsample,
                             ImageData<BufferedImage> data, boolean complete) {
        if (image != data || plane == null || region.getZ() != plane.getZ() || region.getT() != plane.getT())
            return;
        var copy = (Graphics2D) graphics.create();
        try {
            if (preview != null) {
                copy.setColor(new Color(70, 230, 190, 65));
                copy.fill(preview.getShape());
                copy.setColor(new Color(70, 230, 190));
                copy.setStroke(new BasicStroke((float) (downsample * 2)));
                copy.draw(preview.getShape());
            }
            double radius = 4 * downsample;
            for (var point : points) {
                copy.setColor(point.label() == 1 ? Color.GREEN : Color.RED);
                copy.fill(new Ellipse2D.Double(point.point().getX() - radius, point.point().getY() - radius,
                        radius * 2, radius * 2));
            }
        } finally {
            copy.dispose();
        }
    }
}


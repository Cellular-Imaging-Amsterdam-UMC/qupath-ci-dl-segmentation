package qupath.ext.cidl;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
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
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class MicroSamTool {
    private record Click(ImageData<BufferedImage> image, QuPathViewer viewer, Point2 point, ImagePlane plane,
                         List<Integer> channels) {}

    private static final int PATCH_SIZE = 1024;
    private static final int PATCH_STRIDE = PATCH_SIZE / 2;
    private static final String DEFAULT_MODEL = "Light microscopy (ViT-B)";

    private final QuPathGUI qupath;
    private final StringProperty python;
    private final BooleanProperty enabled = new SimpleBooleanProperty(false);
    private final StringProperty checkpoint = new SimpleStringProperty("");
    private final Path modelFolder;
    private final ArrayDeque<Click> pending = new ArrayDeque<>();
    private final EventHandler<MouseEvent> clickHandler = this::clicked;
    private final EventHandler<MouseEvent> intercept = event -> {
        if (focusClick(event))
            event.consume();
    };
    private final Label status = new Label("Enable µSAM to start.");
    private final ComboBox<Channels.Choice> channel = new ComboBox<>();
    private final CheckBox color = new CheckBox("Use RGB");
    private Stage stage;
    private QuPathViewer viewer;
    private Job active;
    private MicroSamSession session;

    MicroSamTool(QuPathGUI qupath, StringProperty python) {
        this.qupath = qupath;
        this.python = python;
        Channels.useSwatches(channel);
        var base = System.getenv("CI_DL_MICROSAM_MODELS");
        if ((base == null || base.isBlank()) && System.getenv("QUPATH_BASE") != null)
            base = Path.of(System.getenv("QUPATH_BASE"), "models", "microsam").toString();
        modelFolder = base == null || base.isBlank() ? null : Path.of(base);
        checkpoint.set(defaultCheckpoint());
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
        checkpoint.addListener((obs, before, after) -> reset());
        python.addListener((obs, before, after) -> reset());
        channel.valueProperty().addListener((obs, before, after) -> reset());
        color.selectedProperty().addListener((obs, before, after) -> reset());
    }

    ToggleButton toggle() {
        var toggle = new ToggleButton("µSAM", MethodIcons.microSam(15));
        toggle.setTooltip(new Tooltip("Turn quick annotation on or off"));
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
        var turnOn = new ToggleButton();
        turnOn.setId("ci-microsam-enabled");
        turnOn.selectedProperty().bindBidirectional(enabled);
        turnOn.textProperty().bind(Bindings.when(enabled).then("Disable µSAM").otherwise("Enable µSAM"));
        var hint = new Label("Alt-click an object to add its outline as an annotation.");
        hint.setWrapText(true);
        status.setId("ci-microsam-status");
        status.setMinWidth(0);
        status.setMaxWidth(Double.MAX_VALUE);
        status.setTextOverrun(OverrunStyle.ELLIPSIS);
        var statusDetails = new Tooltip();
        statusDetails.textProperty().bind(status.textProperty());
        status.setTooltip(statusDetails);
        HBox.setHgrow(status, Priority.ALWAYS);
        var actions = new HBox(10, turnOn, status);
        actions.setAlignment(Pos.CENTER_LEFT);

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
        pythonRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(executable, Priority.ALWAYS);
        var advanced = new TitledPane("Advanced settings", pythonRow);
        advanced.setId("ci-microsam-advanced");
        advanced.setAnimated(false);
        advanced.setExpanded(false);
        advanced.expandedProperty().addListener((obs, before, after) -> Platform.runLater(() -> {
            if (stage.isShowing()) {
                double width = stage.getWidth();
                stage.sizeToScene();
                stage.setWidth(width);
            }
        }));

        var channelRow = new HBox(8, new Label("Image channel"), channel, color);
        channelRow.setAlignment(Pos.CENTER_LEFT);
        var root = new VBox(8, hint, modelPicker(), channelRow, actions, advanced);
        root.setPadding(new Insets(12));
        root.setPrefWidth(500);
        root.setMinHeight(VBox.USE_PREF_SIZE);
        root.getStyleClass().add("ci-dl");
        var scene = new Scene(root);
        scene.getStylesheets().add(Workspace.class.getResource("workspace.css").toExternalForm());
        stage.setScene(scene);
        stage.sizeToScene();
        stage.setOnHidden(event -> enabled.set(false));
        channel.disableProperty().bind(color.selectedProperty());
    }

    private HBox modelPicker() {
        var display = ModelForm.modelDisplay(
                Bindings.createStringBinding(() -> checkpoint.get().isBlank() ? DEFAULT_MODEL
                        : ModelForm.modelDisplayName(checkpoint.get()), checkpoint),
                Bindings.createStringBinding(() -> checkpoint.get().isBlank() ? DEFAULT_MODEL : checkpoint.get(), checkpoint));
        display.setId("ci-microsam-model");
        var browse = new Button("Browse…");
        browse.setOnAction(event -> {
            var chooser = new FileChooser();
            chooser.setTitle("Choose µSAM ViT-B checkpoint");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Checkpoint", "*.pt", "*.pth"));
            var folder = checkpoint.get().isBlank() ? modelFolder : Path.of(checkpoint.get()).getParent();
            if (folder != null && Files.isDirectory(folder))
                chooser.setInitialDirectory(folder.toFile());
            var selected = chooser.showOpenDialog(stage);
            if (selected != null)
                checkpoint.set(selected.getAbsolutePath());
        });
        var defaultItem = new MenuItem(DEFAULT_MODEL);
        defaultItem.setOnAction(event -> checkpoint.set(defaultCheckpoint()));
        var presets = new MenuButton("Presets", null, defaultItem);
        presets.setOnShowing(event -> {
            presets.getItems().setAll(defaultItem);
            if (modelFolder == null || !Files.isDirectory(modelFolder))
                return;
            try (var files = Files.list(modelFolder)) {
                for (var file : files.filter(Files::isRegularFile).filter(MicroSamTool::isLocalPreset).sorted().toList()) {
                    var item = new MenuItem(file.getFileName().toString());
                    item.setOnAction(action -> checkpoint.set(file.toAbsolutePath().toString()));
                    presets.getItems().add(item);
                }
            } catch (IOException e) {
                status.setText("Could not list local µSAM models.");
            }
        });
        var row = new HBox(6, display, browse, presets);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(display, Priority.ALWAYS);
        return row;
    }

    private String defaultCheckpoint() {
        var local = modelFolder == null ? null : modelFolder.resolve("vit_b_lm.pt");
        return local != null && Files.isRegularFile(local) ? local.toAbsolutePath().toString() : "";
    }

    private static boolean isLocalPreset(Path file) {
        var name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.startsWith("vit_b_") && !name.contains("_decoder") && !name.equals("vit_b_lm.pt")
                && (name.endsWith(".pt") || name.endsWith(".pth"));
    }

    private void updateChannels() {
        var data = qupath.getImageData();
        var choices = data == null ? List.<Channels.Choice>of() : Channels.choices(data.getServer());
        boolean changed = !channel.getItems().equals(choices);
        if (changed) {
            var selected = Channels.match(choices, channel.getValue());
            channel.getItems().setAll(choices);
            channel.setValue(selected == null ? Channels.initial(choices) : selected);
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
        if (viewer == null || viewer.getImageData() == null) {
            Platform.runLater(() -> {
                enabled.set(false);
                status.setText("Open an image to use µSAM.");
            });
            return;
        }
        var view = viewer.getView();
        view.addEventFilter(MouseEvent.MOUSE_CLICKED, clickHandler);
        view.addEventFilter(MouseEvent.MOUSE_PRESSED, intercept);
        view.addEventFilter(MouseEvent.MOUSE_RELEASED, intercept);
        view.addEventFilter(MouseEvent.MOUSE_DRAGGED, intercept);
        status.setText("Alt-click an object in the viewer.");
    }

    private void detach() {
        reset();
        if (viewer != null) {
            var view = viewer.getView();
            view.removeEventFilter(MouseEvent.MOUSE_CLICKED, clickHandler);
            view.removeEventFilter(MouseEvent.MOUSE_PRESSED, intercept);
            view.removeEventFilter(MouseEvent.MOUSE_RELEASED, intercept);
            view.removeEventFilter(MouseEvent.MOUSE_DRAGGED, intercept);
        }
        viewer = null;
        status.setText("µSAM is off.");
    }

    private void reset() {
        pending.clear();
        if (active != null) {
            active.cancel();
            active = null;
        }
        closeSession();
        if (enabled.get())
            status.setText("Alt-click an object in the viewer.");
    }

    private void closeSession() {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    private static boolean focusClick(MouseEvent event) {
        return event.isAltDown() && !event.isShiftDown()
                && (event.getButton() == MouseButton.PRIMARY || event.isPrimaryButtonDown());
    }

    private void clicked(MouseEvent event) {
        if (!focusClick(event) || !event.isStillSincePress() || viewer == null)
            return;
        event.consume();
        if (event.getClickCount() > 1)
            return;
        var image = viewer.getImageData();
        if (image == null)
            return;
        var view = viewer.getView();
        var local = view.sceneToLocal(event.getSceneX(), event.getSceneY());
        var xy = viewer.componentPointToImagePoint(local.getX(), local.getY(), null, false);
        var server = image.getServer();
        if (xy.getX() < 0 || xy.getY() < 0 || xy.getX() >= server.getWidth() || xy.getY() >= server.getHeight())
            return;
        var channels = color.isSelected() ? List.of(0, 1, 2)
                : channel.getValue() == null ? List.<Integer>of() : List.of(channel.getValue().index());
        if (channels.isEmpty()) {
            status.setText("Choose an image channel first.");
            return;
        }
        var plane = ImagePlane.getPlane(viewer.getZPosition(), viewer.getTPosition());
        pending.addLast(new Click(image, viewer, new Point2(xy.getX(), xy.getY()), plane, channels));
        if (active == null)
            runNext();
        else
            status.setText("Annotating… " + pending.size() + " in queue");
    }

    private void runNext() {
        var click = pending.pollFirst();
        if (click == null)
            return;
        if (session == null) {
            var executable = python.get().strip();
            session = new MicroSamSession(executable.isBlank() ? "python" : executable);
        }
        var worker = session;
        var weights = checkpoint.get();
        var job = new Job(line -> {
            if (line.startsWith("µSAM"))
                Platform.runLater(() -> {
                    if (session == worker && active != null)
                        status.setText(line);
                });
        });
        active = job;
        status.setText("Annotating…");
        Thread.ofVirtual().name("CI DL µSAM annotation").start(() -> {
            Throwable failure = null;
            try {
                var roi = compute(job, worker, click, weights);
                job.apply(() -> {
                    if (active != job || click.viewer().getImageData() != click.image())
                        return;
                    if (roi == null) {
                        status.setText("No object found at this point.");
                        return;
                    }
                    var object = PathObjects.createAnnotationObject(roi);
                    var hierarchy = click.image().getHierarchy();
                    hierarchy.addObject(object);
                    if (click.plane().getZ() == click.viewer().getZPosition()
                            && click.plane().getT() == click.viewer().getTPosition())
                        hierarchy.getSelectionModel().setSelectedObject(object);
                    status.setText("Annotation added.");
                });
            } catch (Exception | LinkageError e) {
                failure = e;
            } finally {
                var error = failure;
                Platform.runLater(() -> {
                    if (active == job) {
                        active = null;
                        if (error != null && !job.isCancelled()) {
                            status.setText(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                            closeSession();
                        }
                        runNext();
                    }
                });
            }
        });
    }

    private static ROI compute(Job job, MicroSamSession worker, Click click, String weights) throws Exception {
        var server = click.image().getServer();
        int w = Math.min(PATCH_SIZE, server.getWidth());
        int h = Math.min(PATCH_SIZE, server.getHeight());
        int x = patchOrigin(click.point().getX(), server.getWidth(), w);
        int y = patchOrigin(click.point().getY(), server.getHeight(), h);
        var region = ROIs.createRectangleROI(x, y, w, h, click.plane());
        var work = Files.createTempDirectory("qupath-ci-microsam-click-");
        try {
            var request = PatchIO.request(server, region, 1);
            var patch = PatchIO.export(job, server, request, click.channels(), work.resolve("sample.image"));
            var destination = work.resolve("sample.prediction");
            var config = new LinkedHashMap<String, Object>();
            config.put("model", weights);
            config.put("device", "auto");
            config.put("patches", List.of(patch.target(destination, "prediction")));
            config.put("embedding_key", List.of(request.getX(), request.getY(), request.getWidth(), request.getHeight(),
                    click.plane().getZ(), click.plane().getT(), click.channels()));
            config.put("prompts", List.of(Map.of("x", click.point().getX() - request.getX(),
                    "y", click.point().getY() - request.getY(), "label", 1)));
            worker.predict(job, config);
            var labels = PatchIO.readLabels(job, destination, patch.width(), patch.height());
            return ContourTracing.createROIs(SimpleImages.createFloatImage(labels, patch.width(), patch.height()),
                    request, 1, -1).get(1);
        } finally {
            Job.cleanup(work, job::log);
        }
    }

    static int patchOrigin(double click, int extent, int size) {
        int center = (int) (click / PATCH_STRIDE) * PATCH_STRIDE;
        return Math.clamp(center - PATCH_STRIDE / 2, 0, extent - size);
    }
}


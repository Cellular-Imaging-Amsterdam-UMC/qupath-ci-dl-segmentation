package qupath.ext.cidl;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
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
    private record Request(ImageData<BufferedImage> image, QuPathViewer viewer, Point2 point, ImagePlane plane,
                           List<Integer> channels, boolean prepareOnly) {}
    private record Model(String type, String path, String name) {}

    private static final int PATCH_SIZE = 1024;
    private static final int PATCH_STRIDE = PATCH_SIZE / 2;
    private static final String BASE_MODEL = "vit_b_lm";
    private static final String TINY_MODEL = "vit_t_lm";

    private final QuPathGUI qupath;
    private final StringProperty python;
    private final BooleanProperty enabled = new SimpleBooleanProperty(false);
    private final ObjectProperty<Model> model = new SimpleObjectProperty<>();
    private final Path modelFolder;
    private final ArrayDeque<Request> pending = new ArrayDeque<>();
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
    private boolean warmScheduled;

    MicroSamTool(QuPathGUI qupath, StringProperty python) {
        this.qupath = qupath;
        this.python = python;
        Channels.useSwatches(channel);
        var base = System.getenv("CI_DL_MICROSAM_MODELS");
        if ((base == null || base.isBlank()) && System.getenv("QUPATH_BASE") != null)
            base = Path.of(System.getenv("QUPATH_BASE"), "models", "microsam").toString();
        modelFolder = base == null || base.isBlank() ? null : Path.of(base);
        model.set(preset(BASE_MODEL, "Light microscopy · ViT-B"));
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
        model.addListener((obs, before, after) -> restart());
        python.addListener((obs, before, after) -> restart());
        channel.valueProperty().addListener((obs, before, after) -> scheduleWarmup());
        color.selectedProperty().addListener((obs, before, after) -> scheduleWarmup());
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
                Bindings.createStringBinding(() -> model.get().path().isBlank() ? model.get().name()
                        : ModelForm.modelDisplayName(model.get().path()), model),
                Bindings.createStringBinding(() -> model.get().path().isBlank() ? model.get().name() : model.get().path(), model));
        display.setId("ci-microsam-model");
        var browse = new Button("Browse…");
        browse.setOnAction(event -> {
            var chooser = new FileChooser();
            chooser.setTitle("Choose µSAM checkpoint");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Checkpoint", "*.pt", "*.pth"));
            var folder = model.get().path().isBlank() ? modelFolder : Path.of(model.get().path()).getParent();
            if (folder != null && Files.isDirectory(folder))
                chooser.setInitialDirectory(folder.toFile());
            var selected = chooser.showOpenDialog(stage);
            if (selected != null)
                browseCheckpoint(selected.toPath());
        });
        var base = presetItem(BASE_MODEL, "Light microscopy · ViT-B");
        var tiny = presetItem(TINY_MODEL, "Light microscopy · ViT-Tiny");
        var presets = new MenuButton("Presets", null, base, tiny);
        presets.setOnShowing(event -> {
            presets.getItems().setAll(base, tiny);
            if (modelFolder == null || !Files.isDirectory(modelFolder))
                return;
            try (var files = Files.list(modelFolder)) {
                for (var file : files.filter(Files::isRegularFile).sorted().toList()) {
                    var filename = file.getFileName().toString();
                    var type = backbone(filename);
                    var name = filename.toLowerCase(Locale.ROOT);
                    if (type != null && (name.endsWith(".pt") || name.endsWith(".pth"))
                            && !name.contains("_decoder") && !name.equals(BASE_MODEL + ".pt") && !name.equals(TINY_MODEL + ".pt")) {
                        var item = new MenuItem(filename);
                        item.setOnAction(action -> model.set(new Model(type, file.toAbsolutePath().toString(), filename)));
                        presets.getItems().add(item);
                    }
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

    private MenuItem presetItem(String type, String name) {
        var item = new MenuItem(name);
        item.setOnAction(event -> model.set(preset(type, name)));
        return item;
    }

    private Model preset(String type, String name) {
        var local = modelFolder == null ? null : modelFolder.resolve(type + ".pt");
        return new Model(type, local != null && Files.isRegularFile(local) ? local.toAbsolutePath().toString() : "", name);
    }

    private void browseCheckpoint(Path checkpoint) {
        var filename = checkpoint.getFileName().toString();
        var type = backbone(filename);
        if (type == null) {
            var dialog = new ChoiceDialog<>("ViT-B", "ViT-B", "ViT-Tiny", "ViT-L", "ViT-H");
            dialog.initOwner(stage);
            dialog.setTitle("µSAM model architecture");
            dialog.setHeaderText(filename);
            dialog.setContentText("Image encoder");
            type = dialog.showAndWait().map(label -> switch (label) {
                case "ViT-Tiny" -> "vit_t";
                case "ViT-L" -> "vit_l";
                case "ViT-H" -> "vit_h";
                default -> "vit_b";
            }).orElse(null);
        }
        if (type != null)
            model.set(new Model(type, checkpoint.toAbsolutePath().toString(), filename));
    }

    private static String backbone(String filename) {
        var name = filename.toLowerCase(Locale.ROOT);
        for (var type : List.of("vit_t", "vit_b", "vit_l", "vit_h")) {
            if (name.startsWith(type + "_") || name.startsWith(type + ".") || name.startsWith("sam_" + type + "_"))
                return type;
        }
        return null;
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
        updateChannels();
        scheduleWarmup();
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

    private void restart() {
        reset();
        scheduleWarmup();
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
        var channels = selectedChannels();
        if (channels.isEmpty()) {
            status.setText("Choose an image channel first.");
            return;
        }
        pending.removeIf(Request::prepareOnly);
        pending.addLast(new Request(image, viewer, new Point2(xy.getX(), xy.getY()), activePlane(), channels, false));
        if (active == null)
            runNext();
        else
            status.setText("Annotating… " + pending.size() + " in queue");
    }

    private List<Integer> selectedChannels() {
        return color.isSelected() ? List.of(0, 1, 2)
                : channel.getValue() == null ? List.of() : List.of(channel.getValue().index());
    }

    private ImagePlane activePlane() {
        return ImagePlane.getPlane(viewer.getZPosition(), viewer.getTPosition());
    }

    private void scheduleWarmup() {
        if (!enabled.get() || warmScheduled || viewer == null)
            return;
        warmScheduled = true;
        Platform.runLater(() -> {
            warmScheduled = false;
            if (!enabled.get() || viewer == null)
                return;
            var image = viewer.getImageData();
            var channels = selectedChannels();
            if (image == null || channels.isEmpty())
                return;
            var view = viewer.getView();
            var center = viewer.componentPointToImagePoint(view.getWidth() / 2, view.getHeight() / 2, null, false);
            var server = image.getServer();
            double x = Math.clamp(center.getX(), 0, server.getWidth() - 1);
            double y = Math.clamp(center.getY(), 0, server.getHeight() - 1);
            pending.removeIf(Request::prepareOnly);
            pending.addLast(new Request(image, viewer, new Point2(x, y), activePlane(), channels, true));
            if (active == null)
                runNext();
        });
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
        var selection = model.get();
        var job = new Job(line -> {
            if (line.startsWith("µSAM"))
                Platform.runLater(() -> {
                    if (session == worker && active != null)
                        status.setText(line);
                });
        });
        active = job;
        status.setText(click.prepareOnly() ? "Preparing µSAM…" : "Annotating…");
        Thread.ofVirtual().name("CI DL µSAM annotation").start(() -> {
            Throwable failure = null;
            try {
                var roi = compute(job, worker, click, selection);
                job.apply(() -> {
                    if (active != job || click.viewer().getImageData() != click.image())
                        return;
                    if (click.prepareOnly()) {
                        status.setText("Ready · Alt-click an object.");
                        return;
                    }
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

    private static ROI compute(Job job, MicroSamSession worker, Request click, Model model) throws Exception {
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
            config.put("model", model.path());
            config.put("model_type", model.type());
            config.put("prepare_only", click.prepareOnly());
            config.put("device", "auto");
            config.put("patches", List.of(patch.target(destination, "prediction")));
            config.put("embedding_key", List.of(request.getX(), request.getY(), request.getWidth(), request.getHeight(),
                    click.plane().getZ(), click.plane().getT(), click.channels()));
            config.put("prompts", List.of(Map.of("x", click.point().getX() - request.getX(),
                    "y", click.point().getY() - request.getY(), "label", 1)));
            worker.predict(job, config);
            if (click.prepareOnly())
                return null;
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


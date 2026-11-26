package qupath.ext.cidl;

import javafx.application.Platform;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.shape.Polyline;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.controlsfx.glyphfont.FontAwesome;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.ProjectDialogs;
import qupath.lib.gui.tools.IconFactory;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ServerTools;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.roi.RectangleROI;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

final class Workspace {
    private static final double TRAINING_MIN_HEIGHT = 630;
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(Workspace.class);
    private final QuPathGUI qupath;
    private final boolean training;
    private final Stage stage = new Stage();
    private final TabPane tabs = new TabPane();
    private final Map<Engine, ModelForm> forms = new EnumMap<>(Engine.class);
    private final TextArea log = new TextArea();
    private final Label status = new Label("Ready");
    private final Label context = new Label();
    private final Button chooseImages = new Button("Choose images…");
    private final Button addClasses = new Button("Add region classes");
    private final Button addTraining = new Button("Add Training Rectangle");
    private final Button addValidation = new Button("Add Validation Rectangle");
    private final Button toggleRectangleLocks = new Button();
    private final Button run;
    private final Button cancel = new Button("Stop");
    private final LossChart loss;
    private final List<ProjectImageEntry<BufferedImage>> selected = new ArrayList<>();
    private final ConcurrentLinkedQueue<String> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean logScheduled = new AtomicBoolean();
    private final AtomicBoolean rectangleRefreshScheduled = new AtomicBoolean();
    private final PathObjectHierarchyListener rectangleListener = event -> {
        if (event.isChanging() || event.isObjectMeasurementEvent())
            return;
        if (Platform.isFxApplicationThread()) {
            refreshRectangleLockIndicator();
        } else if (rectangleRefreshScheduled.compareAndSet(false, true)) {
            Platform.runLater(() -> {
                rectangleRefreshScheduled.set(false);
                refreshRectangleLockIndicator();
            });
        }
    };
    private PathObjectHierarchy rectangleHierarchy;
    private FontAwesome.Glyph rectangleLockGlyph;
    private Job active;
    private ModelComparison comparison;
    private Node comparisonPane;
    private double expandedHeight;
    private boolean expandedMaximized;
    private double compactChrome;

    Workspace(QuPathGUI qupath, boolean training, Map<Engine, StringProperty> pythonPaths,
              ModelLibrary library, MicroSamTool microSam) {
        this.qupath = qupath;
        this.training = training;
        this.loss = training ? new LossChart() : null;
        var heading = training ? "Training" : "Prediction";
        stage.initOwner(qupath.getStage());
        stage.setTitle("CI Deep Learning Segmentation · " + heading);
        stage.setMinWidth(570);
        stage.setMinHeight(training ? TRAINING_MIN_HEIGHT : 510);
        WindowIcon.apply(stage);
        qupath.getStage().addEventHandler(WindowEvent.WINDOW_HIDING, event -> {
            if (active != null)
                active.cancel();
        });

        var title = new Label("Deep Learning " + heading);
        title.getStyleClass().add("workspace-title");
        context.getStyleClass().add("note");
        context.setWrapText(true);
        var headingText = new VBox(4, title, context);
        var header = new HBox(10, WindowIcon.view(32), headingText);
        HBox.setHgrow(headingText, Priority.ALWAYS);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("workspace-header");

        var engines = Engine.values();
        for (var engine : engines) {
            if (training && engine == Engine.CELLSAM)
                continue;
            var form = new ModelForm(engine, training, stage, pythonPaths.get(engine), library);
            var tab = new Tab(engine.title, form.content());
            tab.setGraphic(MethodIcons.view(engine, 15));
            tab.setUserData(engine);
            tabs.getTabs().add(tab);
            forms.put(engine, form);
        }
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setMinHeight(160);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        var content = new VBox(8);
        var top = new VBox(header);
        var expandedDetails = new ArrayList<Node>();
        if (training) {
            chooseImages.setOnAction(event -> chooseImages());
            addClasses.setOnAction(event -> addClasses());
            addTraining.setOnAction(event -> addRectangle("Training"));
            addValidation.setOnAction(event -> addRectangle("Validation"));
            toggleRectangleLocks.setId("toggle-rectangle-locks");
            toggleRectangleLocks.setMinWidth(32);
            toggleRectangleLocks.setPrefWidth(32);
            toggleRectangleLocks.setMaxWidth(32);
            toggleRectangleLocks.setTooltip(new Tooltip());
            toggleRectangleLocks.setOnAction(event -> toggleRectangleLocks());
            var actions = new FlowPane(8, 6, chooseImages, addClasses, addTraining, addValidation, toggleRectangleLocks, microSam.toggle());
            var instruction = new Label();
            var selectedTab = tabs.getSelectionModel().selectedItemProperty();
            instruction.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(() -> {
                String regions = "Lock rectangles before labeling. ";
                if (selectedTab.get() == null)
                    return regions;
                return switch ((Engine) selectedTab.get().getUserData()) {
                    case SPOTIFLOW -> regions + "Mark spots with points or area centroids.";
                    case STARDIST_QP -> regions + "Train from instance annotations and save an ONNX model for QuPath.";
                    default -> regions + "Draw one area annotation per instance.";
                };
            }, selectedTab));
            instruction.setWrapText(true);
            instruction.getStyleClass().add("note");
            var toolbar = card("Training images", actions, instruction);
            VBox.setMargin(toolbar, new Insets(8, 10, 0, 10));
            top.getChildren().add(toolbar);
            expandedDetails.addAll(List.of(toolbar.getChildren().getFirst(), instruction));
        }
        content.getChildren().add(tabs);
        if (!training) {
            comparison = new ModelComparison(qupath, () -> activeForm().prediction(),
                    () -> activeForm().selectedModelName(), this::enqueue);
            comparisonPane = comparison.pane();
            content.getChildren().add(comparisonPane);
            stage.addEventHandler(WindowEvent.WINDOW_HIDING, event -> comparison.hidden());
        }
        log.setEditable(false);
        log.setWrapText(false);
        double logHeight = training ? 79 : 106;
        log.setMinHeight(logHeight);
        log.setPrefHeight(logHeight);
        log.setMaxHeight(logHeight);
        log.getStyleClass().add("runtime-log");
        content.getChildren().add(card("Run log", log));
        if (loss != null)
            content.getChildren().add(loss.content());
        content.getStyleClass().add("workspace-body");

        run = new Button(training ? "Start training" : "Run prediction");
        run.getStyleClass().add("primary");
        run.setOnAction(event -> start());
        if (comparison != null)
            comparison.busyProperty().addListener((obs, before, busy) -> run.setDisable(busy || active != null));
        cancel.setDisable(true);
        cancel.setOnAction(event -> {
            if (active != null) {
                active.cancel();
                status.setText("Stopping…");
                cancel.setDisable(true);
            }
        });
        status.setMinWidth(0);
        status.setMaxWidth(Double.MAX_VALUE);
        var footer = new HBox(8, status, cancel, run);
        HBox.setHgrow(status, Priority.ALWAYS);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("workspace-footer");
        var root = new BorderPane(content, top, null, footer, null);
        root.getStyleClass().add("ci-dl");
        if (training)
            addCollapseButton(header, root, content, footer, expandedDetails);
        var scene = new Scene(root, 675, training ? 900 : 775);
        scene.getStylesheets().add(Workspace.class.getResource("workspace.css").toExternalForm());
        stage.setScene(scene);

        qupath.imageDataProperty().addListener((value, before, after) -> updateContext());
        if (training) {
            stage.showingProperty().addListener((value, before, after) -> observeRectangleHierarchy());
            qupath.projectProperty().addListener((value, before, after) -> {
                selected.clear();
                updateContext();
            });
        }
        updateContext();
    }

    void showSelection(ModelLibrary.Selection selection) {
        var engine = selection.model().entry().engine();
        show();
        tabs.getTabs().stream().filter(tab -> tab.getUserData() == engine).findFirst().ifPresent(tab -> {
            tabs.getSelectionModel().select(tab);
            forms.get(engine).applySelection(selection);
        });
    }

    void show() {
        updateContext();
        stage.show();
        stage.toFront();
    }

    private void addCollapseButton(HBox header, BorderPane root, VBox content, HBox footer,
                                   List<Node> expandedDetails) {
        var toggle = new ToggleButton();
        toggle.setId("training-layout-toggle");
        toggle.setMinWidth(34);
        toggle.setPrefWidth(34);
        toggle.setMaxWidth(34);
        var glyph = new Polyline(0, 5, 5, 0, 10, 5);
        glyph.getStyleClass().add("window-toggle-glyph");
        toggle.setGraphic(glyph);
        var tooltip = new Tooltip("Collapse training window");
        toggle.setAccessibleText(tooltip.getText());
        toggle.setTooltip(tooltip);
        header.getChildren().add(toggle);
        toggle.setOnAction(event -> {
            boolean collapsed = toggle.isSelected();
            tooltip.setText(collapsed ? "Expand training window" : "Collapse training window");
            toggle.setAccessibleText(tooltip.getText());
            glyph.setRotate(collapsed ? 180 : 0);
            context.setWrapText(!collapsed);
            for (var detail : expandedDetails) {
                detail.setVisible(!collapsed);
                detail.setManaged(!collapsed);
            }
            if (collapsed) {
                expandedHeight = stage.getHeight();
                expandedMaximized = stage.isMaximized();
                compactChrome = Math.max(0, stage.getHeight() - stage.getScene().getHeight());
                if (expandedMaximized) {
                    var x = stage.getX();
                    var y = stage.getY();
                    var width = stage.getWidth();
                    stage.setMaximized(false);
                    stage.setX(x);
                    stage.setY(y);
                    stage.setWidth(width);
                }
                root.setCenter(null);
                root.applyCss();
                sizeCompactWindow(root, footer);
            } else {
                stage.setMaxHeight(Double.MAX_VALUE);
                stage.setMinHeight(TRAINING_MIN_HEIGHT);
                root.setCenter(content);
                stage.setHeight(expandedHeight);
                if (expandedMaximized)
                    stage.setMaximized(true);
            }
        });
        root.widthProperty().addListener((observable, before, after) -> {
            if (toggle.isSelected())
                Platform.runLater(() -> {
                    if (toggle.isSelected())
                        sizeCompactWindow(root, footer);
                });
        });
    }

    private void sizeCompactWindow(BorderPane root, HBox footer) {
        var width = stage.getScene().getWidth();
        var height = Math.ceil(compactChrome + root.getTop().prefHeight(width) + footer.prefHeight(width) + 1);
        stage.setMaxHeight(Double.MAX_VALUE);
        stage.setMinHeight(height);
        stage.setMaxHeight(height);
        stage.setHeight(height);
    }

    static VBox card(String title, Node... nodes) {
        var heading = new Label(title);
        heading.getStyleClass().add("card-heading");
        var box = new VBox(5, heading);
        box.getChildren().addAll(nodes);
        box.getStyleClass().add("card");
        return box;
    }

    private void updateContext() {
        var data = qupath.getImageData();
        List<Channels.Choice> channels = List.of();
        if (training) {
            var project = qupath.getProject();
            chooseImages.setDisable(active != null || project == null);
            addClasses.setDisable(active != null || project == null);
            addTraining.setDisable(active != null || data == null);
            addValidation.setDisable(active != null || data == null);
            observeRectangleHierarchy();
            if (project == null) {
                context.setText("Open a QuPath project to select training images.");
            } else {
                selected.removeIf(entry -> !project.getImageList().contains(entry));
                if (selected.isEmpty()) {
                    context.setText("Choose the project images to use for training.");
                } else {
                    var entries = openImages();
                    var entry = data == null ? null : project.getEntry(data);
                    var reference = selected.contains(entry) ? entry : selected.getFirst();
                    var live = entries.get(reference);
                    var metadata = live != null ? live.getServer().getMetadata()
                            : reference.getServerBuilder().getMetadata().orElse(null);
                    if (metadata != null)
                        channels = Channels.choices(metadata);
                    context.setText(selected.size() + " image(s) selected · Channels from " + reference.getImageName()
                            + (channels.isEmpty() ? " · Open this image to list its channels." : ""));
                }
            }
        } else if (data == null) {
            context.setText("Open an image and select one or more area annotations.");
        } else {
            context.setText(ServerTools.getDisplayableImageName(data.getServer()) + " · Predictions use selected area annotations.");
            channels = Channels.choices(data.getServer());
        }
        for (var form : forms.values())
            form.updateChannels(channels);
    }

    private void chooseImages() {
        var project = qupath.getProject();
        if (project == null)
            return;
        var pane = ProjectDialogs.createImageChoicePane(qupath, project.getImageList(), selected,
                "Select the images that contain Training or Validation rectangles.");
        var dialog = new Dialog<ButtonType>();
        dialog.initOwner(stage);
        dialog.setTitle("Training images");
        dialog.setResizable(true);
        dialog.getDialogPane().setContent(pane);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.APPLY, ButtonType.CANCEL);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.APPLY && qupath.getProject() == project) {
            selected.clear();
            selected.addAll(pane.getTargetItems());
            updateContext();
        }
    }

    private void addClasses() {
        registerClasses(List.of(PathClass.fromString("Training"), PathClass.fromString("Validation")));
        status.setText("Training and Validation classes are available.");
    }

    private void registerClasses(List<PathClass> additions) {
        var classes = qupath.getAvailablePathClasses();
        for (var pathClass : additions) {
            if (!classes.contains(pathClass))
                classes.add(pathClass);
        }
        var project = qupath.getProject();
        if (project != null)
            project.setPathClasses(classes);
    }

    private void addRectangle(String classification) {
        var viewer = qupath.getViewer();
        var data = qupath.getImageData();
        if (active != null || viewer == null || data == null)
            return;
        int side = activeForm().rectangleSide();
        var server = data.getServer();
        var hierarchy = data.getHierarchy();
        try {
            var roi = Training.placeRectangle(server.getWidth(), server.getHeight(), viewer.getCenterPixelX(),
                    viewer.getCenterPixelY(), side, viewer.getImagePlane(),
                    Training.snapshot(hierarchy.getAnnotationObjects()));
            var pathClass = PathClass.fromString(classification);
            var annotation = PathObjects.createAnnotationObject(roi, pathClass);
            if (hierarchy.insertPathObject(annotation, true)) {
                registerClasses(List.of(pathClass));
                viewer.setSelectedObject(annotation);
                viewer.setCenterPixelLocation(roi.getCentroidX(), roi.getCentroidY());
                status.setText("Added a " + classification + " rectangle (" + side + " × " + side + " px).");
            }
        } catch (IllegalArgumentException e) {
            status.setText(e.getMessage());
            enqueue("Error · " + e.getMessage());
        }
    }

    private void observeRectangleHierarchy() {
        var data = qupath.getImageData();
        var next = stage.isShowing() && data != null ? data.getHierarchy() : null;
        if (rectangleHierarchy != next) {
            if (rectangleHierarchy != null)
                rectangleHierarchy.removeListener(rectangleListener);
            rectangleHierarchy = next;
            if (rectangleHierarchy != null)
                rectangleHierarchy.addListener(rectangleListener);
        }
        refreshRectangleLockIndicator();
    }

    private void refreshRectangleLockIndicator() {
        var rectangles = rectangleHierarchy == null ? List.<PathObject>of() : regionRectangles(rectangleHierarchy);
        boolean hasRectangles = !rectangles.isEmpty();
        boolean allLocked = hasRectangles && rectangles.stream().allMatch(PathObject::isLocked);
        var glyph = allLocked ? FontAwesome.Glyph.LOCK : FontAwesome.Glyph.UNLOCK;
        if (rectangleLockGlyph != glyph) {
            rectangleLockGlyph = glyph;
            var icon = IconFactory.createNode(glyph, 16);
            if (allLocked)
                icon.getStyleClass().add("rectangle-lock-active");
            toggleRectangleLocks.setGraphic(icon);
        }
        String message;
        if (!hasRectangles)
            message = "This image has no Training or Validation rectangles.";
        else if (allLocked)
            message = "All Training and Validation rectangles are locked. Click to unlock them.";
        else
            message = "At least one Training or Validation rectangle is unlocked. Click to lock them all.";
        toggleRectangleLocks.setAccessibleText(message);
        toggleRectangleLocks.getTooltip().setText(message);
        toggleRectangleLocks.setDisable(active != null || !hasRectangles);
    }

    private static List<PathObject> regionRectangles(PathObjectHierarchy hierarchy) {
        var trainingClass = PathClass.fromString("Training");
        var validationClass = PathClass.fromString("Validation");
        return hierarchy.getAnnotationObjects().stream()
                .filter(object -> object.getROI() instanceof RectangleROI)
                .filter(object -> trainingClass.equals(object.getPathClass()) || validationClass.equals(object.getPathClass()))
                .toList();
    }

    private void toggleRectangleLocks() {
        var hierarchy = rectangleHierarchy;
        if (active != null || hierarchy == null)
            return;
        var rectangles = regionRectangles(hierarchy);
        if (rectangles.isEmpty())
            return;
        boolean lock = !rectangles.stream().allMatch(PathObject::isLocked);
        rectangles.forEach(object -> object.setLocked(lock));
        hierarchy.fireObjectsChangedEvent(this, rectangles);
        var count = rectangles.size();
        status.setText((lock ? "Locked " : "Unlocked ") + count
                + (count == 1 ? " rectangle in this image." : " rectangles in this image."));
    }

    private ModelForm activeForm() {
        var engine = (Engine) tabs.getSelectionModel().getSelectedItem().getUserData();
        return forms.get(engine);
    }

    private Map<ProjectImageEntry<BufferedImage>, ImageData<BufferedImage>> openImages() {
        var result = new HashMap<ProjectImageEntry<BufferedImage>, ImageData<BufferedImage>>();
        var project = qupath.getProject();
        for (var viewer : qupath.getAllViewers()) {
            var data = viewer.getImageData();
            if (data != null) {
                var entry = project.getEntry(data);
                if (entry != null)
                    result.put(entry, data);
            }
        }
        return result;
    }

    private void start() {
        if (active != null)
            return;
        var form = activeForm();
        var engine = form.engine;
        try {
            if (training) {
                var project = qupath.getProject();
                if (project == null || selected.isEmpty())
                    throw new IllegalArgumentException("Open a project and choose the images to train on.");
                var open = openImages();
                var sources = new ArrayList<Training.Source>();
                for (var entry : selected) {
                    var data = open.get(entry);
                    var marks = data == null ? List.<Training.Mark>of()
                            : Training.snapshot(data.getHierarchy().getAnnotationObjects());
                    sources.add(new Training.Source(entry, data, marks));
                }
                var settings = form.training(project.getPath().toAbsolutePath().getParent());
                var projectPath = project.getPath();
                launch(engine, job -> {
                    Training.run(job, sources, settings);
                    try {
                        ModelLibrary.trained(projectPath, settings);
                        job.log("Model registered in project presets.");
                    } catch (java.io.IOException e) {
                        job.log("Model was saved. Could not update the Model Manager · " + message(e));
                    }
                });
            } else {
                var data = qupath.getImageData();
                if (data == null)
                    throw new IllegalArgumentException("Open an image first.");
                var regions = Prediction.selectedRegions(data);
                if (regions.isEmpty())
                    throw new IllegalArgumentException("Select at least one area annotation or TMA core in the viewer.");
                var settings = form.prediction();
                launch(engine, job -> Prediction.run(job, data, regions, settings));
            }
        } catch (RuntimeException e) {
            enqueue("Error · " + message(e));
            status.setText("Check inputs");
        }
    }

    @FunctionalInterface
    private interface Work {
        void run(Job job) throws Exception;
    }

    private void launch(Engine engine, Work work) {
        log.clear();
        if (loss != null)
            loss.clear();
        var job = new Job(this::enqueue);
        active = job;
        run.setDisable(true);
        if (comparisonPane != null)
            comparisonPane.setDisable(true);
        tabs.setDisable(true);
        chooseImages.setDisable(true);
        addClasses.setDisable(true);
        addTraining.setDisable(true);
        addValidation.setDisable(true);
        toggleRectangleLocks.setDisable(true);
        cancel.setDisable(false);
        status.setText(training ? "Training…" : "Predicting…");
        enqueue(engine.title + (training ? " training started." : " prediction started."));
        Thread.ofVirtual().name("CI DL " + engine.id).start(() -> {
            var outcome = "Finished";
            try {
                work.run(job);
            } catch (CancellationException e) {
                outcome = "Stopped";
                enqueue("Stopped.");
            } catch (Exception | LinkageError e) {
                outcome = job.isCancelled() ? "Stopped" : "Failed";
                enqueue(outcome + " · " + message(e));
                if (!job.isCancelled())
                    LOGGER.warn("CI Deep Learning Segmentation job failed", e);
            }
            var finalOutcome = outcome;
            Platform.runLater(() -> {
                flushLog();
                if (loss != null)
                    loss.finish();
                active = null;
                run.setDisable(false);
                if (comparisonPane != null)
                    comparisonPane.setDisable(false);
                tabs.setDisable(false);
                cancel.setDisable(true);
                status.setText(finalOutcome);
                updateContext();
            });
        });
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private void enqueue(String line) {
        pending.add(line);
        scheduleLog();
    }

    private void scheduleLog() {
        if (logScheduled.compareAndSet(false, true))
            Platform.runLater(this::flushLog);
    }

    private void flushLog() {
        var text = new StringBuilder();
        for (String line; (line = pending.poll()) != null; ) {
            if (loss == null || !loss.accept(line))
                text.append(line).append('\n');
        }
        if (!text.isEmpty()) {
            log.appendText(text.toString());
            if (log.getLength() > 75_000)
                log.deleteText(0, log.getLength() - 65_000);
            log.positionCaret(log.getLength());
        }
        logScheduled.set(false);
        if (!pending.isEmpty())
            scheduleLog();
    }
}

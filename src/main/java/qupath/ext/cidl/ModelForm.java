package qupath.ext.cidl;

import javafx.beans.binding.Bindings;
import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.converter.IntegerStringConverter;
import qupath.lib.gui.prefs.PathPrefs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ModelForm {
    private static final int DEFAULT_RECTANGLE_SIZE = 256;
    private static final List<Integer> RECTANGLE_SIZES = List.of(128, 256, 512, 1024, 2048);

    final Engine engine;
    private final boolean training;
    private final Window window;
    private final TextField python = new TextField();
    private final ComboBox<String> device = new ComboBox<>();
    private final TextField model = new TextField();
    private final ComboBox<Channels.Choice> primary = new ComboBox<>();
    private final ComboBox<Channels.Choice> secondary = new ComboBox<>();
    private final TextField downsample = new TextField("1");
    private final ComboBox<Integer> rectangleSize = new ComboBox<>();
    private final TextField diameter = new TextField("0");
    private final TextField threshold = new TextField();
    private final TextField flow = new TextField("0.4");
    private final TextField distance = new TextField("1");
    private final TextField radius = new TextField("2");
    private final TextField expansion = new TextField("0");
    private final TextField mean = new TextField("0");
    private final TextField gaussian = new TextField("0");
    private final CheckBox annotations = new CheckBox("Create annotations");
    private final CheckBox excludeBorder = new CheckBox("Exclude image-edge objects");
    private final CheckBox replaceSpots = new CheckBox("Replace previous Spotiflow detections");
    private final CheckBox measurements = new CheckBox("Add shape and intensity measurements");
    private final TextField name = new TextField();
    private final TextField epochs = new TextField("100");
    private final TextField rate = new TextField();
    private final TextField batch = new TextField();
    private final TextField masks = new TextField("1");
    private final TextField crop = new TextField();
    private final TextField sigma = new TextField("1.5");

    ModelForm(Engine engine, boolean training, Window window, StringProperty pythonPath) {
        this.engine = engine;
        this.training = training;
        this.window = window;
        if (engine != Engine.STARDIST)
            python.textProperty().bindBidirectional(pythonPath);
        model.setText(training ? "" : engine.defaultModel());
        model.setPromptText(training ? "Train from scratch" : switch (engine) {
            case CELLPOSE -> "cyto3, nuclei or model file";
            case SPOTIFLOW -> "general or model folder";
            case STARDIST -> "Choose a StarDist .pb or .onnx file";
        });
        var modelPath = new Tooltip();
        modelPath.textProperty().bind(model.textProperty());
        model.setTooltip(modelPath);
        expansion.disableProperty().bind(annotations.selectedProperty());
        device.getItems().setAll("Auto", "CPU", "CUDA");
        device.getSelectionModel().selectFirst();
        secondary.getItems().add(Channels.NONE);
        secondary.setValue(Channels.NONE);
        threshold.setText(engine == Engine.STARDIST ? "0.5" : engine == Engine.CELLPOSE ? "0" : "");
        threshold.setPromptText("Model default");
        excludeBorder.setSelected(engine != Engine.SPOTIFLOW);
        replaceSpots.setSelected(true);
        rate.setText(engine == Engine.CELLPOSE ? "0.005" : "0.0003");
        batch.setText(engine == Engine.CELLPOSE ? "8" : "4");
        crop.setText(engine == Engine.CELLPOSE ? "224" : "256");
        name.setText(engine.id + "_custom");
        if (training) {
            var preference = PathPrefs.createPersistentPreference("ci.dl." + engine.id + ".rectangle.size",
                    DEFAULT_RECTANGLE_SIZE);
            rectangleSize.getItems().setAll(RECTANGLE_SIZES);
            rectangleSize.setValue(RECTANGLE_SIZES.contains(preference.get()) ? preference.get() : DEFAULT_RECTANGLE_SIZE);
            rectangleSize.valueProperty().addListener((observable, before, after) -> {
                if (after != null)
                    preference.set(after);
            });
            rectangleSize.setConverter(new IntegerStringConverter() {
                @Override
                public String toString(Integer size) {
                    return size == null ? "" : size + " × " + size;
                }
            });
            rectangleSize.setPrefWidth(126);
            rectangleSize.setTooltip(new Tooltip("Size of new rectangle annotations in original image pixels."));
        }
    }

    Node content() {
        var input = grid();
        add(input, training ? "Base model" : "Model", modelPicker());
        if (training)
            add(input, "New model name", name);
        add(input, "Image channel", primary);
        if (engine == Engine.CELLPOSE)
            add(input, "Secondary channel", secondary);
        add(input, "Downsample", downsample);
        if (training) {
            var modelSize = new Label();
            modelSize.getStyleClass().add("note");
            modelSize.textProperty().bind(Bindings.createStringBinding(this::modelRectangleSize,
                    rectangleSize.valueProperty(), downsample.textProperty()));
            var sizing = new HBox(8, rectangleSize, modelSize);
            sizing.setAlignment(Pos.CENTER_LEFT);
            add(input, "New rectangle size (image pixels)", sizing);
        }

        var parameters = grid();
        if (training) {
            add(parameters, "Epochs", epochs);
            add(parameters, "Learning rate", rate);
            add(parameters, "Batch size", batch);
            if (engine == Engine.CELLPOSE) {
                add(parameters, "Mean diameter (from scratch, 0 = default)", diameter);
                add(parameters, "Minimum instances per training patch", masks);
            }
            if (engine == Engine.SPOTIFLOW)
                add(parameters, "Spot sigma (pixels, from scratch)", sigma);
        } else {
            if (engine == Engine.CELLPOSE) {
                add(parameters, "Diameter (model pixels, 0 = auto)", diameter);
                add(parameters, "Cell probability threshold", threshold);
                add(parameters, "Flow threshold", flow);
            } else {
                add(parameters, "Probability threshold", threshold);
            }
            if (engine == Engine.SPOTIFLOW) {
                add(parameters, "Minimum peak distance (model pixels)", distance);
                add(parameters, "Spot marker radius (image pixels)", radius);
            } else {
                add(parameters, "Cell expansion (image pixels)", expansion);
                add(parameters, null, annotations);
                add(parameters, null, measurements);
            }
            if (engine == Engine.SPOTIFLOW)
                excludeBorder.setText("Exclude peaks near crop edges");
            add(parameters, null, excludeBorder);
            if (engine == Engine.SPOTIFLOW)
                add(parameters, null, replaceSpots);
        }

        var advanced = grid();
        if (training)
            add(advanced, "Training crop size (model pixels)", crop);
        if (engine != Engine.SPOTIFLOW) {
            add(advanced, "Mean filter radius (model pixels)", mean);
            add(advanced, "Gaussian blur sigma (model pixels)", gaussian);
        }
        if (engine != Engine.STARDIST) {
            add(advanced, "Python executable", pythonPicker());
            add(advanced, "Compute device", device);
        }
        var cards = new VBox(8, Workspace.card("Model & image input", input),
                Workspace.card(training ? "Training settings" : "Prediction settings", parameters));
        var more = new TitledPane("Advanced settings", advanced);
        more.setExpanded(false);
        cards.getChildren().add(more);
        cards.getStyleClass().add("workspace-body");
        var scroll = new ScrollPane(cards);
        scroll.getStyleClass().add("workspace-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scroll;
    }

    int rectangleSide() {
        return rectangleSize.getValue();
    }

    private String modelRectangleSize() {
        try {
            var resolution = decimal(downsample, "Downsample", 1, 128);
            var side = Math.round(rectangleSide() / resolution);
            return "≈ " + side + " × " + side + " model pixels";
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private HBox pythonPicker() {
        python.setPromptText("python on PATH");
        var browse = new Button("Browse…");
        browse.setOnAction(event -> pickFile(python, "Choose Python executable", null));
        var row = new HBox(6, python, browse);
        HBox.setHgrow(python, Priority.ALWAYS);
        return row;
    }

    private HBox modelPicker() {
        var browse = new Button(engine == Engine.SPOTIFLOW ? "Folder…" : "Browse…");
        browse.setOnAction(event -> {
            if (engine == Engine.SPOTIFLOW) {
                var chooser = new DirectoryChooser();
                chooser.setTitle("Choose Spotiflow model folder");
                var initial = initialDirectory(model, engine.modelEnvironment);
                if (initial != null)
                    chooser.setInitialDirectory(initial.toFile());
                var selected = chooser.showDialog(window);
                if (selected != null)
                    model.setText(selected.getAbsolutePath());
            } else {
                pickFile(model, "Choose " + engine.title + " model",
                        engine.modelEnvironment);
            }
        });
        var row = new HBox(6, model, browse);
        HBox.setHgrow(model, Priority.ALWAYS);
        if (training) {
            var clear = new Button("Clear");
            clear.setOnAction(event -> model.clear());
            row.getChildren().add(clear);
        }
        return row;
    }

    private void pickFile(TextField field, String title, String modelEnvironment) {
        var chooser = new FileChooser();
        chooser.setTitle(title);
        var initial = initialDirectory(field, modelEnvironment);
        if (initial != null)
            chooser.setInitialDirectory(initial.toFile());
        if (field == model && engine == Engine.STARDIST)
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("StarDist model", "*.pb", "*.onnx"));
        var selected = chooser.showOpenDialog(window);
        if (selected != null)
            field.setText(selected.getAbsolutePath());
    }

    private static Path initialDirectory(TextField field, String env) {
        var text = field.getText().strip();
        try {
            if (!text.isEmpty()) {
                var path = Path.of(text);
                if (Files.isDirectory(path) && path.isAbsolute())
                    return path;
                if (path.isAbsolute() && Files.isDirectory(path.getParent()))
                    return path.getParent();
            }
            var dir = env == null ? null : System.getenv(env);
            if (dir != null && Files.isDirectory(Path.of(dir)))
                return Path.of(dir).toAbsolutePath();
        } catch (IllegalArgumentException ignored) {
            // Let the chooser open in its default location.
        }
        return null;
    }

    static StringProperty pythonPreference(Engine engine) {
        if (engine == Engine.STARDIST)
            return null;
        String value = System.getenv("CI_DL_" + engine.id.toUpperCase(Locale.ROOT) + "_PYTHON");
        if (value == null) {
            String base = System.getenv("QUPATH_BASE");
            if (base != null) {
                var path = Path.of(base, "PythonEnvs", "qupathenv", "python.exe");
                if (Files.isRegularFile(path))
                    value = path.toString();
            }
        }
        return PathPrefs.createPersistentPreference("ci.dl." + engine.id + ".python", value == null ? "" : value);
    }

    void updateChannels(List<Channels.Choice> choices) {
        var oldPrimary = primary.getValue();
        var oldSecondary = secondary.getValue();
        primary.getItems().setAll(choices);
        primary.setValue(oldPrimary != null && choices.contains(oldPrimary) ? oldPrimary : Channels.initial(choices));
        var second = new ArrayList<Channels.Choice>();
        second.add(Channels.NONE);
        second.addAll(choices);
        secondary.getItems().setAll(second);
        secondary.setValue(oldSecondary != null && second.contains(oldSecondary) ? oldSecondary : Channels.NONE);
    }

    private static GridPane grid() {
        var grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(7);
        var label = new ColumnConstraints();
        label.setMinWidth(165);
        label.setPrefWidth(200);
        label.setMaxWidth(260);
        var control = new ColumnConstraints();
        control.setHgrow(Priority.ALWAYS);
        control.setFillWidth(true);
        grid.getColumnConstraints().setAll(label, control);
        grid.setAlignment(Pos.TOP_LEFT);
        return grid;
    }

    private static void add(GridPane grid, String title, Node control) {
        int row = grid.getRowCount();
        if (title == null) {
            grid.add(control, 0, row, 2, 1);
            return;
        }
        var label = new Label(title);
        label.setWrapText(true);
        label.getStyleClass().add("field-label");
        label.setLabelFor(control);
        if (control instanceof javafx.scene.layout.Region region)
            region.setMaxWidth(Double.MAX_VALUE);
        grid.addRow(row, label, control);
    }

    Prediction.Settings prediction() {
        boolean cell = engine == Engine.CELLPOSE;
        boolean spot = engine == Engine.SPOTIFLOW;
        Double prob = spot && threshold.getText().isBlank() ? null
                : decimal(threshold, "Threshold", cell ? -20 : 0, cell ? 20 : 1);
        return new Prediction.Settings(engine, executable(), resolvedModel(), device(), channels(),
                decimal(downsample, "Downsample", 1, 128), cell ? decimal(diameter, "Diameter", 0, 10000) : 0,
                prob, cell ? decimal(flow, "Flow threshold", 0, 10) : 0,
                spot ? integer(distance, "Minimum peak distance", 1, 1024) : 1,
                spot ? decimal(radius, "Spot marker radius", 0.25, 100) : 0,
                spot || annotations.isSelected() ? 0 : decimal(expansion, "Cell expansion", 0, 1000),
                spot ? 0 : integer(mean, "Mean filter radius", 0, 99),
                spot ? 0 : decimal(gaussian, "Gaussian blur", 0, 99),
                annotations.isSelected(), excludeBorder.isSelected(), measurements.isSelected(), replaceSpots.isSelected());
    }

    Training.Settings training(Path projectFolder) {
        var modelName = name.getText().strip();
        if (!modelName.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}") || modelName.endsWith(".")
                || modelName.toUpperCase(Locale.ROOT).matches("(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?"))
            throw new IllegalArgumentException("Enter a valid model name using letters, numbers, dots, hyphens or underscores.");
        boolean cell = engine == Engine.CELLPOSE;
        return new Training.Settings(engine, executable(), resolvedModel(), device(), channels(),
                decimal(downsample, "Downsample", 1, 128), projectFolder.resolve("models").resolve(modelName),
                integer(epochs, "Epochs", 1, 5000), decimal(rate, "Learning rate", 1e-9, 1),
                integer(batch, "Batch size", 1, 256), cell ? decimal(diameter, "Diameter", 0, 10000) : 0,
                cell ? integer(masks, "Minimum instances", 1, 10000) : 0,
                integer(crop, "Training crop size", 16, 1024), cell ? 0 : decimal(sigma, "Spot sigma", 0.1, 32),
                cell ? integer(mean, "Mean filter radius", 0, 99) : 0,
                cell ? decimal(gaussian, "Gaussian blur", 0, 99) : 0);
    }

    private String executable() {
        if (engine == Engine.STARDIST)
            return "";
        var value = python.getText().strip();
        if (value.isEmpty() || value.equals("python"))
            return "python";
        var path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path))
            throw new IllegalArgumentException("Select a valid Python executable in Advanced settings.");
        return path.toString();
    }

    private String resolvedModel() {
        var value = model.getText().strip();
        if (value.isEmpty()) {
            if (training)
                return "";
            throw new IllegalArgumentException("Choose a " + engine.title + " model.");
        }
        var path = Path.of(value);
        if (engine == Engine.STARDIST && !Files.isRegularFile(path))
            throw new IllegalArgumentException("Choose a local StarDist model file.");
        if (Files.exists(path))
            return path.toAbsolutePath().normalize().toString();
        if (path.isAbsolute() || path.getNameCount() > 1 || value.contains("\\") || value.contains("/"))
            throw new IllegalArgumentException("The selected model path does not exist.");
        return value;
    }

    private String device() {
        return device.getValue().toLowerCase(Locale.ROOT);
    }

    private Channels channels() {
        if (primary.getValue() == null)
            throw new IllegalArgumentException("Select an image channel.");
        return new Channels(primary.getValue(), engine == Engine.CELLPOSE ? secondary.getValue() : null);
    }

    private static double decimal(TextField field, String title, double min, double max) {
        double value;
        try {
            value = Double.parseDouble(field.getText().strip());
        } catch (NumberFormatException e) {
            value = Double.NaN;
        }
        if (!Double.isFinite(value) || value < min || value > max)
            throw new IllegalArgumentException(title + " must be between " + min + " and " + max + ".");
        return value;
    }

    private static int integer(TextField field, String title, int min, int max) {
        var value = decimal(field, title, min, max);
        if (value != Math.rint(value))
            throw new IllegalArgumentException(title + " must be a whole number.");
        return (int) value;
    }
}

package qupath.ext.cidl;

import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

final class PresetEditor {
    record Result(ModelLibrary.Scope scope, ModelLibrary.Preset preset) {}

    private static final Channels.Choice CURRENT = new Channels.Choice(-2, "Keep current channel");
    private final ComboBox<Channels.Choice> primary = new ComboBox<>();
    private final ComboBox<Channels.Choice> secondary = new ComboBox<>();
    private final ComboBox<Channels.Choice> third = new ComboBox<>();
    private final ComboBox<String> device = new ComboBox<>();
    private final ComboBox<String> target = new ComboBox<>();
    private final ComboBox<String> border = new ComboBox<>();
    private final ComboBox<String> annotations = new ComboBox<>();
    private final ComboBox<String> measurements = new ComboBox<>();
    private final ComboBox<String> replace = new ComboBox<>();
    private final TextField downsample = number();
    private final TextField pixelSize = number();
    private final TextField diameter = number();
    private final TextField threshold = number();
    private final TextField flow = number();
    private final TextField nms = number();
    private final TextField expansion = number();
    private final TextField mean = number();
    private final TextField blur = number();
    private final TextField peak = number();
    private final TextField radius = number();
    private final TextField epochs = number();
    private final TextField rate = number();
    private final TextField batch = number();
    private final TextField minimum = number();
    private final TextField crop = number();
    private final TextField sigma = number();

    static Result open(Window owner, ModelLibrary library, ModelLibrary.Choice model, ModelLibrary.Preset existing,
                       Boolean fixedMode) {
        return new PresetEditor().edit(owner, library, model, existing, fixedMode);
    }

    private Result edit(Window owner, ModelLibrary library, ModelLibrary.Choice model, ModelLibrary.Preset existing,
                        Boolean fixedMode) {
        var engine = model.entry().engine();
        threshold.setPromptText("Model default");
        nms.setPromptText("Model default");
        var dialog = new Dialog<Result>();
        dialog.initOwner(owner);
        dialog.setTitle("Preset · " + engine.title);
        dialog.setHeaderText(model.entry().name());
        var name = new TextField(existing == null ? "" : existing.name());
        name.setPromptText("Preset name");
        var mode = new ComboBox<>(javafx.collections.FXCollections.observableArrayList("Prediction", "Training"));
        if (engine == Engine.CELLSAM)
            mode.getItems().remove("Training");
        mode.setValue(Boolean.TRUE.equals(fixedMode) || existing != null && fixedMode == null && existing.training()
                ? "Training" : "Prediction");
        mode.setDisable(fixedMode != null || engine == Engine.CELLSAM);
        var scopes = new ComboBox<ModelLibrary.Scope>();
        scopes.getItems().setAll(library.writableScopes());
        scopes.setValue(model.scope() == ModelLibrary.Scope.BUILT_IN ? scopes.getItems().getFirst() : model.scope());
        scopes.setDisable(existing != null && fixedMode == null && model.scope() != ModelLibrary.Scope.BUILT_IN);

        var data = library.qupath().getImageData();
        var channels = data == null ? List.<Channels.Choice>of() : Channels.choices(data.getServer());
        var saved = existing == null ? null : existing.options();
        configureChannel(primary, channels, saved == null || saved.channels() == null ? null : saved.channels().primary(), false);
        configureChannel(secondary, channels, saved == null || saved.channels() == null ? null : saved.channels().secondary(), true);
        configureChannel(third, channels, saved == null || saved.channels() == null ? null : saved.channels().third(), true);
        device.getItems().setAll("Keep current", "Auto", "CPU", "CUDA");
        device.getSelectionModel().selectFirst();
        target.getItems().setAll("Keep current", "Nuclei", "Cells");
        target.getSelectionModel().selectFirst();
        for (var state : List.of(border, annotations, measurements, replace)) {
            state.getItems().setAll("Keep current", "On", "Off");
            state.getSelectionModel().selectFirst();
        }
        if (saved != null)
            load(saved);
        var metadata = grid();
        add(metadata, "Name", name);
        add(metadata, "Workflow", mode);
        add(metadata, "Save in", scopes);
        var settingsHost = new VBox();
        Runnable redraw = () -> settingsHost.getChildren().setAll(settings(engine, "Training".equals(mode.getValue())));
        // GridPane rows own their controls. Detach before rebuilding for the other workflow.
        mode.valueProperty().addListener((obs, old, next) -> {
            if (!settingsHost.getChildren().isEmpty())
                ((GridPane) settingsHost.getChildren().getFirst()).getChildren().clear();
            redraw.run();
        });
        redraw.run();
        var detail = new TitledPane("Settings", settingsHost);
        detail.setExpanded(existing == null || fixedMode == null);
        var error = new Label();
        error.setWrapText(true);
        var body = new VBox(10, metadata, detail, error);
        var scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(detail.isExpanded() ? 490 : 200);
        detail.expandedProperty().addListener((obs, old, opened) -> scroll.setPrefViewportHeight(opened ? 490 : 200));
        dialog.getDialogPane().setContent(scroll);
        dialog.getDialogPane().setPrefWidth(510);
        dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        var ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.disableProperty().bind(name.textProperty().isEmpty().or(scopes.valueProperty().isNull()));
        var result = new Result[1];
        ok.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                if (name.getText().isBlank())
                    throw new IllegalArgumentException("Enter a preset name.");
                boolean training = "Training".equals(mode.getValue());
                var id = existing == null ? ModelLibrary.newId() : existing.id();
                var preset = new ModelLibrary.Preset(id, name.getText().strip(), training, values(engine, training));
                result[0] = new Result(scopes.getValue(), preset);
            } catch (IllegalArgumentException e) {
                error.setText(e.getMessage());
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == ButtonType.OK ? result[0] : null);
        return dialog.showAndWait().orElse(null);
    }

    private GridPane settings(Engine engine, boolean training) {
        var grid = grid();
        boolean spot = engine == Engine.SPOTIFLOW;
        add(grid, "Image channel", primary);
        if (engine.usesSecondaryChannel())
            add(grid, "Secondary channel", secondary);
        if (engine.usesThirdChannel())
            add(grid, "Third channel", third);
        add(grid, "Downsample", downsample);
        if (engine.usesPython(training))
            add(grid, "Device", device);
        if (engine == Engine.INSTANSEG)
            add(grid, "Pixel size override (µm)", pixelSize);
        if (!spot) {
            add(grid, "Mean filter radius", mean);
            add(grid, "Gaussian sigma", blur);
        }
        if (training) {
            if (engine == Engine.CELLPOSE)
                add(grid, "Mean diameter", diameter);
            add(grid, "Epochs", epochs);
            add(grid, "Learning rate", rate);
            add(grid, "Batch size", batch);
            add(grid, "Training crop (pixels)", crop);
            if (spot)
                add(grid, "Spot sigma", sigma);
            else
                add(grid, "Minimum instances", minimum);
        } else {
            if (engine == Engine.INSTANSEG)
                add(grid, "Target", target);
            else
                add(grid, engine == Engine.CELLSAM ? "Box confidence threshold" : "Probability threshold", threshold);
            if (engine.isCellpose()) {
                add(grid, "Diameter", diameter);
                add(grid, "Flow threshold", flow);
            }
            if (engine == Engine.STARDIST)
                add(grid, "Overlap threshold", nms);
            if (spot) {
                add(grid, "Minimum peak distance", peak);
                add(grid, "Spot marker radius", radius);
                add(grid, "Replace previous spots", replace);
            } else {
                if (engine != Engine.CELLSAM)
                    add(grid, "Cell expansion", expansion);
                add(grid, "Create annotations", annotations);
                add(grid, "Measurements", measurements);
            }
            add(grid, "Exclude edge objects", border);
        }
        return grid;
    }

    private ModelLibrary.Options values(Engine engine, boolean trainingMode) {
        var channels = new Channels(selected(primary), engine.usesSecondaryChannel() ? selected(secondary) : null,
                engine.usesThirdChannel() ? selected(third) : null);
        var training = trainingMode ? new ModelLibrary.TrainingValues(
                integer(epochs, "Epochs", 1, 5000), decimal(rate, "Learning rate", 1e-9, 1),
                integer(batch, "Batch size", 1, 256),
                engine == Engine.SPOTIFLOW ? null : integer(minimum, "Minimum instances", 1, 10000),
                trainingCrop(engine), engine == Engine.SPOTIFLOW ? decimal(sigma, "Spot sigma", 0.1, 32) : null)
                : null;
        String deviceName = device.getSelectionModel().getSelectedIndex() == 0
                ? null : device.getValue().toLowerCase(java.util.Locale.ROOT);
        boolean spot = engine == Engine.SPOTIFLOW;
        return new ModelLibrary.Options(channels, decimal(downsample, "Downsample", 1, 128),
                engine == Engine.INSTANSEG ? decimal(pixelSize, "Pixel size", 0, 1000) : null,
                !trainingMode && engine == Engine.INSTANSEG && target.getSelectionModel().getSelectedIndex() > 0
                        ? "Cells".equals(target.getValue()) : null,
                trainingMode || engine == Engine.INSTANSEG ? null
                        : decimal(threshold, "Threshold", engine.isCellpose() ? -20 : 0, engine.isCellpose() ? 20 : 1),
                !trainingMode && engine == Engine.STARDIST ? decimal(nms, "Overlap threshold", 0, 1) : null,
                engine == Engine.CELLPOSE || engine == Engine.CELLPOSE_SAM && !trainingMode
                        ? decimal(diameter, "Diameter", 0, 10000) : null,
                !trainingMode && engine.isCellpose() ? decimal(flow, "Flow threshold", 0, 10) : null,
                spot ? null : integer(mean, "Mean filter radius", 0, 99), spot ? null : decimal(blur, "Gaussian sigma", 0, 99),
                !trainingMode && spot ? integer(peak, "Minimum peak distance", 1, 1024) : null,
                !trainingMode && spot ? decimal(radius, "Spot marker radius", 0.25, 100) : null,
                !trainingMode && !spot && engine != Engine.CELLSAM ? decimal(expansion, "Cell expansion", 0, 1000) : null,
                trainingMode ? null : state(border), trainingMode || spot ? null : state(annotations),
                trainingMode || spot ? null : state(measurements), !trainingMode && spot ? state(replace) : null,
                deviceName, training);
    }

    private Integer trainingCrop(Engine engine) {
        if (engine == Engine.CELLPOSE_SAM && crop.getText().strip().equals("0"))
            return 0;
        return integer(crop, "Training crop", 16, 1024);
    }

    private void load(ModelLibrary.Options saved) {
        set(downsample, saved.downsample());
        set(pixelSize, saved.pixelSize());
        set(diameter, saved.diameter());
        set(threshold, saved.threshold());
        set(flow, saved.flow());
        set(nms, saved.nms());
        set(expansion, saved.expansion());
        set(mean, saved.mean());
        set(blur, saved.gaussian());
        set(peak, saved.minDistance());
        set(radius, saved.spotRadius());
        if (saved.device() != null) {
            device.setValue(switch (saved.device()) {
                case "cpu" -> "CPU";
                case "cuda" -> "CUDA";
                default -> "Auto";
            });
        }
        if (saved.cells() != null)
            target.setValue(saved.cells() ? "Cells" : "Nuclei");
        set(border, saved.excludeBorder());
        set(annotations, saved.annotations());
        set(measurements, saved.measurements());
        set(replace, saved.replaceSpots());
        var train = saved.training();
        if (train != null) {
            set(epochs, train.epochs());
            set(rate, train.rate());
            set(batch, train.batch());
            set(minimum, train.minimumMasks());
            set(crop, train.crop());
            set(sigma, train.sigma());
        }
    }

    private static void configureChannel(ComboBox<Channels.Choice> combo, List<Channels.Choice> available,
                                         Channels.Choice saved, boolean optional) {
        var items = new ArrayList<>(available);
        items.addFirst(CURRENT);
        if (optional)
            items.add(1, Channels.NONE);
        var matching = Channels.match(items, saved);
        if (matching == null && saved != null) {
            items.add(saved);
            matching = saved;
        }
        combo.getItems().setAll(items);
        combo.setValue(matching == null ? CURRENT : matching);
        Channels.useSwatches(combo);
    }

    private static Channels.Choice selected(ComboBox<Channels.Choice> combo) {
        return combo.getValue() == CURRENT ? null : combo.getValue();
    }

    private static GridPane grid() {
        var grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(7);
        return grid;
    }

    private static void add(GridPane grid, String name, Node item) {
        grid.addRow(grid.getRowCount(), new Label(name), item);
        GridPane.setHgrow(item, Priority.ALWAYS);
        ((javafx.scene.layout.Region)item).setMaxWidth(Double.MAX_VALUE);
    }

    private static TextField number() {
        var field = new TextField();
        field.setPromptText("Keep current / model default");
        return field;
    }

    private static void set(TextField field, Number value) {
        field.setText(value == null ? "" : value.toString());
    }

    private static void set(ComboBox<String> combo, Boolean value) {
        combo.getSelectionModel().select(value == null ? 0 : value ? 1 : 2);
    }

    private static Boolean state(ComboBox<String> combo) {
        return switch (combo.getSelectionModel().getSelectedIndex()) {
            case 1 -> true;
            case 2 -> false;
            default -> null;
        };
    }

    private static Double decimal(TextField field, String name, double min, double max) {
        if (field.getText().isBlank())
            return null;
        try {
            double value = Double.parseDouble(field.getText().strip());
            if (Double.isFinite(value) && value >= min && value <= max)
                return value;
        } catch (NumberFormatException ignored) {}
        throw new IllegalArgumentException(name + " must be between " + min + " and " + max + ".");
    }

    private static Integer integer(TextField field, String name, int min, int max) {
        var value = decimal(field, name, min, max);
        if (value == null)
            return null;
        if (value != Math.rint(value))
            throw new IllegalArgumentException(name + " must be a whole number.");
        return value.intValue();
    }
}


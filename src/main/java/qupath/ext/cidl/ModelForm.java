package qupath.ext.cidl;

import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
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
    private final ModelLibrary library;
    private final TextField python = new TextField();
    private final ComboBox<String> device = new ComboBox<>();
    private final StringProperty model = new SimpleStringProperty();
    private final StringProperty modelAlias = new SimpleStringProperty("");
    private final ComboBox<Channels.Choice> primary = new ComboBox<>();
    private final Label trainingImageNotice = new Label("Add at least one project image above to choose channels.");
    private final ComboBox<Channels.Choice> secondary = new ComboBox<>();
    private final ComboBox<Channels.Choice> third = new ComboBox<>();
    private final TextField downsample = new TextField("1");
    private final ComboBox<Integer> rectangleSize = new ComboBox<>();
    private final TextField diameter = new TextField("0");
    private final TextField threshold = new TextField();
    private final TextField nms = new TextField();
    private final TextField pixelSize = new TextField("0");
    private final ComboBox<String> target = new ComboBox<>();
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
        this(engine, training, window, pythonPath, null);
    }

    ModelForm(Engine engine, boolean training, Window window, StringProperty pythonPath, ModelLibrary library) {
        this.engine = engine;
        this.training = training;
        this.window = window;
        this.library = library;
        Channels.useSwatches(primary);
        Channels.useSwatches(secondary);
        Channels.useSwatches(third);
        if (engine.usesPython(training))
            python.textProperty().bindBidirectional(pythonPath);
        selectModel(engine.defaultModel(training));
        device.getItems().setAll("Auto", "CPU", "CUDA");
        device.getSelectionModel().selectFirst();
        secondary.getItems().add(Channels.NONE);
        secondary.setValue(Channels.NONE);
        threshold.setText(engine == Engine.STARDIST_QP ? "0.5" : engine.isCellpose() ? "0" : "");
        nms.setPromptText("Model default");
        target.getItems().setAll("Nuclei", "Cells");
        target.getSelectionModel().selectFirst();
        expansion.disableProperty().bind(annotations.selectedProperty().or(target.valueProperty().isEqualTo("Cells")));
        threshold.setPromptText("Model default");
        excludeBorder.setSelected(engine != Engine.SPOTIFLOW);
        replaceSpots.setSelected(true);
        rate.setText(switch (engine) {
            case CELLPOSE -> "0.005";
            case CELLPOSE_SAM -> "0.00001";
            case STARDIST, STARDIST_QP -> "0.0001";
            default -> "0.0003";
        });
        batch.setText(engine == Engine.CELLPOSE ? "8" : engine == Engine.CELLPOSE_SAM ? "1" : "4");
        crop.setText(engine == Engine.CELLPOSE ? "224" : engine == Engine.CELLPOSE_SAM ? "0" : "256");
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
        if (training) {
            trainingImageNotice.setId("training-channel-notice");
            trainingImageNotice.setWrapText(true);
            trainingImageNotice.getStyleClass().add("note");
            trainingImageNotice.managedProperty().bind(trainingImageNotice.visibleProperty());
            add(input, null, trainingImageNotice);
        }
        if (engine.usesSecondaryChannel())
            add(input, "Secondary channel", secondary);
        if (engine.usesThirdChannel())
            add(input, "Third channel", third);
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
            if (engine == Engine.CELLPOSE)
                add(parameters, "Mean diameter (from scratch, 0 = default)", diameter);
            if (engine != Engine.SPOTIFLOW)
                add(parameters, "Minimum instances per training patch", masks);
            if (engine == Engine.SPOTIFLOW)
                add(parameters, "Spot sigma (pixels, from scratch)", sigma);
        } else {
            if (engine.isCellpose()) {
                add(parameters, "Diameter (model pixels, 0 = auto)", diameter);
                add(parameters, "Cell probability threshold", threshold);
                add(parameters, "Flow threshold", flow);
            } else if (engine == Engine.INSTANSEG) {
                add(parameters, "Segmentation target", target);
            } else {
                add(parameters, engine == Engine.CELLSAM ? "Box confidence threshold" : "Probability threshold", threshold);
                if (engine == Engine.STARDIST)
                    add(parameters, "Overlap threshold (NMS)", nms);
            }
            if (engine == Engine.SPOTIFLOW) {
                add(parameters, "Minimum peak distance (model pixels)", distance);
                add(parameters, "Spot marker radius (image pixels)", radius);
            } else {
                if (engine != Engine.CELLSAM)
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
        if (training) {
            add(advanced, engine == Engine.CELLPOSE_SAM ? "Training crop (0 = backbone default)" : "Training crop size (model pixels)", crop);
            if (engine == Engine.CELLPOSE_SAM) {
                var information = new Label("SAM uses 256 px. DINO defaults to 384 px and supports other multiples of 16.");
                information.setWrapText(true);
                add(advanced, null, information);
            }
        }
        if (engine == Engine.INSTANSEG)
            add(advanced, "Pixel size override (µm/image px, 0 = calibrated)", pixelSize);
        if (engine != Engine.SPOTIFLOW) {
            add(advanced, "Mean filter radius (model pixels)", mean);
            add(advanced, "Gaussian blur sigma (model pixels)", gaussian);
        }
        if (engine.usesPython(training)) {
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
        browse.setOnAction(event -> {
            var selected = fileChooser("Choose Python executable", python.getText(), null).showOpenDialog(window);
            if (selected != null)
                python.setText(selected.getAbsolutePath());
        });
        var row = new HBox(6, python, browse);
        HBox.setHgrow(python, Priority.ALWAYS);
        return row;
    }

    private boolean canTrainFromScratch() {
        return training && engine != Engine.CELLPOSE_SAM;
    }

    private HBox modelPicker() {
        String empty = canTrainFromScratch() ? "Train from scratch" : "Choose a model";
        var display = modelDisplay(
                Bindings.createStringBinding(() -> model.get().isBlank() ? empty : selectedModelName(), model, modelAlias),
                Bindings.createStringBinding(() -> {
                    if (!model.get().isBlank())
                        return model.get();
                    return training && engine == Engine.INSTANSEG
                            ? "Browse for weights.pt from a previous CI DL InstanSeg run to fine-tune."
                            : empty;
                }, model));

        Node browse;
        if (engine == Engine.INSTANSEG && !training) {
            var folder = new MenuItem("Model folder…");
            folder.setOnAction(event -> browseModelFolder());
            var file = new MenuItem("TorchScript file…");
            file.setOnAction(event -> browseModelFile());
            browse = new MenuButton("Browse…", null, folder, file);
        } else {
            var button = new Button("Browse…");
            button.setOnAction(event -> {
                if (engine.browsesFolders(training))
                    browseModelFolder();
                else
                    browseModelFile();
            });
            browse = button;
        }

        var row = new HBox(6, display, browse);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(display, Priority.ALWAYS);
        var presets = engine.modelPresets(training);
        if (!presets.isEmpty() || canTrainFromScratch() || library != null) {
            var menu = new MenuButton("Presets");
            if (canTrainFromScratch()) {
                var scratch = new MenuItem("Train from scratch");
                scratch.setOnAction(event -> selectModel(""));
                menu.getItems().add(scratch);
                if (!presets.isEmpty())
                    menu.getItems().add(new SeparatorMenuItem());
            }
            for (var preset : presets) {
                var item = new MenuItem(preset);
                item.setOnAction(event -> selectModel(engine.modelChoice(preset, training)));
                menu.getItems().add(item);
            }
            if (library != null) {
                var fixed = List.copyOf(menu.getItems());
                menu.setOnShowing(event -> populatePresets(menu, fixed));
            }
            row.getChildren().add(menu);
        }
        return row;
    }

    private void populatePresets(MenuButton menu, List<MenuItem> fixed) {
        menu.getItems().setAll(fixed);
        var snapshot = library.snapshot(false);
        for (var scope : library.writableScopes()) {
            var items = new Menu(scope.title);
            for (var choice : snapshot.models()) {
                if (choice.scope() != scope || choice.entry().engine() != engine || !library.available(choice, training))
                    continue;
                var entry = choice.entry();
                var presets = entry.presets().stream().filter(preset -> preset.training() == training).toList();
                var use = new MenuItem(entry.name() + (entry.version().isBlank() ? "" : " · " + entry.version()));
                use.setOnAction(event -> applySelection(new ModelLibrary.Selection(choice, null)));
                if (presets.isEmpty()) {
                    if (!entry.location().isBlank())
                        items.getItems().add(use);
                    continue;
                }
                var model = new Menu(use.getText());
                if (!entry.location().isBlank()) {
                    use.setText("Use model");
                    model.getItems().add(use);
                    model.getItems().add(new SeparatorMenuItem());
                }
                for (var preset : presets) {
                    var item = new MenuItem(preset.name());
                    item.setOnAction(event -> applySelection(new ModelLibrary.Selection(choice, preset)));
                    model.getItems().add(item);
                }
                items.getItems().add(model);
            }
            if (!items.getItems().isEmpty())
                menu.getItems().add(items);
        }
        if (!menu.getItems().isEmpty())
            menu.getItems().add(new SeparatorMenuItem());
        var save = new MenuItem("Save as preset…");
        save.setOnAction(event -> savePreset());
        var manage = new MenuItem("Model Manager…");
        manage.setOnAction(event -> library.show(window, engine, training, this::applySelection));
        menu.getItems().addAll(save, manage);
    }

    private void savePreset() {
        try {
            var selected = resolvedModel();
            var project = library.qupath().getProject();
            var folder = project == null ? Path.of(".").toAbsolutePath() : project.getPath().toAbsolutePath().getParent();
            var options = training ? ModelLibrary.Options.from(training(folder)) : ModelLibrary.Options.from(prediction());
            var model = library.currentModel(engine, selected, training);
            var draft = new ModelLibrary.Preset(ModelLibrary.newId(), "", training, options);
            var result = PresetEditor.open(window, library, model, draft, training);
            if (result != null) {
                var stored = library.savePreset(result.scope(), model, result.preset());
                applySelection(new ModelLibrary.Selection(stored, result.preset()));
            }
        } catch (java.io.IOException | RuntimeException e) {
            var alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING, e.getMessage());
            alert.initOwner(window);
            alert.show();
        }
    }

    void applySelection(ModelLibrary.Selection selection) {
        var choice = selection.model();
        var preset = selection.preset();
        selectModel(library.resolve(choice, training));
        var entry = choice.entry();
        modelAlias.set(preset == null ? entry.name() + (entry.version().isBlank() ? "" : " · " + entry.version())
                : entry.name() + " · " + preset.name());
        var settings = preset == null ? entry.options() : preset.options();
        if (settings == null)
            return;
        if (settings.channels() != null) {
            applyChannel(primary, settings.channels().primary());
            applyChannel(secondary, settings.channels().secondary());
            applyChannel(third, settings.channels().third());
        }
        setValue(downsample, settings.downsample());
        setValue(pixelSize, settings.pixelSize());
        setValue(mean, settings.mean());
        setValue(gaussian, settings.gaussian());
        if (settings.device() != null)
            device.setValue(switch (settings.device()) {
                case "cpu" -> "CPU";
                case "cuda" -> "CUDA";
                default -> "Auto";
            });
        if (training) {
            if (engine == Engine.CELLPOSE)
                setValue(diameter, settings.diameter());
            var values = settings.training();
            if (values != null) {
                setValue(epochs, values.epochs());
                setValue(rate, values.rate());
                setValue(batch, values.batch());
                setValue(masks, values.minimumMasks());
                setValue(crop, values.crop());
                setValue(sigma, values.sigma());
            }
        } else if (settings.training() == null) {
            if (settings.cells() != null)
                target.setValue(settings.cells() ? "Cells" : "Nuclei");
            if (preset != null || settings.cells() != null) {
                threshold.setText(settings.threshold() == null ? (engine == Engine.STARDIST_QP ? "0.5" : "")
                        : settings.threshold().toString());
                nms.setText(settings.nms() == null ? "" : settings.nms().toString());
            }
            setValue(diameter, settings.diameter());
            setValue(flow, settings.flow());
            setValue(distance, settings.minDistance());
            if (engine == Engine.SPOTIFLOW)
                setValue(radius, settings.spotRadius());
            setValue(expansion, settings.expansion());
            if (settings.excludeBorder() != null)
                excludeBorder.setSelected(settings.excludeBorder());
            if (settings.annotations() != null)
                annotations.setSelected(settings.annotations());
            if (settings.measurements() != null)
                measurements.setSelected(settings.measurements());
            if (settings.replaceSpots() != null)
                replaceSpots.setSelected(settings.replaceSpots());
        }
    }

    private static void setValue(TextField field, Number value) {
        if (value != null)
            field.setText(value.toString());
    }

    private static void applyChannel(ComboBox<Channels.Choice> combo, Channels.Choice saved) {
        if (saved == null)
            return;
        combo.setValue(Channels.match(combo.getItems(), saved));
    }

    private void selectModel(String location) {
        modelAlias.set("");
        model.set(location);
    }

    String selectedModelName() {
        return modelAlias.get().isBlank() ? modelDisplayName(model.get()) : modelAlias.get();
    }

    static Label modelDisplay(javafx.beans.value.ObservableValue<String> name,
                              javafx.beans.value.ObservableValue<String> details) {
        var display = new Label();
        display.getStyleClass().add("model-selection");
        display.setMinWidth(0);
        display.setMaxWidth(Double.MAX_VALUE);
        display.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        display.textProperty().bind(name);
        var tooltip = new Tooltip();
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(620);
        tooltip.textProperty().bind(details);
        display.setTooltip(tooltip);
        display.accessibleTextProperty().bind(details);
        return display;
    }

    static String modelDisplayName(String selection) {
        var path = Path.of(selection);
        var filename = path.getFileName();
        if (filename == null)
            return selection;
        var name = filename.toString();
        var parent = path.getParent();
        if (parent != null && parent.getFileName() != null
                && List.of("instanseg.pt", "model.onnx", "weights.pt", "weights_best.pt")
                    .contains(name.toLowerCase(Locale.ROOT))) {
            return parent.getFileName() + " / " + name;
        }
        return name;
    }

    private void browseModelFolder() {
        var chooser = new DirectoryChooser();
        chooser.setTitle("Choose " + engine.title + " model folder");
        var initial = initialDirectory(model.get(), engine.modelDirectory(training));
        if (initial != null)
            chooser.setInitialDirectory(initial.toFile());
        var selected = chooser.showDialog(window);
        if (selected != null)
            selectModel(selected.getAbsolutePath());
    }

    private void browseModelFile() {
        var chooser = fileChooser("Choose " + engine.title + " model", model.get(), engine.modelDirectory(training));
        if (engine == Engine.STARDIST_QP && !training)
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("StarDist model", "*.pb", "*.onnx"));
        if (engine == Engine.INSTANSEG || engine == Engine.CELLSAM)
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(engine.title + " checkpoint", "*.pt", "*.pth"));
        var selected = chooser.showOpenDialog(window);
        if (selected != null)
            selectModel(selected.getAbsolutePath());
    }

    private static FileChooser fileChooser(String title, String selection, Path directory) {
        var chooser = new FileChooser();
        chooser.setTitle(title);
        var initial = initialDirectory(selection, directory);
        if (initial != null)
            chooser.setInitialDirectory(initial.toFile());
        return chooser;
    }

    private static Path initialDirectory(String selection, Path directory) {
        var text = selection.strip();
        try {
            if (!text.isEmpty()) {
                var path = Path.of(text);
                if (Files.isDirectory(path) && path.isAbsolute())
                    return path;
                if (path.isAbsolute() && Files.isDirectory(path.getParent()))
                    return path.getParent();
            }
            if (directory != null && Files.isDirectory(directory))
                return directory.toAbsolutePath();
        } catch (IllegalArgumentException ignored) {
            // Let the chooser open in its default location.
        }
        return null;
    }

    static StringProperty pythonPreference(Engine engine) {
        return pythonPreference(engine.id);
    }

    static StringProperty pythonPreference(String engineId) {
        String value = System.getenv("CI_DL_" + engineId.toUpperCase(Locale.ROOT).replace('-', '_') + "_PYTHON");
        if (value == null) {
            String base = System.getenv("QUPATH_BASE");
            if (base != null) {
                var path = Path.of(base, "PythonEnvs", "qupathenv", "python.exe");
                if (Files.isRegularFile(path))
                    value = path.toString();
            }
        }
        return PathPrefs.createPersistentPreference("ci.dl." + engineId + ".python", value == null ? "" : value);
    }

    void setTrainingImagesSelected(boolean selected) {
        trainingImageNotice.setVisible(!selected);
        primary.setDisable(!selected);
        secondary.setDisable(!selected);
        third.setDisable(!selected);
    }

    void updateChannels(List<Channels.Choice> choices) {
        var oldPrimary = Channels.match(choices, primary.getValue());
        var oldSecondary = Channels.match(choices, secondary.getValue());
        var oldThird = Channels.match(choices, third.getValue());
        primary.getItems().setAll(choices);
        primary.setValue(oldPrimary == null ? Channels.initial(choices) : oldPrimary);
        var second = new ArrayList<Channels.Choice>();
        second.add(Channels.NONE);
        second.addAll(choices);
        secondary.getItems().setAll(second);
        secondary.setValue(oldSecondary == null ? Channels.NONE : oldSecondary);
        third.getItems().setAll(second);
        third.setValue(oldThird == null ? Channels.NONE : oldThird);
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
        boolean cell = engine.isCellpose();
        boolean spot = engine == Engine.SPOTIFLOW;
        boolean cells = engine == Engine.CELLSAM || engine == Engine.INSTANSEG && "Cells".equals(target.getValue());
        Double prob = threshold.getText().isBlank() && engine != Engine.STARDIST_QP ? null
                : decimal(threshold, "Threshold", cell ? -20 : 0, cell ? 20 : 1);
        return new Prediction.Settings(engine, executable(), resolvedModel(), device(), channels(),
                decimal(downsample, "Downsample", 1, 128), cell ? decimal(diameter, "Diameter", 0, 10000) : 0,
                prob, cell ? decimal(flow, "Flow threshold", 0, 10) : 0,
                spot ? integer(distance, "Minimum peak distance", 1, 1024) : 1,
                spot ? decimal(radius, "Spot marker radius", 0.25, 100) : 0,
                spot || cells || annotations.isSelected() ? 0 : decimal(expansion, "Cell expansion", 0, 1000),
                spot ? 0 : integer(mean, "Mean filter radius", 0, 99),
                spot ? 0 : decimal(gaussian, "Gaussian blur", 0, 99),
                annotations.isSelected(), excludeBorder.isSelected(), measurements.isSelected(), replaceSpots.isSelected(),
                engine == Engine.STARDIST && !nms.getText().isBlank() ? decimal(nms, "Overlap threshold", 0, 1) : null,
                cells, pixelSize());
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
                engine != Engine.SPOTIFLOW ? integer(masks, "Minimum instances", 1, 10000) : 0,
                trainingCrop(), engine == Engine.SPOTIFLOW ? decimal(sigma, "Spot sigma", 0.1, 32) : 0,
                engine != Engine.SPOTIFLOW ? integer(mean, "Mean filter radius", 0, 99) : 0,
                engine != Engine.SPOTIFLOW ? decimal(gaussian, "Gaussian blur", 0, 99) : 0, pixelSize());
    }

    private int trainingCrop() {
        if (engine == Engine.CELLPOSE_SAM && crop.getText().strip().equals("0"))
            return 0;
        return integer(crop, "Training crop size", 16, 1024);
    }

    private double pixelSize() {
        return engine == Engine.INSTANSEG ? decimal(pixelSize, "Pixel size", 0, 1000) : 0;
    }

    private String executable() {
        if (!engine.usesPython(training))
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
        var value = model.get().strip();
        if (value.isEmpty()) {
            if (canTrainFromScratch())
                return "";
            throw new IllegalArgumentException("Choose a " + engine.title + " model.");
        }
        if (engine.modelPresets(training).contains(value))
            value = engine.modelChoice(value, training);
        var path = Path.of(value);
        if (engine == Engine.STARDIST_QP && !training && !Files.isRegularFile(path))
            throw new IllegalArgumentException("Choose a local StarDist-QP model file.");
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
        return new Channels(primary.getValue(), engine.usesSecondaryChannel() ? secondary.getValue() : null,
                engine.usesThirdChannel() ? third.getValue() : null);
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

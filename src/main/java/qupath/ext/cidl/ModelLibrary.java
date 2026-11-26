package qupath.ext.cidl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

final class ModelLibrary {
    enum Scope { PROJECT, PERSONAL, BUILT_IN }
    enum Origin { PRETRAINED, TRAINED }
    record Options(Channels channels, Double downsample, Double pixelSize, Boolean cells, Double threshold,
                   Double nms, Double diameter, Double flow, Integer mean, Double gaussian,
                   Integer minDistance, Double spotRadius, Double expansion, Boolean excludeBorder) {
        static Options from(Prediction.Settings s) {
            return new Options(s.channels(), s.downsample(), s.pixelSize(), s.cells(), s.threshold(),
                    s.nms(), s.diameter(), s.flowThreshold(), s.mean(), s.gaussian(), s.minDistance(),
                    s.spotRadius(), s.expansion(), s.excludeBorder());
        }

        static Options from(Training.Settings s) {
            return new Options(s.channels(), s.downsample(), s.pixelSize(), null, null, null, null, null,
                    s.mean(), s.gaussian(), null, null, null, null);
        }
    }

    record Entry(String id, String name, Engine engine, String location, String version, Origin origin,
                 Options options) {}
    record Choice(Entry entry, Scope scope) {
        @Override
        public String toString() {
            return entry.name() + (entry.version().isBlank() ? "" : " · " + entry.version())
                    + " (" + scope.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + ")";
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final QuPathGUI qupath;

    ModelLibrary(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    private Path projectRoot() {
        var project = qupath.getProject();
        return project == null ? null : project.getPath().toAbsolutePath().getParent();
    }

    Path file(Scope scope) {
        if (scope == Scope.PROJECT)
            return projectRoot() == null ? null : projectRoot().resolve("models").resolve("ci-dl-library.json");
        if (scope == Scope.BUILT_IN)
            return null;
        var user = PathPrefs.userPathProperty().get();
        return user == null || user.isBlank() ? Path.of(System.getProperty("user.home"), ".qupath", "ci-dl-library.json")
                : Path.of(user, "ci-dl-library.json");
    }

    private List<Entry> read(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file))
            return List.of();
        try (var in = Files.newBufferedReader(file)) {
            var entries = GSON.fromJson(in, Entry[].class);
            return entries == null ? List.of() : List.of(entries);
        } catch (RuntimeException e) {
            throw new IOException("Could not read model library " + file, e);
        }
    }

    List<Choice> entries() throws IOException {
        var choices = new ArrayList<Choice>();
        for (var scope : Scope.values())
            read(file(scope)).forEach(entry -> choices.add(new Choice(entry, scope)));
        choices.sort(Comparator.comparing(Choice::toString, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(choices);
    }

    String resolve(Choice choice, boolean training) {
        var entry = choice.entry();
        if (entry.engine().modelPresets(training).contains(entry.location()))
            return entry.engine().modelChoice(entry.location(), training);
        var path = Path.of(entry.location());
        if (choice.scope() == Scope.PROJECT && !path.isAbsolute() && projectRoot() != null)
            path = projectRoot().resolve(path).normalize();
        if (Files.isDirectory(path) && entry.engine() == Engine.STARDIST_QP && !training)
            path = path.resolve("model.onnx");
        if (entry.engine() == Engine.INSTANSEG && training) {
            if (Files.isDirectory(path))
                path = path.resolve("weights.pt");
            else if (path.getFileName() != null && path.getFileName().toString().equalsIgnoreCase("instanseg.pt"))
                path = path.resolveSibling("weights.pt");
        }
        return path.toString();
    }

    boolean available(Choice choice, boolean training) {
        var location = resolve(choice, training);
        var engine = choice.entry().engine();
        var path = Path.of(location);
        if (engine.browsesFolders(training) && engine != Engine.INSTANSEG)
            return Files.isDirectory(path) || (engine == Engine.SPOTIFLOW && engine.modelPresets(training).contains(location));
        return Files.isRegularFile(path) || (engine != Engine.STARDIST_QP && engine.modelPresets(training).contains(location))
                || engine == Engine.INSTANSEG && !training && Files.isRegularFile(path.resolve("instanseg.pt"));
    }

    private static synchronized void change(Path file, Entry entry, String removeId) throws IOException {
        if (file == null)
            throw new IOException("Open a project to use its model library.");
        var previous = new ArrayList<Entry>();
        if (Files.exists(file)) {
            try (var in = Files.newBufferedReader(file)) {
                var entries = GSON.fromJson(in, Entry[].class);
                if (entries != null)
                    previous.addAll(Arrays.asList(entries));
            } catch (RuntimeException e) {
                throw new IOException("Could not read model library " + file, e);
            }
        }
        previous.removeIf(existing -> existing.id().equals(removeId));
        if (entry != null)
            previous.add(entry);
        Files.createDirectories(file.getParent());
        var staged = Files.createTempFile(file.getParent(), ".ci-dl-models-", ".json");
        try {
            Files.writeString(staged, GSON.toJson(previous));
            try {
                Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private String storeLocation(Scope scope, String location) {
        var path = Path.of(location);
        var root = projectRoot();
        if (scope == Scope.PROJECT && root != null && path.isAbsolute() && path.normalize().startsWith(root))
            return root.relativize(path.normalize()).toString().replace('\\', '/');
        return location;
    }

    static void trained(Path projectFile, Training.Settings s) throws IOException {
        var root = projectFile.toAbsolutePath().getParent();
        var location = root.relativize(s.output()).toString().replace('\\', '/');
        var name = s.output().getFileName().toString();
        var entry = new Entry(UUID.randomUUID().toString(), name, s.engine(), location, "", Origin.TRAINED, Options.from(s));
        change(root.resolve("models").resolve("ci-dl-library.json"), entry, entry.id());
    }

    void saveCurrent(Window owner, Prediction.Settings settings) {
        edit(owner, null, settings);
    }

    void show(Window owner) {
        var dialog = new Dialog<Void>();
        dialog.initOwner(owner);
        dialog.setTitle("CI Deep Learning Segmentation · Model Manager");
        var list = new ListView<Choice>();
        list.setPrefSize(620, 310);
        var info = new Label();
        info.setWrapText(true);
        Runnable refresh = () -> {
            try {
                var all = new ArrayList<>(entries());
                for (var engine : Engine.values()) {
                    for (var preset : engine.modelPresets(false))
                        all.add(new Choice(new Entry(preset, preset, engine, preset, "", Origin.PRETRAINED, null),
                                Scope.BUILT_IN));
                }
                list.getItems().setAll(all);
                info.setText("Import pretrained or trained models to make them available in Presets.");
            } catch (IOException e) {
                info.setText(e.getMessage());
            }
        };
        list.getSelectionModel().selectedItemProperty().addListener((obs, before, choice) -> {
            if (choice != null)
                info.setText(choice.entry().engine().title + " · " + choice.entry().origin() + "\n" + resolve(choice, false));
        });
        var add = new Button("Import model…");
        add.setOnAction(event -> { edit(dialog.getDialogPane().getScene().getWindow(), null, null); refresh.run(); });
        var edit = new Button("Edit…");
        var remove = new Button("Remove from library");
        edit.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(() -> {
            var selected = list.getSelectionModel().getSelectedItem();
            return selected == null || selected.scope() == Scope.BUILT_IN;
        }, list.getSelectionModel().selectedItemProperty()));
        remove.disableProperty().bind(edit.disableProperty());
        edit.setOnAction(event -> { edit(dialog.getDialogPane().getScene().getWindow(),
                list.getSelectionModel().getSelectedItem(), null); refresh.run(); });
        remove.setOnAction(event -> {
            var choice = list.getSelectionModel().getSelectedItem();
            try {
                change(file(choice.scope()), null, choice.entry().id());
                refresh.run();
            } catch (IOException e) {
                info.setText(e.getMessage());
            }
        });
        refresh.run();
        var content = new VBox(9, list, new HBox(8, add, edit, remove), info);
        VBox.setVgrow(list, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.setResizable(true);
        dialog.show();
    }

    private void edit(Window owner, Choice existing, Prediction.Settings current) {
        var dialog = new Dialog<ButtonType>();
        dialog.initOwner(owner);
        dialog.setTitle(existing == null ? "Add to Model Manager" : "Edit model");
        var name = new TextField();
        var version = new TextField();
        var location = new TextField();
        location.setEditable(false);
        var engine = new ComboBox<Engine>();
        engine.getItems().setAll(Engine.values());
        engine.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Engine value) { return value == null ? "" : value.title; }
            @Override public Engine fromString(String value) { throw new UnsupportedOperationException(); }
        });
        engine.setValue(Engine.CELLPOSE_SAM);
        var scope = new ComboBox<Scope>();
        scope.getItems().setAll(projectRoot() == null ? List.of(Scope.PERSONAL) : List.of(Scope.PROJECT, Scope.PERSONAL));
        scope.getSelectionModel().selectFirst();
        scope.setDisable(existing != null);
        var origin = new ComboBox<Origin>();
        origin.getItems().setAll(Origin.values());
        origin.setValue(Origin.PRETRAINED);
        if (existing != null) {
            name.setText(existing.entry().name());
            version.setText(existing.entry().version());
            location.setText(resolve(existing, false));
            engine.setValue(existing.entry().engine());
            scope.setValue(existing.scope());
            origin.setValue(existing.entry().origin());
        } else if (current != null) {
            name.setText(ModelForm.modelDisplayName(current.model()));
            location.setText(current.model());
            engine.setValue(current.engine());
        }
        engine.setDisable(existing != null || current != null);
        var browse = new MenuButton("Browse…");
        var file = new MenuItem("Model file…");
        file.setOnAction(event -> {
            var selected = new FileChooser().showOpenDialog(dialog.getDialogPane().getScene().getWindow());
            if (selected != null)
                location.setText(selected.getAbsolutePath());
        });
        var folder = new MenuItem("Model folder…");
        folder.setOnAction(event -> {
            var selected = new DirectoryChooser().showDialog(dialog.getDialogPane().getScene().getWindow());
            if (selected != null)
                location.setText(selected.getAbsolutePath());
        });
        browse.getItems().setAll(file, folder);
        browse.setDisable(current != null);
        var model = new HBox(8, location, browse);
        HBox.setHgrow(location, Priority.ALWAYS);
        var grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.addRow(0, new Label("Name"), name);
        grid.addRow(1, new Label("Backend"), engine);
        grid.addRow(2, new Label("Model location"), model);
        grid.addRow(3, new Label("Version"), version);
        grid.addRow(4, new Label("Source"), origin);
        grid.addRow(5, new Label("Library"), scope);
        var error = new Label();
        error.setWrapText(true);
        grid.add(error, 0, 6, 2, 1);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        var ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.disableProperty().bind(name.textProperty().isEmpty().or(location.textProperty().isEmpty()));
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                String id = existing == null ? UUID.randomUUID().toString() : existing.entry().id();
                String modelName = name.getText().strip();
                if (modelName.isEmpty())
                    throw new IOException("Enter a model name.");
                String path = existing != null && location.getText().equals(resolve(existing, false))
                        ? existing.entry().location() : storeLocation(scope.getValue(), location.getText());
                var entry = new Entry(id, modelName, engine.getValue(), path, version.getText().strip(),
                        origin.getValue(), current != null ? Options.from(current) : existing == null ? null : existing.entry().options());
                change(file(scope.getValue()), entry, id);
            } catch (IOException e) {
                error.setText(e.getMessage());
                event.consume();
            }
        });
        dialog.showAndWait();
    }
}


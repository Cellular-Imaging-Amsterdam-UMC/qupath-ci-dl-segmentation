package qupath.ext.cidl;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class ModelManager {
    private final ModelLibrary library;
    private final Consumer<ModelLibrary.Selection> apply;
    private final Boolean applyTraining;
    private final Engine fromMethod;
    private final Stage stage = new Stage();
    private final TextField search = new TextField();
    private final ToggleGroup methods = new ToggleGroup();
    private final ComboBox<String> scopes = new ComboBox<>();
    private final ListView<ModelLibrary.Choice> models = new ListView<>();
    private final ListView<ModelLibrary.Preset> presets = new ListView<>();
    private final Label status = new Label();
    private final Label count = new Label();
    private final Label modelName = new Label("Select a model");
    private final Label details = new Label();
    private final Label path = new Label();
    private final Label notes = new Label();
    private final HBox identity = new HBox(8);
    private final Button edit = new Button("Edit model…");
    private final ToggleButton favorite = new ToggleButton("Favorite");
    private final Button reveal = new Button("Show folder");
    private final Button remove = new Button("Remove entry…");
    private final MenuButton copy = new MenuButton("Copy to");
    private final MenuButton move = new MenuButton("Move to");
    private final Button createPreset = new Button("New preset…");
    private final Button editPreset = new Button("Edit…");
    private final Button duplicatePreset = new Button("Duplicate…");
    private final Button removePreset = new Button("Remove…");
    private final Button use = new Button("Use selection");
    private final Button cancel = new Button("Cancel transfer");
    private final ProgressIndicator progress = new ProgressIndicator();
    private final AtomicBoolean cancelTransfer = new AtomicBoolean();
    private ModelLibrary.Snapshot snapshot;
    private boolean busy;
    private boolean updating;

    ModelManager(ModelLibrary library, Window owner, Engine initial,
                 Boolean applyTraining, Consumer<ModelLibrary.Selection> apply) {
        this.library = library;
        this.applyTraining = applyTraining;
        this.fromMethod = initial;
        this.apply = apply;
        stage.initOwner(owner);
        stage.setTitle("CI Deep Learning Segmentation · Model Manager");
        stage.setMinWidth(820);
        stage.setMinHeight(540);
        WindowIcon.apply(stage);
        var root = new BorderPane();
        root.getStyleClass().addAll("ci-dl", "model-manager");
        root.setTop(header());
        var split = new SplitPane(modelList(), modelDetails());
        split.setDividerPositions(0.44);
        var center = new HBox(12, methodNavigation(initial), split);
        center.setPadding(new Insets(12));
        HBox.setHgrow(split, Priority.ALWAYS);
        root.setCenter(center);
        root.setBottom(footer());
        var scene = new Scene(root, 1080, 640);
        scene.getStylesheets().add(Workspace.class.getResource("workspace.css").toExternalForm());
        stage.setScene(scene);
        javafx.beans.InvalidationListener locationChanged = ignored -> refresh(null);
        stage.setOnShowing(event -> {
            library.qupath().projectProperty().addListener(locationChanged);
            library.sharedPathProperty().addListener(locationChanged);
            refresh(null);
        });
        stage.setOnHidden(event -> {
            library.qupath().projectProperty().removeListener(locationChanged);
            library.sharedPathProperty().removeListener(locationChanged);
        });
        stage.setOnCloseRequest(event -> {
            if (busy) {
                event.consume();
                status.setText("Cancel the transfer or wait for it to finish before closing.");
            }
        });
        search.textProperty().addListener((obs, before, after) -> filter());
        scopes.valueProperty().addListener((obs, before, after) -> filter());
        methods.selectedToggleProperty().addListener((obs, before, after) -> filter());
        models.getSelectionModel().selectedItemProperty().addListener((obs, before, after) -> showModel(after));
        presets.getSelectionModel().selectedItemProperty().addListener((obs, before, after) -> refreshActions());
        refresh(null);
    }

    void show() {
        stage.show();
        search.requestFocus();
    }

    private Node header() {
        var title = new Label("Model Manager");
        title.getStyleClass().add("workspace-title");
        var add = new Button("Import model…");
        add.getStyleClass().add("primary");
        add.setOnAction(event -> importModel());
        var shared = new Button("Shared folder…");
        shared.setOnAction(event -> chooseShared());
        var refresh = new Button("Refresh");
        refresh.setOnAction(event -> refresh(selected()));
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var row = new HBox(10, WindowIcon.view(30), title, spacer, refresh, shared, add);
        row.getStyleClass().add("workspace-header");
        row.setAlignment(Pos.CENTER_LEFT);
        row.disableProperty().bind(progress.visibleProperty());
        return row;
    }

    private Node methodNavigation(Engine initial) {
        var column = new VBox(5);
        column.setMinWidth(150);
        column.getStyleClass().add("model-methods");
        var all = methodButton("All methods", null);
        column.getChildren().add(all);
        all.setSelected(initial == null);
        for (var engine : Engine.values()) {
            var button = methodButton(engine.title, engine);
            column.getChildren().add(button);
            if (engine == initial)
                button.setSelected(true);
        }
        return column;
    }

    private ToggleButton methodButton(String label, Engine engine) {
        var button = new ToggleButton(label, engine == null ? null : MethodIcons.view(engine, 16));
        button.setUserData(engine);
        button.setToggleGroup(methods);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.getStyleClass().add("model-method");
        button.setOnAction(event -> {
            if (!button.isSelected())
                button.setSelected(true);
        });
        return button;
    }

    private Node modelList() {
        search.setPromptText("Search models and presets");
        search.setAccessibleText("Search models and presets");
        scopes.getItems().add("All libraries");
        for (var scope : ModelLibrary.Scope.values())
            scopes.getItems().add(scope.title);
        scopes.setValue("All libraries");
        scopes.setAccessibleText("Model library filter");
        models.setId("ci-dl-model-list");
        models.setPlaceholder(new Label("No models match"));
        models.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(ModelLibrary.Choice choice, boolean empty) {
                super.updateItem(choice, empty);
                if (empty || choice == null) {
                    setText(null);
                    setGraphic(null);
                    setAccessibleText(null);
                    return;
                }
                var record = choice.entry();
                var title = (record.favorite() ? "★ " : "") + record.name();
                var subtitle = record.engine().title + " · " + choice.scope().title
                        + (record.version().isBlank() ? "" : " · " + record.version());
                setText(title + "\n" + subtitle);
                setGraphic(MethodIcons.view(record.engine(), 24));
                setGraphicTextGap(9);
                setAccessibleText(title + " · " + subtitle);
            }
        });
        models.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && !use.isDisabled() && use.isVisible())
                use.fire();
        });
        HBox.setHgrow(search, Priority.ALWAYS);
        VBox.setVgrow(models, Priority.ALWAYS);
        count.getStyleClass().add("note");
        return new VBox(7, new HBox(8, search, scopes), models, count);
    }

    private Node modelDetails() {
        modelName.getStyleClass().add("workspace-title");
        modelName.setWrapText(true);
        details.getStyleClass().add("note");
        details.setWrapText(true);
        notes.setWrapText(true);
        notes.managedProperty().bind(notes.visibleProperty());
        path.getStyleClass().add("note");
        path.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        path.setMinWidth(0);
        path.setMaxWidth(Double.MAX_VALUE);
        identity.setAlignment(Pos.CENTER_LEFT);
        edit.setOnAction(event -> editModel());
        favorite.setOnAction(event -> setFavorite());
        reveal.setOnAction(event -> reveal());
        remove.setOnAction(event -> remove());
        copy.getItems().add(new MenuItem("Choose destination"));
        copy.setOnShowing(event -> transferChoices(copy, false));
        move.getItems().add(new MenuItem("Choose destination"));
        move.setOnShowing(event -> transferChoices(move, true));
        var commands = new FlowPane(7, 6, edit, favorite, reveal, copy, move, remove);

        var header = new Label("Presets");
        header.getStyleClass().add("model-list-title");
        presets.setId("ci-dl-preset-list");
        presets.setPlaceholder(new Label("No custom presets for this model"));
        presets.setPrefHeight(150);
        presets.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(ModelLibrary.Preset preset, boolean empty) {
                super.updateItem(preset, empty);
                setText(empty || preset == null ? null : preset.name() + "  ·  "
                        + (preset.training() ? "Training" : "Prediction"));
            }
        });
        createPreset.setOnAction(event -> preset(null, false));
        editPreset.setOnAction(event -> preset(presets.getSelectionModel().getSelectedItem(), false));
        duplicatePreset.setOnAction(event -> preset(presets.getSelectionModel().getSelectedItem(), true));
        removePreset.setOnAction(event -> removePreset());
        var presetActions = new FlowPane(7, 6, createPreset, editPreset, duplicatePreset, removePreset);
        use.setVisible(apply != null);
        use.setManaged(apply != null);
        use.getStyleClass().add("primary");
        use.setOnAction(event -> {
            apply.accept(new ModelLibrary.Selection(selected(), presets.getSelectionModel().getSelectedItem()));
            stage.hide();
        });
        VBox.setVgrow(presets, Priority.ALWAYS);
        var body = new VBox(8, identity, details, path, notes, commands, new Separator(),
                header, presets, presetActions, use);
        body.setPadding(new Insets(3, 0, 3, 12));
        return body;
    }

    private Node footer() {
        status.setWrapText(true);
        status.getStyleClass().add("note");
        status.setMaxWidth(Double.MAX_VALUE);
        progress.setVisible(false);
        progress.managedProperty().bind(progress.visibleProperty());
        progress.setMaxSize(20, 20);
        cancel.visibleProperty().bind(progress.visibleProperty());
        cancel.managedProperty().bind(cancel.visibleProperty());
        cancel.setOnAction(event -> {
            cancelTransfer.set(true);
            status.setText("Stopping after the current file…");
            cancel.setDisable(true);
        });
        var close = new Button("Close");
        close.setOnAction(event -> stage.close());
        close.disableProperty().bind(progress.visibleProperty());
        var row = new HBox(8, status, progress, cancel, close);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(status, Priority.ALWAYS);
        row.getStyleClass().add("workspace-footer");
        return row;
    }


    private Engine method() {
        return methods.getSelectedToggle() == null ? null : (Engine) methods.getSelectedToggle().getUserData();
    }

    private ModelLibrary.Choice selected() {
        return models.getSelectionModel().getSelectedItem();
    }

    private void refresh(ModelLibrary.Choice select) {
        snapshot = library.snapshot(true);
        filter();
        if (select != null)
            models.getItems().stream().filter(choice -> choice.scope() == select.scope()
                    && choice.entry().id().equals(select.entry().id())).findFirst()
                    .ifPresent(models.getSelectionModel()::select);
        status.setText(String.join(" · ", snapshot.errors()));
    }

    private void filter() {
        if (snapshot == null || updating)
            return;
        var previous = selected();
        var currentMethod = method();
        var scope = scopes.getValue();
        var query = search.getText().strip().toLowerCase(Locale.ROOT);
        var rows = snapshot.models().stream().filter(choice -> currentMethod == null || choice.entry().engine() == currentMethod)
                .filter(choice -> "All libraries".equals(scope) || choice.scope().title.equals(scope))
                .filter(choice -> query.isBlank() || matches(choice, query)).toList();
        models.getItems().setAll(rows);
        if (previous != null && rows.contains(previous))
            models.getSelectionModel().select(previous);
        else if (!rows.isEmpty())
            models.getSelectionModel().selectFirst();
        else
            showModel(null);
        count.setText(rows.size() + (rows.size() == 1 ? " model" : " models"));
    }

    private static boolean matches(ModelLibrary.Choice choice, String query) {
        var entry = choice.entry();
        var contents = entry.name() + " " + entry.engine().title + " " + entry.version() + " "
                + entry.origin() + " " + entry.notes() + " "
                + String.join(" ", entry.presets().stream().map(ModelLibrary.Preset::name).toList());
        return java.util.Arrays.stream(query.split("\\s+"))
                .allMatch(term -> contents.toLowerCase(Locale.ROOT).contains(term));
    }

    private void showModel(ModelLibrary.Choice choice) {
        var entry = choice == null ? null : choice.entry();
        modelName.setText(entry == null ? "Select a model" : entry.name());
        if (entry == null)
            identity.getChildren().setAll(modelName);
        else
            identity.getChildren().setAll(MethodIcons.view(entry.engine(), 27), modelName);
        details.setText(entry == null ? "" : entry.engine().title + " · " + choice.scope() + " · " + entry.origin()
                + (entry.version().isBlank() ? "" : " · " + entry.version())
                + (library.available(choice, false) || library.available(choice, true) ? "" : " · Model missing"));
        String location = entry == null ? "" : library.rawLocation(choice, false);
        path.setText(location);
        path.setTooltip(location.isBlank() ? null : new Tooltip(location));
        notes.setText(entry == null ? "" : entry.notes());
        notes.setVisible(!notes.getText().isBlank());
        favorite.setSelected(entry != null && entry.favorite());
        presets.getItems().setAll(entry == null ? List.of() : entry.presets());
        refreshActions();
    }

    private void refreshActions() {
        var choice = selected();
        var preset = presets.getSelectionModel().getSelectedItem();
        boolean has = choice != null;
        boolean writable = has && choice.scope() != ModelLibrary.Scope.BUILT_IN;
        var file = artifact(choice);
        edit.setDisable(busy || !writable);
        favorite.setDisable(busy || !writable);
        reveal.setDisable(busy || file == null);
        remove.setDisable(busy || !writable);
        copy.setDisable(busy || file == null);
        move.setDisable(busy || file == null || !writable || choice.entry().origin() != ModelLibrary.Origin.TRAINED
                || !ModelTransfer.canMove(file, library.storage(choice.scope())));
        createPreset.setDisable(busy || !has);
        editPreset.setDisable(busy || !writable || preset == null);
        duplicatePreset.setDisable(editPreset.isDisabled());
        removePreset.setDisable(editPreset.isDisabled());
        boolean training = applyTraining == null ? preset != null && preset.training() : applyTraining;
        use.setDisable(busy || !has || preset != null && preset.training() != training || !library.available(choice, training)
                || applyTraining != null && choice.entry().engine() != fromMethod);
        use.setText(preset == null ? "Use model" : "Apply preset");
    }

    private Path artifact(ModelLibrary.Choice choice) {
        if (choice == null)
            return null;
        String value = library.rawLocation(choice, false);
        if (value.isBlank())
            return null;
        var file = Path.of(value);
        return Files.exists(file) ? ModelTransfer.artifact(file.toAbsolutePath().normalize(), choice.entry().engine()) : null;
    }

    private void chooseShared() {
        var picker = new DirectoryChooser();
        picker.setTitle("Choose the shared model library folder");
        if (!library.sharedPath().isBlank() && Files.isDirectory(Path.of(library.sharedPath())))
            picker.setInitialDirectory(Path.of(library.sharedPath()).toFile());
        var selected = picker.showDialog(stage);
        if (selected != null) {
            library.setSharedPath(selected.toPath());
            scopes.setValue(ModelLibrary.Scope.SHARED.title);
            refresh(null);
            status.setText("Shared folder · " + selected.getAbsolutePath());
        }
    }

    private void importModel() {
        var result = ModelEditor.open(stage, library, null, method(), scopes.getValue());
        if (result == null)
            return;
        var choice = new ModelLibrary.Choice(result.entry(), result.scope());
        if (result.copy()) {
            var source = Path.of(result.entry().location()).toAbsolutePath();
            var request = new ModelTransfer.Request(choice, ModelTransfer.artifact(source, result.entry().engine()),
                    null, library.storage(result.scope()), List.of(), false);
            transfer(request, "Importing model files…", "Imported");
        } else {
            try {
                var target = library.storage(result.scope());
                var entry = result.entry().relocated(result.entry().id(), target.store(Path.of(result.entry().location())));
                library.write(result.scope(), entry.id(), before -> entry);
                selectResult(new ModelLibrary.Choice(entry, result.scope()));
                status.setText("Imported " + entry.name());
            } catch (IOException | RuntimeException e) {
                status.setText(message(e));
            }
        }
    }

    private void editModel() {
        var choice = selected();
        var result = ModelEditor.open(stage, library, choice, method(), scopes.getValue());
        if (result == null)
            return;
        try {
            library.write(choice.scope(), choice.entry().id(), current -> {
                if (current == null)
                    throw new IllegalStateException("The model was removed. Refresh the library.");
                var edit = result.entry();
                return new ModelLibrary.Entry(current.id(), edit.name(), current.engine(), edit.location(), edit.version(),
                        edit.origin(), current.options(), edit.notes(), current.favorite(), current.presets());
            });
            refresh(choice);
            status.setText("Model updated.");
        } catch (IOException | RuntimeException e) {
            status.setText(message(e));
        }
    }

    private void setFavorite() {
        var model = selected();
        try {
            boolean flag = favorite.isSelected();
            library.write(model.scope(), model.entry().id(), current -> current == null ? null
                    : new ModelLibrary.Entry(current.id(), current.name(), current.engine(), current.location(),
                    current.version(), current.origin(), current.options(), current.notes(), flag, current.presets()));
            refresh(model);
        } catch (IOException | RuntimeException e) {
            favorite.setSelected(model.entry().favorite());
            status.setText(message(e));
        }
    }

    private void reveal() {
        try {
            var file = artifact(selected());
            var folder = Files.isDirectory(file) ? file : file.getParent();
            java.awt.Desktop.getDesktop().open(folder.toFile());
        } catch (IOException | RuntimeException e) {
            status.setText(message(e));
        }
    }

    private boolean confirm(String title, String description) {
        var alert = new Alert(Alert.AlertType.CONFIRMATION, description, ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(stage);
        alert.setTitle(title);
        alert.setHeaderText(title);
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    private void remove() {
        var model = selected();
        if (model == null || !confirm("Remove model entry?", "This removes the model and its presets from the library. The files stay on disk."))
            return;
        try {
            library.write(model.scope(), model.entry().id(), before -> null);
            refresh(null);
            status.setText("Model entry removed.");
        } catch (IOException | RuntimeException e) {
            status.setText(message(e));
        }
    }

    private void preset(ModelLibrary.Preset selected, boolean duplicate) {
        var model = selected();
        var draft = duplicate && selected != null ? new ModelLibrary.Preset(ModelLibrary.newId(),
                selected.name() + " copy", selected.training(), selected.options()) : selected;
        var result = PresetEditor.open(stage, library, model, draft, selected == null ? applyTraining : null);
        if (result == null)
            return;
        try {
            selectResult(library.savePreset(result.scope(), model, result.preset()));
            presets.getItems().stream().filter(preset -> preset.id().equals(result.preset().id())).findFirst()
                    .ifPresent(presets.getSelectionModel()::select);
            status.setText("Preset saved.");
        } catch (IOException | RuntimeException e) {
            status.setText(message(e));
        }
    }

    private void removePreset() {
        var model = selected();
        var preset = presets.getSelectionModel().getSelectedItem();
        if (model == null || preset == null || !confirm("Remove preset?", preset.name()))
            return;
        try {
            library.write(model.scope(), model.entry().id(), current -> current == null ? null
                    : current.withPresets(current.presets().stream().filter(p -> !p.id().equals(preset.id())).toList()));
            refresh(model);
            status.setText("Preset removed.");
        } catch (IOException | RuntimeException e) {
            status.setText(message(e));
        }
    }

    private void transferChoices(MenuButton menu, boolean moving) {
        var model = selected();
        menu.getItems().clear();
        if (model == null)
            return;
        for (var target : library.writableScopes()) {
            if (target == model.scope())
                continue;
            var item = new MenuItem(target.title);
            item.setOnAction(event -> startTransfer(model, target, moving));
            menu.getItems().add(item);
        }
        if (library.storage(ModelLibrary.Scope.SHARED) == null) {
            var configure = new MenuItem("Set shared folder…");
            configure.setOnAction(event -> chooseShared());
            menu.getItems().add(configure);
        }
    }

    private void startTransfer(ModelLibrary.Choice model, ModelLibrary.Scope target, boolean moving) {
        var source = artifact(model);
        if (source == null) {
            status.setText("The source model files are unavailable.");
            return;
        }
        if (moving && !confirm("Move model to " + target.title + "?",
                model.entry().name() + " and its presets will move to the selected library. "
                        + "The original files are removed after the copy has been verified."))
            return;
        var references = snapshot.models().stream()
                .filter(other -> other.scope() != model.scope() || !other.entry().id().equals(model.entry().id()))
                .map(this::artifact).filter(Objects::nonNull).toList();
        var request = new ModelTransfer.Request(model, source, library.storage(model.scope()), library.storage(target),
                references, moving);
        transfer(request, moving ? "Moving model files…" : "Copying model files…", moving ? "Moved" : "Copied");
    }

    private void transfer(ModelTransfer.Request request, String working, String done) {
        busy = true;
        cancelTransfer.set(false);
        progress.setVisible(true);
        cancel.setDisable(false);
        refreshActions();
        status.setText(working);
        Thread.ofVirtual().name("CI DL model transfer").start(() -> {
            ModelTransfer.Result result = null;
            String completion;
            try {
                result = ModelTransfer.run(request, cancelTransfer::get);
                completion = result.cleanupNotice().isBlank() ? done + " " + result.choice().entry().name()
                        + " · " + result.choice().scope() : result.cleanupNotice();
            } catch (CancellationException e) {
                completion = "Transfer cancelled.";
            } catch (IOException | RuntimeException e) {
                completion = message(e);
            }
            var completed = result;
            var message = completion;
            Platform.runLater(() -> {
                busy = false;
                progress.setVisible(false);
                if (completed == null)
                    refresh(selected());
                else
                    selectResult(completed.choice());
                refreshActions();
                status.setText(message);
            });
        });
    }

    private void selectResult(ModelLibrary.Choice result) {
        updating = true;
        search.clear();
        scopes.setValue(result.scope().title);
        methods.getToggles().stream().filter(toggle -> toggle.getUserData() == result.entry().engine())
                .findFirst().ifPresent(methods::selectToggle);
        updating = false;
        refresh(result);
    }

    private static String message(Throwable error) {
        return Objects.requireNonNullElse(error.getMessage(), error.getClass().getSimpleName());
    }
}


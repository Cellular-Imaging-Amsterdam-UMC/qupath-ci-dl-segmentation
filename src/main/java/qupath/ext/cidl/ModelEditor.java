package qupath.ext.cidl;

import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.List;

final class ModelEditor {
    record Result(ModelLibrary.Entry entry, ModelLibrary.Scope scope, boolean copy) {}

    static Result open(Window owner, ModelLibrary library, ModelLibrary.Choice existing,
                       Engine suggestedEngine, String suggestedScope) {
        var dialog = new Dialog<Result>();
        dialog.initOwner(owner);
        dialog.setTitle(existing == null ? "Import model" : "Edit model");
        var name = new TextField();
        var version = new TextField();
        var source = new TextField();
        source.setEditable(false);
        source.setPromptText("Select a model file or folder");
        var description = new TextArea();
        description.setWrapText(true);
        description.setPrefRowCount(3);
        var engine = new ComboBox<>(FXCollections.observableArrayList(Engine.values()));
        engine.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Engine value) { return value == null ? "" : value.title; }
            @Override public Engine fromString(String value) { throw new UnsupportedOperationException(); }
        });
        engine.setValue(suggestedEngine == null ? Engine.CELLPOSE_SAM : suggestedEngine);
        var scope = new ComboBox<>(FXCollections.observableArrayList(library.writableScopes()));
        scope.getSelectionModel().selectFirst();
        for (var item : scope.getItems()) {
            if (item.title.equals(suggestedScope))
                scope.setValue(item);
        }
        var kind = new ComboBox<>(FXCollections.observableArrayList(ModelLibrary.Origin.values()));
        kind.setValue(ModelLibrary.Origin.PRETRAINED);
        var managed = new CheckBox("Copy model files into this library");
        managed.setSelected(true);
        if (existing != null) {
            var entry = existing.entry();
            name.setText(entry.name());
            version.setText(entry.version());
            source.setText(library.rawLocation(existing, false));
            description.setText(entry.notes());
            engine.setValue(entry.engine());
            engine.setDisable(true);
            scope.setValue(existing.scope());
            scope.setDisable(true);
            kind.setValue(entry.origin());
            managed.setVisible(false);
            managed.setManaged(false);
        }
        var browseFile = new MenuItem("Model file…");
        browseFile.setOnAction(event -> {
            var file = new FileChooser().showOpenDialog(dialog.getDialogPane().getScene().getWindow());
            if (file != null)
                choose(file.toPath(), source, name);
        });
        var browseFolder = new MenuItem("Model folder…");
        browseFolder.setOnAction(event -> {
            var folder = new DirectoryChooser().showDialog(dialog.getDialogPane().getScene().getWindow());
            if (folder != null)
                choose(folder.toPath(), source, name);
        });
        var location = new HBox(8, source, new MenuButton("Browse…", null, browseFile, browseFolder));
        HBox.setHgrow(source, Priority.ALWAYS);
        var grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.addRow(0, new Label("Method"), engine);
        grid.addRow(1, new Label("Model location"), location);
        grid.addRow(2, new Label("Name"), name);
        grid.addRow(3, new Label("Version"), version);
        grid.addRow(4, new Label("Source"), kind);
        grid.addRow(5, new Label("Save in"), scope);
        grid.addRow(6, new Label("Notes"), description);
        grid.add(managed, 1, 7);
        var error = new Label();
        error.setWrapText(true);
        grid.add(error, 0, 8, 2, 1);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().setPrefWidth(570);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        var ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.disableProperty().bind(name.textProperty().isEmpty().or(scope.valueProperty().isNull())
                .or(javafx.beans.binding.Bindings.createBooleanBinding(
                        () -> existing == null && source.getText().isBlank(), source.textProperty())));
        var result = new Result[1];
        ok.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                if (name.getText().isBlank())
                    throw new IllegalArgumentException("Enter a model name.");
                var id = existing == null ? ModelLibrary.newId() : existing.entry().id();
                var raw = source.getText();
                var chosen = new ModelLibrary.Entry(id, name.getText().strip(), engine.getValue(), raw,
                        version.getText().strip(), kind.getValue(), null, description.getText().strip(), false, List.of());
                boolean changed = existing == null || !raw.equals(library.rawLocation(existing, false));
                if (changed) {
                    var test = new ModelLibrary.Choice(chosen, ModelLibrary.Scope.BUILT_IN);
                    if (!library.available(test, false) && !library.available(test, true))
                        throw new IllegalArgumentException("Choose a file or folder compatible with " + engine.getValue().title + ".");
                }
                if (existing != null)
                    chosen = chosen.relocated(id, changed ? library.storage(existing.scope()).store(Path.of(raw))
                            : existing.entry().location());
                result[0] = new Result(chosen, scope.getValue(), existing == null && managed.isSelected());
            } catch (IllegalArgumentException e) {
                error.setText(e.getMessage());
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == ButtonType.OK ? result[0] : null);
        return dialog.showAndWait().orElse(null);
    }

    private static void choose(Path selection, TextField source, TextField name) {
        source.setText(selection.toAbsolutePath().toString());
        if (name.getText().isBlank())
            name.setText(selection.getFileName().toString());
    }

    private ModelEditor() {}
}


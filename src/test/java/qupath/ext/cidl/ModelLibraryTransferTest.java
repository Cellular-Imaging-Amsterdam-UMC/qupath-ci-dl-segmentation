package qupath.ext.cidl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelLibraryTransferTest {
    @Test
    void copiesAndMovesAssetsWithTheirPresets(@TempDir Path root) throws Exception {
        var project = storage(root, "project", ModelLibrary.Scope.PROJECT);
        var personal = storage(root, "personal", ModelLibrary.Scope.PERSONAL);
        var shared = storage(root, "shared", ModelLibrary.Scope.SHARED);
        Files.createDirectories(shared.relativeBase());
        var checkpoint = Files.createDirectories(project.assets().resolve("nuclei"));
        Files.writeString(checkpoint.resolve("config.json"), "{}");
        Files.writeString(checkpoint.resolve("weights_best.pt"), "weights");
        var prediction = new ModelLibrary.Preset("prediction", "DAPI nuclei", false, null);
        var fineTune = new ModelLibrary.Preset("training", "High resolution", true, null);
        var entry = new ModelLibrary.Entry("source", "Nuclei", Engine.STARDIST, project.store(checkpoint), "1",
                ModelLibrary.Origin.TRAINED, null, "Test entry", true, List.of(prediction, fineTune));
        ModelLibrary.change(project.registry(), entry.id(), current -> entry);
        var source = new ModelLibrary.Choice(entry, project.scope());

        var copied = ModelTransfer.run(new ModelTransfer.Request(source, checkpoint, project,
                personal, List.of(), false), () -> false).choice();
        var personalPath = personal.resolve(copied.entry().location());
        assertTrue(Files.exists(checkpoint));
        assertEquals("weights", Files.readString(personalPath.resolve("weights_best.pt")));
        assertEquals(List.of(prediction, fineTune), copied.entry().presets());

        var moved = ModelTransfer.run(new ModelTransfer.Request(source, checkpoint, project,
                shared, List.of(personalPath), true), () -> false).choice();
        assertFalse(Files.exists(checkpoint));
        assertTrue(ModelLibrary.read(project.registry()).isEmpty());
        assertTrue(Files.exists(personalPath));
        var published = ModelLibrary.read(shared.registry()).getFirst();
        assertEquals(moved.entry(), published);
        assertEquals("{}", Files.readString(shared.resolve(published.location()).resolve("config.json")));
        assertEquals(List.of(prediction, fineTune), published.presets());
    }

    @Test
    void rejectsMovingAFileReferencedByAnotherEntry(@TempDir Path root) throws Exception {
        var project = storage(root, "project", ModelLibrary.Scope.PROJECT);
        var personal = storage(root, "personal", ModelLibrary.Scope.PERSONAL);
        var path = Files.createDirectories(project.assets()).resolve("trained");
        Files.writeString(path, "weights");
        var entry = new ModelLibrary.Entry("original", "Original", Engine.CELLPOSE, project.store(path), "",
                ModelLibrary.Origin.TRAINED, null);
        ModelLibrary.change(project.registry(), entry.id(), before -> entry);
        var request = new ModelTransfer.Request(new ModelLibrary.Choice(entry, project.scope()), path, project,
                personal, List.of(path), true);
        assertThrows(IOException.class, () -> ModelTransfer.run(request, () -> false));
        assertTrue(Files.exists(path));
        assertEquals(List.of(entry), ModelLibrary.read(project.registry()));
        assertTrue(ModelLibrary.read(personal.registry()).isEmpty());
    }

    @Test
    void matchesOldPresetsToCurrentChannelColors() {
        var red = new Channels.Choice(0, "Channel 1", 0xff0000);
        var green = new Channels.Choice(1, "Channel 2", 0x00ff00);
        assertEquals(green, Channels.match(List.of(red, green), new Channels.Choice(1, "Channel 2")));
        assertEquals(green, Channels.match(List.of(red, green), new Channels.Choice(0, "Channel 2", 0x0000ff)));
    }

    private static ModelLibrary.Storage storage(Path base, String folder, ModelLibrary.Scope scope) {
        var root = base.resolve(folder);
        return new ModelLibrary.Storage(scope, root.resolve("library.json"), root.resolve("models"), root);
    }
}


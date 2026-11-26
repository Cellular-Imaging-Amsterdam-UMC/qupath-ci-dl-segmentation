package qupath.ext.cidl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import javafx.beans.property.StringProperty;
import javafx.stage.Window;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

final class ModelLibrary {
    enum Scope {
        PROJECT("Project"), PERSONAL("My library"), SHARED("Shared"), BUILT_IN("Built-in");

        final String title;
        Scope(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }

    enum Origin {
        PRETRAINED("Pretrained"), TRAINED("Trained");
        private final String title;
        Origin(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }

    record TrainingValues(Integer epochs, Double rate, Integer batch, Integer minimumMasks, Integer crop, Double sigma) {}

    record Options(Channels channels, Double downsample, Double pixelSize, Boolean cells, Double threshold,
                   Double nms, Double diameter, Double flow, Integer mean, Double gaussian,
                   Integer minDistance, Double spotRadius, Double expansion, Boolean excludeBorder,
                   Boolean annotations, Boolean measurements, Boolean replaceSpots, String device, TrainingValues training) {
        static Options from(Prediction.Settings s) {
            return new Options(s.channels(), s.downsample(), s.pixelSize(), s.cells(), s.threshold(),
                    s.nms(), s.diameter(), s.flowThreshold(), s.mean(), s.gaussian(), s.minDistance(),
                    s.spotRadius(), s.expansion(), s.excludeBorder(), s.annotations(), s.measurements(),
                    s.replaceSpots(), s.device(), null);
        }

        static Options from(Training.Settings s) {
            return new Options(s.channels(), s.downsample(), s.pixelSize(), null, null, null, s.diameter(), null,
                    s.mean(), s.gaussian(), null, null, null, null, null, null, null, s.device(),
                    new TrainingValues(s.epochs(), s.learningRate(), s.batchSize(), s.minimumMasks(), s.crop(), s.sigma()));
        }
    }

    record Preset(String id, String name, boolean training, Options options) {}

    record Entry(String id, String name, Engine engine, String location, String version, Origin origin,
                 Options options, String notes, boolean favorite, List<Preset> presets) {
        Entry {
            version = Objects.requireNonNullElse(version, "");
            notes = Objects.requireNonNullElse(notes, "");
            presets = presets == null ? List.of() : List.copyOf(presets);
        }

        Entry(String id, String name, Engine engine, String location, String version, Origin origin, Options options) {
            this(id, name, engine, location, version, origin, options, "", false, List.of());
        }

        Entry withPresets(List<Preset> values) {
            return new Entry(id, name, engine, location, version, origin, options, notes, favorite, values);
        }

        Entry relocated(String newId, String destination) {
            return new Entry(newId, name, engine, destination, version, origin, options, notes, favorite, presets);
        }
    }

    record Choice(Entry entry, Scope scope) {
        @Override public String toString() {
            return entry.name() + (entry.version().isBlank() ? "" : " · " + entry.version()) + " (" + scope + ")";
        }
    }
    record Selection(Choice model, Preset preset) {}
    record Storage(Scope scope, Path registry, Path assets, Path relativeBase) {
        String store(Path path) {
            var absolute = path.toAbsolutePath().normalize();
            if (relativeBase != null && absolute.startsWith(relativeBase))
                return relativeBase.relativize(absolute).toString().replace('\\', '/');
            return absolute.toString();
        }

        Path resolve(String location) {
            var path = Path.of(location);
            return path.isAbsolute() || relativeBase == null ? path : relativeBase.resolve(path).normalize();
        }
    }

    record Snapshot(List<Choice> models, List<String> errors) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final QuPathGUI qupath;
    private final StringProperty shared = PathPrefs.createPersistentPreference("ci.dl.models.sharedPath", "");

    ModelLibrary(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    QuPathGUI qupath() {
        return qupath;
    }

    javafx.beans.property.ReadOnlyStringProperty sharedPathProperty() {
        return shared;
    }

    String sharedPath() {
        return shared.get();
    }

    void setSharedPath(Path path) {
        shared.set(path == null ? "" : path.toAbsolutePath().normalize().toString());
    }

    Storage storage(Scope scope) {
        return switch (scope) {
            case PROJECT -> {
                var project = qupath.getProject();
                if (project == null)
                    yield null;
                var root = project.getPath().toAbsolutePath().normalize().getParent();
                yield new Storage(scope, root.resolve("models/ci-dl-library.json"), root.resolve("models"), root);
            }
            case PERSONAL -> {
                var user = PathPrefs.userPathProperty().get();
                var root = user == null || user.isBlank() ? Path.of(System.getProperty("user.home"), ".qupath")
                        : Path.of(user).toAbsolutePath().normalize();
                yield new Storage(scope, root.resolve("ci-dl-library.json"), root.resolve("ci-dl-models"), root);
            }
            case SHARED -> {
                if (shared.get().isBlank())
                    yield null;
                var root = Path.of(shared.get()).toAbsolutePath().normalize();
                yield new Storage(scope, root.resolve("ci-dl-library.json"), root.resolve("ci-dl-models"), root);
            }
            case BUILT_IN -> null;
        };
    }

    List<Scope> writableScopes() {
        return Arrays.stream(Scope.values()).filter(scope -> storage(scope) != null).toList();
    }

    static List<Entry> read(Path file) throws IOException {
        if (file == null || !Files.exists(file))
            return List.of();
        try (var in = Files.newBufferedReader(file)) {
            var entries = GSON.fromJson(in, Entry[].class);
            return entries == null ? List.of() : List.of(entries);
        } catch (RuntimeException e) {
            throw new IOException("Could not read model library " + file, e);
        }
    }

    Snapshot snapshot(boolean includeDefaults) {
        var choices = new ArrayList<Choice>();
        var warnings = new ArrayList<String>();
        for (var scope : writableScopes()) {
            try {
                var storage = storage(scope);
                if (scope == Scope.SHARED && !Files.isDirectory(storage.relativeBase()))
                    throw new IOException("Shared folder unavailable: " + storage.relativeBase());
                read(storage.registry()).forEach(entry -> choices.add(new Choice(entry, scope)));
            } catch (IOException e) {
                warnings.add(e.getMessage());
            }
        }
        if (includeDefaults) {
            for (var engine : Engine.values()) {
                var names = new java.util.LinkedHashSet<>(engine.modelPresets(false));
                if (engine != Engine.CELLSAM)
                    names.addAll(engine.modelPresets(true));
                for (var preset : names)
                    choices.add(new Choice(new Entry(engine.id + "/" + preset, preset, engine, preset, "",
                            Origin.PRETRAINED, null), Scope.BUILT_IN));
            }
        }
        choices.sort(Comparator.comparing((Choice choice) -> !choice.entry().favorite())
                .thenComparing(choice -> choice.entry().name(), String.CASE_INSENSITIVE_ORDER));
        return new Snapshot(List.copyOf(choices), List.copyOf(warnings));
    }

    String rawLocation(Choice choice, boolean training) {
        var entry = choice.entry();
        if (entry.location().isBlank())
            return "";
        if (entry.engine().modelPresets(training).contains(entry.location()))
            return entry.engine().modelChoice(entry.location(), training);
        var storage = storage(choice.scope());
        return storage == null ? entry.location() : storage.resolve(entry.location()).toString();
    }

    String resolve(Choice choice, boolean training) {
        var location = rawLocation(choice, training);
        if (location.isBlank())
            return location;
        var path = Path.of(location);
        var engine = choice.entry().engine();
        if (Files.isDirectory(path) && engine == Engine.STARDIST_QP && !training)
            path = path.resolve("model.onnx");
        if (engine == Engine.INSTANSEG && training) {
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
        if (training && engine == Engine.CELLSAM)
            return false;
        if (location.isBlank())
            return training && engine != Engine.CELLPOSE_SAM;
        var path = Path.of(location);
        if (engine == Engine.SPOTIFLOW)
            return engine.modelPresets(training).contains(location) || Files.isRegularFile(path.resolve("config.yaml"))
                    && Files.isRegularFile(path.resolve("best.pt"));
        if (engine == Engine.STARDIST || engine == Engine.STARDIST_QP && training) {
            if (!Files.isRegularFile(path.resolve("config.json")))
                return false;
            try (var children = Files.newDirectoryStream(path, "*.pt")) {
                return children.iterator().hasNext();
            } catch (IOException e) {
                return false;
            }
        }
        if (Files.isRegularFile(path)) {
            var name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            return switch (engine) {
                case STARDIST_QP -> name.endsWith(".pb") || name.endsWith(".onnx");
                case CELLSAM, INSTANSEG -> name.endsWith(".pt") || name.endsWith(".pth");
                default -> true;
            };
        }
        return engine == Engine.INSTANSEG ? !training && Files.isRegularFile(path.resolve("instanseg.pt"))
                : engine != Engine.STARDIST_QP && engine.modelPresets(training).contains(location);
    }

    static synchronized void change(Path file, String id, UnaryOperator<Entry> update) throws IOException {
        Files.createDirectories(file.getParent());
        var lock = file.resolveSibling(file.getFileName() + ".lock");
        try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var ignored = channel.tryLock()) {
            if (ignored == null)
                throw new IOException("The model library is busy. Try again.");
            var entries = new ArrayList<>(read(file));
            int index = -1;
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).id().equals(id)) {
                    index = i;
                    break;
                }
            }
            var replacement = update.apply(index < 0 ? null : entries.get(index));
            if (index >= 0)
                entries.remove(index);
            if (replacement != null)
                entries.add(replacement);
            var staged = Files.createTempFile(file.getParent(), ".ci-dl-models-", ".json");
            try {
                Files.writeString(staged, GSON.toJson(entries));
                replace(staged, file);
            } finally {
                Files.deleteIfExists(staged);
            }
        }
    }

    static void replace(Path staged, Path file) throws IOException {
        try {
            Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    void write(Scope scope, String id, UnaryOperator<Entry> update) throws IOException {
        var selected = storage(scope);
        if (selected == null || scope == Scope.SHARED && !Files.isDirectory(selected.relativeBase()))
            throw new IOException("The " + scope + " library is unavailable.");
        change(selected.registry(), id, update);
    }

    static void trained(Path projectFile, Training.Settings s) throws IOException {
        var root = projectFile.toAbsolutePath().getParent();
        var location = root.relativize(s.output()).toString().replace('\\', '/');
        var name = s.output().getFileName().toString();
        var settings = Options.from(s);
        var entry = new Entry(newId(), name, s.engine(), location, "", Origin.TRAINED, settings,
                "", false, List.of(new Preset(newId(), "Fine-tune", true, settings)));
        change(root.resolve("models/ci-dl-library.json"), entry.id(), before -> entry);
    }

    Choice savePreset(Scope scope, Choice model, Preset preset) throws IOException {
        var existing = model.entry();
        var target = storage(scope);
        if (target == null)
            throw new IOException("Choose an available library.");
        if (scope != model.scope() || model.scope() == Scope.BUILT_IN) {
            String symbolic = existing.location();
            var raw = rawLocation(model, preset.training());
            String location = raw.isBlank() || existing.engine().modelPresets(preset.training()).contains(symbolic)
                    ? symbolic : target.store(Path.of(raw));
            var engine = existing.engine();
            var linked = read(target.registry()).stream().filter(e -> e.engine() == engine
                    && e.location().equals(location)).findFirst().orElse(null);
            existing = linked == null ? new Entry(newId(), existing.name(), existing.engine(),
                    location, existing.version(), existing.origin(), null) : linked;
        }
        var base = existing;
        var written = new Entry[1];
        write(scope, base.id(), current -> {
            var record = current == null ? base : current;
            var updated = new ArrayList<>(record.presets());
            if (updated.stream().anyMatch(old -> !old.id().equals(preset.id())
                    && old.training() == preset.training() && old.name().equalsIgnoreCase(preset.name())))
                throw new IllegalArgumentException("A preset with that name already exists for this workflow.");
            updated.removeIf(old -> old.id().equals(preset.id()));
            updated.add(preset);
            written[0] = record.withPresets(updated);
            return written[0];
        });
        return new Choice(written[0], scope);
    }

    Choice currentModel(Engine engine, String location, boolean training) {
        for (var choice : snapshot(false).models()) {
            if (choice.entry().engine() == engine && resolve(choice, training).equals(location))
                return choice;
        }
        for (var name : engine.modelPresets(training)) {
            if (name.equals(location) || engine.modelChoice(name, training).equals(location))
                return new Choice(new Entry(engine.id + "/" + name, name, engine, name, "", Origin.PRETRAINED, null),
                        Scope.BUILT_IN);
        }
        var name = location.isBlank() ? "Train from scratch" : ModelForm.modelDisplayName(location);
        return new Choice(new Entry(newId(), name, engine, location, "", Origin.PRETRAINED, null), Scope.BUILT_IN);
    }

    void show(Window owner) {
        show(owner, null, null, null);
    }

    void show(Window owner, Engine engine, Boolean training, Consumer<Selection> apply) {
        new ModelManager(this, owner, engine, training, apply).show();
    }

    static String newId() { return UUID.randomUUID().toString(); }
}


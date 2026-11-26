package qupath.ext.cidl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

final class ModelTransfer {
    record Request(ModelLibrary.Choice choice, Path source, ModelLibrary.Storage from, ModelLibrary.Storage to,
                   List<Path> otherReferences, boolean move) {}
    record Result(ModelLibrary.Choice choice, String cleanupNotice) {}

    static Path artifact(Path path, Engine engine) {
        if (!Files.isRegularFile(path) || path.getParent() == null)
            return path;
        var name = path.getFileName().toString();
        var parent = path.getParent();
        if (engine == Engine.INSTANSEG && ("instanseg.pt".equalsIgnoreCase(name) || "weights.pt".equalsIgnoreCase(name))
                && Files.isRegularFile(parent.resolve("instanseg.pt")) && Files.isRegularFile(parent.resolve("weights.pt")))
            return parent;
        if (engine == Engine.STARDIST_QP && "model.onnx".equalsIgnoreCase(name)
                && Files.isRegularFile(parent.resolve("config.json")) && Files.isRegularFile(parent.resolve("weights_best.pt")))
            return parent;
        return path;
    }

    static boolean canMove(Path source, ModelLibrary.Storage from) {
        if (from == null || source == null || !Files.exists(source))
            return false;
        try {
            var root = from.assets().toRealPath();
            var resolved = source.toRealPath();
            return !source.equals(from.assets()) && !resolved.equals(root) && resolved.startsWith(root)
                    && !Files.isSymbolicLink(source);
        } catch (IOException e) {
            return false;
        }
    }

    static Result run(Request request, BooleanSupplier cancelled) throws IOException {
        var choice = request.choice();
        var record = choice.entry();
        var src = request.source().toRealPath();
        var from = request.from();
        var to = request.to();
        if (request.move() && (record.origin() != ModelLibrary.Origin.TRAINED || !canMove(request.source(), from)))
            throw new IOException("Only trained assets stored in this library can be moved. Use Copy to keep a local copy.");
        if (request.move() && request.otherReferences().stream().anyMatch(path -> overlaps(src, path)))
            throw new IOException("Another model entry uses these files. Copy the model or remove the other entry first.");
        if (!Files.isDirectory(to.relativeBase()) && to.scope() == ModelLibrary.Scope.SHARED)
            throw new IOException("The shared library folder is unavailable.");
        if (from != null && from.registry().equals(to.registry()))
            throw new IOException("Choose a different library.");
        if (to.assets().toAbsolutePath().normalize().startsWith(src))
            throw new IOException("Choose a library outside the model folder.");
        Files.createDirectories(to.assets());
        if (to.assets().toRealPath().startsWith(src))
            throw new IOException("Choose a library outside the model folder.");
        var id = ModelLibrary.newId();
        var slug = record.name().replaceAll("[^A-Za-z0-9._-]+", "-");
        slug = slug.substring(0, Math.min(55, slug.length()));
        var folder = to.assets().resolve("model-" + slug + "-" + id);
        var staging = Files.createTempDirectory(to.assets(), ".ci-dl-transfer-");
        var payload = folder.resolve(src.getFileName().toString());
        var copied = record.relocated(id, to.store(payload));
        Path quarantine = null;
        boolean movedFolder = false;
        boolean registered = false;
        boolean finished = false;
        try {
            copy(src, staging.resolve(src.getFileName().toString()), request.move(), cancelled);
            check(cancelled);
            Files.move(staging, folder);
            movedFolder = true;
            if (request.move()) {
                quarantine = src.resolveSibling(".ci-dl-moved-" + id);
                Files.move(src, quarantine);
            }
            ModelLibrary.change(to.registry(), id, before -> copied);
            registered = true;
            if (request.move()) {
                ModelLibrary.change(from.registry(), record.id(), previous -> {
                    if (!Objects.equals(previous, record))
                        throw new IllegalStateException("The source model changed. Refresh the library and try again.");
                    return null;
                });
            }
            finished = true;
        } catch (IOException | RuntimeException failure) {
            if (quarantine != null && Files.exists(quarantine)) {
                try {
                    Files.move(quarantine, src);
                } catch (IOException e) {
                    failure.addSuppressed(e);
                }
            }
            if (registered) {
                try {
                    ModelLibrary.change(to.registry(), id, before -> null);
                    registered = false;
                } catch (IOException | RuntimeException e) {
                    failure.addSuppressed(e);
                }
            }
            if (!registered) {
                try {
                    deleteTree(movedFolder ? folder : staging);
                } catch (IOException e) {
                    failure.addSuppressed(e);
                }
            }
            throw failure;
        }
        String notice = "";
        if (finished && quarantine != null) {
            try {
                deleteTree(quarantine);
                // A model previously transferred by CI DL may have a now-empty container.
                var parent = src.getParent();
                if (parent != null && parent.getParent() != null && parent.getParent().equals(from.assets().toRealPath())
                        && parent.getFileName().toString().startsWith("model-")) {
                    try (var entries = Files.list(parent)) {
                        if (entries.findAny().isPresent())
                            parent = null;
                    }
                    if (parent != null)
                        Files.deleteIfExists(parent);
                }
            } catch (IOException e) {
                notice = "Moved. A temporary folder could not be cleaned up: " + quarantine;
            }
        }
        return new Result(new ModelLibrary.Choice(copied, to.scope()), notice);
    }

    private static boolean overlaps(Path source, Path other) {
        try {
            var path = other.toRealPath();
            return path.startsWith(source) || source.startsWith(path);
        } catch (IOException e) {
            return false;
        }
    }

    private static void copy(Path src, Path dest, boolean verify, BooleanSupplier cancelled) throws IOException {
        try (var paths = Files.walk(src)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                check(cancelled);
                var path = iterator.next();
                var target = dest.resolve(src.relativize(path));
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectory(target);
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.copy(path, target);
                    if (Files.size(path) != Files.size(target) || verify && Files.mismatch(path, target) != -1)
                        throw new IOException("Could not verify the copied file " + path.getFileName());
                } else {
                    throw new IOException("Model folders must contain regular files and directories: " + path);
                }
            }
        }
    }

    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean())
            throw new CancellationException();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS))
            return;
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        }
    }
}


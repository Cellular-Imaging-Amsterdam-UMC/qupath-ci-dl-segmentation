package qupath.ext.cidl;

import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.RectangleROI;
import qupath.lib.roi.interfaces.ROI;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

final class Training {
    record Mark(String classification, ROI roi) {
        boolean isRegion() {
            return classification.equals("Training") || classification.equals("Validation");
        }
    }

    record Source(ProjectImageEntry<BufferedImage> entry, ImageData<BufferedImage> open, List<Mark> marks) {}
    record Settings(Engine engine, String python, String model, String device, Channels channels, double downsample,
                    Path output, int epochs, double learningRate, int batchSize, double diameter, int minimumMasks,
                    int crop, double sigma, int mean, double gaussian) {}

    static List<Mark> snapshot(Collection<? extends PathObject> objects) {
        return objects.stream().filter(PathObject::hasROI)
                .map(object -> new Mark(object.getPathClass() == null ? "" : object.getPathClass().toString(),
                        object.getROI())).toList();
    }

    static void run(Job job, List<Source> sources, Settings s) throws Exception {
        if (Files.exists(s.output()))
            throw new IllegalArgumentException("A model already exists at " + s.output() + ". Choose a new model name.");
        var patches = new ArrayList<PatchIO.Patch>();
        var work = Files.createTempDirectory("qupath-ci-train-");
        try {
            for (var source : sources) {
                job.check();
                var name = source.entry().getImageName();
                job.log("Reading annotations in " + name + "…");
                if (source.open() == null) {
                    var marks = snapshot(source.entry().readHierarchy().getAnnotationObjects());
                    if (marks.stream().noneMatch(Mark::isRegion)) {
                        job.log("No Training or Validation rectangles in " + name + ".");
                        continue;
                    }
                    try (var data = source.entry().readImageData()) {
                        export(job, data, marks, name, work, patches, s);
                    }
                } else {
                    export(job, source.open(), source.marks(), name, work, patches, s);
                }
            }
            long train = patches.stream().filter(p -> p.split().equals("training")).count();
            if (train == 0 || train == patches.size())
                throw new IllegalArgumentException("The selected images must contain both Training and Validation rectangles.");
            job.log("Exported " + train + " training and " + (patches.size() - train) + " validation patch(es).");
            Files.createDirectories(s.output().getParent());
            var staging = Files.createTempDirectory(s.output().getParent(), ".ci-dl-training-");
            try {
                var config = new LinkedHashMap<String, Object>();
                config.put("task", "train");
                config.put("engine", s.engine().id);
                config.put("model", s.model());
                config.put("device", s.device());
                config.put("patches", patches);
                var artifact = staging.resolve("artifact");
                config.put("output", artifact.toString());
                config.put("name", s.output().getFileName().toString());
                config.put("epochs", s.epochs());
                config.put("learning_rate", s.learningRate());
                config.put("batch_size", s.batchSize());
                config.put("diameter", s.diameter());
                config.put("minimum_masks", s.minimumMasks());
                config.put("crop", s.crop());
                config.put("sigma", s.sigma());
                config.put("mean", s.mean());
                config.put("gaussian", s.gaussian());
                job.python(s.python(), work, config);
                job.apply(() -> {
                    try {
                        validateArtifact(s.engine(), artifact);
                        Files.move(artifact, s.output());
                        job.log("Model saved to " + s.output());
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
            } finally {
                Job.cleanup(staging, job::log);
            }
        } finally {
            Job.cleanup(work, job::log);
        }
    }

    private static void validateArtifact(Engine engine, Path artifact) throws java.io.IOException {
        if (engine == Engine.CELLPOSE && Files.isRegularFile(artifact) && Files.size(artifact) > 0)
            return;
        if (engine == Engine.SPOTIFLOW && Files.isRegularFile(artifact.resolve("config.yaml"))
                && Files.isRegularFile(artifact.resolve("best.pt")) && Files.isRegularFile(artifact.resolve("thresholds.yaml")))
            return;
        throw new java.io.IOException("Python finished without producing the expected model files.");
    }

    private static void export(Job job, ImageData<BufferedImage> data, List<Mark> marks, String name, Path work,
                               List<PatchIO.Patch> patches, Settings s) throws Exception {
        var regions = marks.stream().filter(Mark::isRegion).toList();
        if (regions.isEmpty()) {
            job.log("No Training or Validation rectangles in " + name + ".");
            return;
        }
        var server = data.getServer();
        List<Integer> channels;
        try {
            channels = s.channels().resolve(server);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " · " + e.getMessage(), e);
        }
        var annotations = marks.stream().filter(mark -> !mark.isRegion() && !mark.roi().isEmpty()).map(Mark::roi).toList();
        for (var mark : regions) {
            job.check();
            var region = mark.roi();
            var label = name + " · " + mark.classification() + " rectangle at ("
                    + Math.round(region.getBoundsX()) + ", " + Math.round(region.getBoundsY()) + ")";
            if (!(region instanceof RectangleROI) || region.isEmpty())
                throw new IllegalArgumentException(label + " must be a non-empty QuPath rectangle annotation.");
            if (region.getBoundsX() < 0 || region.getBoundsY() < 0
                    || region.getBoundsX() + region.getBoundsWidth() > server.getWidth()
                    || region.getBoundsY() + region.getBoundsHeight() > server.getHeight())
                throw new IllegalArgumentException(label + " must lie fully inside the image.");
            if (mark.classification().equals("Training") && regions.stream().anyMatch(other ->
                    other.classification().equals("Validation") && samePlane(region, other.roi()) && overlaps(region, other.roi())))
                throw new IllegalArgumentException(label + " overlaps a Validation rectangle on the same plane.");

            var request = PatchIO.request(server, region, s.downsample());
            int index = patches.size() + 1;
            var patch = PatchIO.export(job, server, request, channels, work.resolve(index + ".image"));
            var targets = work.resolve(index + ".labels");
            String split = mark.classification().toLowerCase(Locale.ROOT);
            int count;
            if (s.engine() == Engine.CELLPOSE) {
                count = exportMasks(job, region, request, patch, annotations, targets, label);
                int min = split.equals("training") ? s.minimumMasks() : 1;
                if (count < min)
                    throw new IllegalArgumentException(label + " contains " + count + " usable instance(s). At least "
                            + min + " required.");
            } else {
                count = exportSpots(region, request, patch, annotations, targets);
                if (count == 0)
                    job.log(label + " is a background-only patch.");
            }
            patches.add(patch.target(targets, split));
            job.log("Patch " + index + " · " + split + " · "
                    + patch.width() + " × " + patch.height() + " px · " + count + " label(s)");
        }
    }

    private static boolean overlaps(ROI a, ROI b) {
        return a.getBoundsX() < b.getBoundsX() + b.getBoundsWidth()
                && b.getBoundsX() < a.getBoundsX() + a.getBoundsWidth()
                && a.getBoundsY() < b.getBoundsY() + b.getBoundsHeight()
                && b.getBoundsY() < a.getBoundsY() + a.getBoundsHeight();
    }

    private static boolean samePlane(ROI a, ROI b) {
        return a.getZ() == b.getZ() && a.getT() == b.getT();
    }

    private static int exportMasks(Job job, ROI region, RegionRequest request, PatchIO.Patch patch,
                                   List<ROI> annotations, Path output, String label) throws Exception {
        int width = patch.width();
        int height = patch.height();
        var labels = new int[width * height];
        double ds = request.getDownsample();
        var transform = AffineTransform.getScaleInstance(1 / ds, 1 / ds);
        transform.translate(-request.getX(), -request.getY());
        var rectangle = transform.createTransformedShape(region.getShape());
        int count = 0;
        for (var annotation : annotations) {
            job.check();
            if (!annotation.isArea() || !samePlane(region, annotation)
                    || !region.contains(annotation.getCentroidX(), annotation.getCentroidY()))
                continue;
            var shape = transform.createTransformedShape(annotation.getShape());
            var bounds = shape.getBounds().intersection(rectangle.getBounds());
            boolean retained = false;
            for (int y = Math.max(0, bounds.y); y < Math.min(height, bounds.y + bounds.height); y++) {
                job.check();
                for (int x = Math.max(0, bounds.x); x < Math.min(width, bounds.x + bounds.width); x++) {
                    if (!shape.contains(x + 0.5, y + 0.5) || !rectangle.contains(x + 0.5, y + 0.5))
                        continue;
                    int offset = y * width + x;
                    if (labels[offset] != 0)
                        throw new IllegalArgumentException(label + " contains overlapping instance annotations.");
                    labels[offset] = count + 1;
                    retained = true;
                }
            }
            if (retained)
                count++;
        }
        PatchIO.writeMask(job, output, width, height, labels);
        return count;
    }

    static int exportSpots(ROI region, RegionRequest request, PatchIO.Patch patch,
                                   List<ROI> annotations, Path output) throws Exception {
        var points = new java.util.LinkedHashSet<String>();
        double ds = request.getDownsample();
        for (var annotation : annotations) {
            if (!samePlane(region, annotation))
                continue;
            if (annotation.isPoint()) {
                for (var point : annotation.getAllPoints())
                    addSpot(points, region, request, patch, ds, point.getX(), point.getY());
            } else if (annotation.isArea()) {
                addSpot(points, region, request, patch, ds, annotation.getCentroidX(), annotation.getCentroidY());
            }
        }
        Files.write(output, points);
        return points.size();
    }

    private static void addSpot(java.util.Set<String> points, ROI region, RegionRequest request, PatchIO.Patch patch,
                                double ds, double x, double y) {
        if (!region.contains(x, y))
            return;
        double px = (x - request.getX()) / ds - 0.5;
        double py = (y - request.getY()) / ds - 0.5;
        if (px >= -0.5 && py >= -0.5 && px < patch.width() - 0.5 && py < patch.height() - 0.5)
            points.add(Math.max(0, py) + "\t" + Math.max(0, px));
    }
}

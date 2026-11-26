package qupath.ext.cidl;

import org.locationtech.jts.geom.Geometry;
import qupath.lib.analysis.features.ObjectMeasurements;
import qupath.lib.analysis.images.ContourTracing;
import qupath.lib.analysis.images.SimpleImages;
import qupath.lib.images.ImageData;
import qupath.lib.objects.CellTools;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;
import qupath.opencv.ops.ImageOp;
import qupath.opencv.ops.ImageOps;

import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Prediction {
    private static final String SPOT_SOURCE = "ci.dl.spotiflow";

    record Region(PathObject parent, ROI roi) {}
    record Settings(Engine engine, String python, String model, String device, Channels channels,
                    double downsample, double diameter, Double threshold, double flowThreshold,
                    int minDistance, double spotRadius, double expansion, int mean, double gaussian,
                    boolean annotations, boolean excludeBorder, boolean measurements, boolean replaceSpots,
                    Double nms, boolean cells, double pixelSize) {}
    private record Export(PatchIO.Patch patch, RegionRequest request, Path output) {}

    static void run(Job job, ImageData<BufferedImage> image, List<Region> regions, Settings s) throws Exception {
        var results = compute(job, image, regions, s);
        job.apply(() -> {
            var hierarchy = image.getHierarchy();
            for (var region : regions) {
                var parent = region.parent();
                if (!region.roi().equals(parent.getROI()) || parent.getLevel() < 1
                        || root(parent) != hierarchy.getRootObject())
                    throw new IllegalStateException("A selected prediction region was edited or removed. Run prediction again.");
            }
            int total = 0;
            for (int i = 0; i < regions.size(); i++) {
                var parent = regions.get(i).parent();
                var objects = results.get(i);
                if (s.engine() == Engine.SPOTIFLOW && s.replaceSpots()) {
                    var existing = parent.getChildObjects().stream()
                            .filter(object -> "true".equals(object.getMetadata().get(SPOT_SOURCE))).toList();
                    hierarchy.removeObjects(existing, true);
                }
                for (var object : objects)
                    hierarchy.addObjectBelowParent(parent, object, false);
                if (s.engine() == Engine.SPOTIFLOW) {
                    var count = parent.getChildObjects().stream().filter(PathObject::isDetection)
                            .filter(object -> PathClass.fromString("Foci").equals(object.getPathClass())).count();
                    parent.getMeasurementList().put("Foci: Count", count);
                    parent.getMeasurementList().close();
                }
                total += objects.size();
            }
            if (total > 0 || s.engine() == Engine.SPOTIFLOW)
                hierarchy.fireHierarchyChangedEvent(Prediction.class);
            job.log("Added " + total + " object(s).");
        });
    }

    static List<List<PathObject>> compute(Job job, ImageData<BufferedImage> image, List<Region> regions, Settings s)
            throws Exception {
        var channels = s.channels().resolve(image.getServer());
        if (s.engine() == Engine.STARDIST_QP)
            return starDist(job, image, regions, s, channels.getFirst());
        return runPython(job, image, regions, s, channels);
    }

    static List<Region> selectedRegions(ImageData<BufferedImage> data) {
        return data.getHierarchy().getSelectionModel().getSelectedObjects().stream()
                .filter(object -> (object.isAnnotation() || object.isTMACore()) && object.hasROI()
                        && object.getROI().isArea() && !object.getROI().isEmpty())
                .map(object -> new Region(object, object.getROI())).toList();
    }

    private static PathObject root(PathObject object) {
        while (object.getParent() != null)
            object = object.getParent();
        return object;
    }

    private static List<List<PathObject>> runPython(Job job, ImageData<BufferedImage> image,
            List<Region> regions, Settings s, List<Integer> channels) throws Exception {
        var work = Files.createTempDirectory("qupath-ci-predict-");
        try {
            var exports = new ArrayList<Export>();
            for (var region : regions) {
                job.check();
                int index = exports.size() + 1;
                job.log("Exporting region " + index + " of " + regions.size() + "…");
                var request = PatchIO.request(image.getServer(), region.roi(), s.downsample());
                var patch = PatchIO.export(job, image.getServer(), request, channels, work.resolve(index + ".image"));
                exports.add(new Export(patch, request, work.resolve(index + ".prediction")));
            }
            var config = new LinkedHashMap<String, Object>();
            config.put("task", "predict");
            config.put("engine", s.engine().id);
            config.put("model", s.model());
            config.put("device", s.device());
            config.put("diameter", s.diameter());
            config.put("threshold", s.threshold());
            config.put("flow_threshold", s.flowThreshold());
            config.put("min_distance", s.minDistance());
            config.put("exclude_border", s.excludeBorder());
            config.put("mean", s.mean());
            config.put("gaussian", s.gaussian());
            config.put("nms", s.nms());
            config.put("cells", s.cells());
            config.put("pixel_size", s.pixelSize() * s.downsample());
            config.put("patches", exports.stream().map(e -> e.patch().target(e.output(), "prediction")).toList());
            job.python(s.python(), work, config);
            var results = new ArrayList<List<PathObject>>();
            for (int i = 0; i < regions.size(); i++) {
                job.check();
                var export = exports.get(i);
                var roi = regions.get(i).roi();
                results.add(s.engine() == Engine.SPOTIFLOW ? readSpots(export.output(), export.request(), roi, s.spotRadius())
                        : readMasks(job, export, roi, image, s));
            }
            return results;
        } finally {
            Job.cleanup(work, job::log);
        }
    }

    private static List<PathObject> readMasks(Job job, Export e, ROI region, ImageData<BufferedImage> data,
                                              Settings s) throws Exception {
        int width = e.patch().width();
        int height = e.patch().height();
        var labels = PatchIO.readLabels(job, e.output(), width, height);
        var rois = ContourTracing.createROIs(SimpleImages.createFloatImage(labels, width, height), e.request(), 1, -1);
        var parentGeometry = region.getGeometry();
        var server = data.getServer();
        var calibration = server.getPixelCalibration();
        var objects = new ArrayList<PathObject>();
        var pathClass = segmentationClass(s);
        for (var roi : rois.values()) {
            job.check();
            if (s.excludeBorder() && touchesImage(roi, data))
                continue;
            var geometry = clip(roi.getGeometry(), parentGeometry);
            if (geometry.getArea() == 0)
                continue;
            var nucleus = GeometryTools.geometryToROI(geometry, region.getImagePlane());
            if (s.annotations()) {
                objects.add(PathObjects.createAnnotationObject(nucleus, pathClass));
            } else if (s.expansion() > 0) {
                var cell = CellTools.estimateCellBoundary(geometry, s.expansion(), 1.5);
                var cellRoi = GeometryTools.geometryToROI(clip(cell, parentGeometry), region.getImagePlane());
                objects.add(PathObjects.createCellObject(cellRoi, nucleus, pathClass, null));
            } else {
                objects.add(PathObjects.createDetectionObject(nucleus, pathClass));
            }
        }
        List<PathObject> result = s.expansion() > 0 && !s.annotations() && !objects.isEmpty()
                ? CellTools.constrainCellOverlaps(objects) : objects;
        if (s.measurements()) {
            var measurements = java.util.EnumSet.allOf(ObjectMeasurements.Measurements.class);
            for (var object : result) {
                job.check();
                ObjectMeasurements.addShapeMeasurements(object, calibration);
                var compartments = java.util.EnumSet.of(ObjectMeasurements.Compartments.NUCLEUS,
                        ObjectMeasurements.Compartments.CYTOPLASM, ObjectMeasurements.Compartments.CELL);
                ObjectMeasurements.addIntensityMeasurements(server, object, s.downsample(), measurements, compartments);
            }
        }
        return result;
    }

    private static Geometry clip(Geometry geometry, Geometry parent) {
        return parent.covers(geometry) ? geometry : geometry.intersection(parent);
    }

    static List<PathObject> readSpots(Path output, RegionRequest request, ROI region, double radius) throws java.io.IOException {
        var result = new ArrayList<PathObject>();
        double ds = request.getDownsample();
        var pathClass = PathClass.fromString("Foci");
        try (var lines = Files.newBufferedReader(output)) {
            for (String line; (line = lines.readLine()) != null; ) {
                var fields = line.split("\t");
                if (fields.length != 3)
                    throw new IllegalArgumentException("Spotiflow returned an invalid spot row.");
                double x = request.getX() + (Double.parseDouble(fields[1]) + 0.5) * ds;
                double y = request.getY() + (Double.parseDouble(fields[0]) + 0.5) * ds;
                double probability = Double.parseDouble(fields[2]);
                if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(probability)
                        || !region.contains(x, y))
                    continue;
                var ellipse = ROIs.createEllipseROI(x - radius, y - radius, radius * 2, radius * 2, region.getImagePlane());
                var spot = PathObjects.createDetectionObject(ellipse, pathClass);
                spot.getMetadata().put(SPOT_SOURCE, "true");
                spot.getMeasurementList().put("Spotiflow: Probability", probability);
                spot.getMeasurementList().close();
                result.add(spot);
            }
        }
        return result;
    }

    private static List<List<PathObject>> starDist(Job job, ImageData<BufferedImage> image,
                                                   List<Region> regions, Settings s, int channel) throws Exception {
        Class<?> type;
        try {
            type = Class.forName("qupath.ext.stardist.StarDist2D", true,
                    CiDeepLearningExtension.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Install the QuPath StarDist extension in the Extension Manager.");
        }
        var builder = invoke(type.getMethod("builder", String.class), null, s.model());
        Class<?> b = builder.getClass();
        double size = image.getServer().getPixelCalibration().getAveragedPixelSize().doubleValue();
        invoke(b.getMethod("channels", int[].class), builder, new int[]{channel});
        invoke(b.getMethod("normalizePercentiles", double.class, double.class), builder, 1.0, 99.0);
        invoke(b.getMethod("threshold", double.class), builder, s.threshold());
        if (s.model().toLowerCase(java.util.Locale.ROOT).endsWith(".onnx"))
            invoke(b.getMethod("layout", String.class), builder, "BCHW");
        invoke(b.getMethod("pixelSize", double.class), builder, size * s.downsample());
        invoke(b.getMethod("includeProbability", boolean.class), builder, true);
        invoke(b.getMethod("classify", PathClass.class), builder, segmentationClass(s));
        var filters = new ArrayList<ImageOp>();
        if (s.mean() > 0)
            filters.add(ImageOps.Filters.mean(s.mean()));
        if (s.gaussian() > 0)
            filters.add(ImageOps.Filters.gaussianBlur(s.gaussian()));
        if (!filters.isEmpty())
            invoke(b.getMethod("preprocess", ImageOp[].class), builder, (Object) filters.toArray(ImageOp[]::new));
        if (s.annotations())
            invoke(b.getMethod("createAnnotations"), builder);
        else if (s.expansion() > 0)
            invoke(b.getMethod("cellExpansion", double.class), builder, s.expansion() * size);
        if (s.measurements()) {
            invoke(b.getMethod("measureShape"), builder);
            invoke(b.getMethod("measureIntensity"), builder);
        }
        var model = invoke(b.getMethod("build"), builder);
        var detect = type.getMethod("detectObjects", ImageData.class, ROI.class);
        var results = new ArrayList<List<PathObject>>();
        try {
            for (var region : regions) {
                job.check();
                job.log("StarDist region " + (results.size() + 1) + " of " + regions.size() + "…");
                var objects = (List<?>) invoke(detect, model, image, region.roi());
                results.add(objects.stream().map(PathObject.class::cast)
                        .filter(object -> !s.excludeBorder() || !touchesImage(object.getROI(), image)).toList());
            }
        } finally {
            if (model instanceof AutoCloseable closeable)
                closeable.close();
        }
        return results;
    }

    private static Object invoke(java.lang.reflect.Method method, Object receiver, Object... args) throws Exception {
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception exception)
                throw exception;
            if (e.getCause() instanceof Error error)
                throw error;
            throw e;
        }
    }

    private static PathClass segmentationClass(Settings s) {
        return PathClass.fromString((s.expansion() > 0 && !s.annotations()) || s.cells() ? "Cells" : "Nuclei");
    }

    private static boolean touchesImage(ROI roi, ImageData<BufferedImage> data) {
        return roi.getBoundsX() <= 0 || roi.getBoundsY() <= 0
                || roi.getBoundsX() + roi.getBoundsWidth() >= data.getServer().getWidth() - 1
                || roi.getBoundsY() + roi.getBoundsHeight() >= data.getServer().getHeight() - 1;
    }
}

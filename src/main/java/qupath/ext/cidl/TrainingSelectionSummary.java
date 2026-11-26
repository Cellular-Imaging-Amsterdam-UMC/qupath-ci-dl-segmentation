package qupath.ext.cidl;

import javafx.application.Platform;
import javafx.geometry.VPos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.roi.RectangleROI;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

final class TrainingSelectionSummary {
    record Counts(long training, long validation) {
        Counts plus(Counts other) {
            return new Counts(training + other.training, validation + other.validation);
        }
    }

    private static final Counts ZERO = new Counts(0, 0);
    private static final PathClass TRAINING = PathClass.fromString("Training");
    private static final PathClass VALIDATION = PathClass.fromString("Validation");
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(TrainingSelectionSummary.class);

    private final Label selectedImages = metric(null);
    private final Label trainingRectangles = metric("training-key");
    private final Label validationRectangles = metric("validation-key");
    private final FlowPane summary = new FlowPane(6, 4, selectedImages, trainingRectangles, validationRectangles);
    private final Tooltip tooltip = new Tooltip();
    private final Set<PathObjectHierarchy> observed = Collections.newSetFromMap(new IdentityHashMap<>());
    private final AtomicBoolean redrawQueued = new AtomicBoolean();
    private final PathObjectHierarchyListener listener = event -> {
        if (!event.isChanging() && !event.isObjectMeasurementEvent() && redrawQueued.compareAndSet(false, true)) {
            Platform.runLater(() -> {
                redrawQueued.set(false);
                render();
            });
        }
    };

    private List<PathObjectHierarchy> live = List.of();
    private List<ProjectImageEntry<BufferedImage>> closed = List.of();
    private Counts saved = ZERO;
    private List<String> failed = List.of();
    private Thread reader;
    private int version;
    private int images;

    TrainingSelectionSummary() {
        summary.setId("training-selection-summary");
        summary.getStyleClass().add("training-summary");
        summary.setRowValignment(VPos.CENTER);
        Tooltip.install(summary, tooltip);
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(420);
        render();
    }

    FlowPane content() {
        return summary;
    }

    private static Label metric(String color) {
        var label = new Label();
        label.getStyleClass().add("training-metric");
        if (color != null) {
            var swatch = new Region();
            swatch.getStyleClass().addAll("training-metric-swatch", color);
            label.setGraphic(swatch);
        }
        return label;
    }

    void update(List<ProjectImageEntry<BufferedImage>> selected,
                Map<ProjectImageEntry<BufferedImage>, ImageData<BufferedImage>> open) {
        images = selected.size();
        var current = new ArrayList<PathObjectHierarchy>();
        var toRead = new ArrayList<ProjectImageEntry<BufferedImage>>();
        for (var entry : selected) {
            var data = open.get(entry);
            if (data == null)
                toRead.add(entry);
            else
                current.add(data.getHierarchy());
        }
        live = List.copyOf(current);
        observe(current);
        if (!toRead.equals(closed)) {
            closed = List.copyOf(toRead);
            readClosed();
        }
        render();
    }

    void clear() {
        observe(List.of());
        live = List.of();
        closed = List.of();
        saved = ZERO;
        failed = List.of();
        images = 0;
        cancelRead();
    }

    private void observe(List<PathObjectHierarchy> hierarchies) {
        observed.removeIf(hierarchy -> {
            if (hierarchies.contains(hierarchy))
                return false;
            hierarchy.removeListener(listener);
            return true;
        });
        for (var hierarchy : hierarchies) {
            if (observed.add(hierarchy))
                hierarchy.addListener(listener);
        }
    }

    private void cancelRead() {
        version++;
        if (reader != null) {
            reader.interrupt();
            reader = null;
        }
    }

    private void readClosed() {
        cancelRead();
        saved = ZERO;
        failed = List.of();
        if (closed.isEmpty())
            return;
        var entries = closed;
        int request = version;
        reader = Thread.ofVirtual().name("CI DL training rectangle counts").start(() -> {
            var total = ZERO;
            var unavailable = new ArrayList<String>();
            for (var entry : entries) {
                if (Thread.currentThread().isInterrupted())
                    return;
                try {
                    if (entry.hasImageData())
                        total = total.plus(count(entry.readHierarchy()));
                } catch (IOException | RuntimeException e) {
                    unavailable.add(entry.getImageName());
                    LOGGER.debug("Could not count saved training rectangles for {}", entry.getImageName(), e);
                }
            }
            var result = total;
            Platform.runLater(() -> {
                if (request == version) {
                    reader = null;
                    saved = result;
                    failed = List.copyOf(unavailable);
                    render();
                }
            });
        });
    }

    private void render() {
        selectedImages.setText(images + (images == 1 ? " image selected" : " images selected"));
        String details = "Totals across the selected images. Open images use current annotations. "
                + "Closed images use the saved project annotations.";
        if (reader != null) {
            trainingRectangles.setText("Training rectangles …");
            validationRectangles.setText("Validation rectangles …");
            details = "Counting saved rectangles.\n" + details;
        } else {
            var total = saved;
            for (var hierarchy : live)
                total = total.plus(count(hierarchy));
            var atLeast = failed.isEmpty() ? "" : "≥";
            trainingRectangles.setText(tally(atLeast, total.training(), "Training"));
            validationRectangles.setText(tally(atLeast, total.validation(), "Validation"));
            if (!failed.isEmpty())
                details += "\nSaved annotations unavailable for " + String.join(", ", failed) + ".";
        }
        tooltip.setText(details);
    }

    private static String tally(String prefix, long count, String type) {
        return prefix + count + " " + type + (count == 1 ? " rectangle" : " rectangles");
    }

    static Counts count(PathObjectHierarchy hierarchy) {
        if (hierarchy == null)
            return ZERO;
        long training = 0;
        long validation = 0;
        for (var rectangle : hierarchy.getAnnotationObjects()) {
            if (!isRectangle(rectangle))
                continue;
            if (TRAINING.equals(rectangle.getPathClass()))
                training++;
            else
                validation++;
        }
        return new Counts(training, validation);
    }

    static List<PathObject> rectangles(PathObjectHierarchy hierarchy) {
        return hierarchy.getAnnotationObjects().stream()
                .filter(TrainingSelectionSummary::isRectangle)
                .toList();
    }

    static boolean isRectangle(PathObject object) {
        return object.isAnnotation() && object.getROI() instanceof RectangleROI rectangle && !rectangle.isEmpty()
                && (TRAINING.equals(object.getPathClass()) || VALIDATION.equals(object.getPathClass()));
    }
}

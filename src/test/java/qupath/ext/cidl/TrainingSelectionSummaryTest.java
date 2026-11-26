package qupath.ext.cidl;

import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrainingSelectionSummaryTest {
    @Test
    void countsOnlyClassifiedRectanglesAcrossImages() {
        var plane = ImagePlane.getDefaultPlane();
        var training = PathClass.fromString("Training");
        var validation = PathClass.fromString("Validation");
        var first = new PathObjectHierarchy();
        var region = PathObjects.createAnnotationObject(ROIs.createRectangleROI(5, 5, 20, 20, plane), training);
        region.setLocked(true);
        first.addObjects(List.of(region,
                PathObjects.createAnnotationObject(ROIs.createEllipseROI(5, 5, 8, 8, plane), validation),
                PathObjects.createAnnotationObject(ROIs.createRectangleROI(10, 10, 8, 8, plane))));
        var second = new PathObjectHierarchy();
        second.addObjects(List.of(
                PathObjects.createAnnotationObject(ROIs.createRectangleROI(10, 30, 20, 20, plane), training),
                PathObjects.createAnnotationObject(ROIs.createRectangleROI(60, 60, 15, 15, plane), validation)));
        assertEquals(new TrainingSelectionSummary.Counts(2, 1),
                TrainingSelectionSummary.count(first).plus(TrainingSelectionSummary.count(second)));
    }
}

package qupath.ext.cidl;

import org.junit.jupiter.api.Test;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RectanglePlacementTest {
    @Test
    void centersTheRectangleAndKeepsItInsideTheImage() {
        var plane = ImagePlane.getPlane(1, 2);
        var center = Training.placeRectangle(1000, 750, 400, 300, 256, plane, List.of());
        assertEquals(400, center.getCentroidX());
        assertEquals(300, center.getCentroidY());
        assertEquals(plane, center.getImagePlane());

        var edge = Training.placeRectangle(1000, 750, 980, 15, 512, plane, List.of());
        assertEquals(488, edge.getBoundsX());
        assertEquals(0, edge.getBoundsY());
        assertEquals(512, edge.getBoundsWidth());
        assertEquals(512, edge.getBoundsHeight());
    }

    @Test
    void avoidsExistingRegionsOnTheActivePlane() {
        var plane = ImagePlane.getDefaultPlane();
        var offPlane = ROIs.createRectangleROI(0, 0, 768, 400, ImagePlane.getPlane(1, 0));
        var existing = ROIs.createRectangleROI(256, 72, 256, 256, plane);
        var other = ROIs.createEllipseROI(0, 0, 768, 400, plane);
        var marks = List.of(new Training.Mark("Validation", offPlane), new Training.Mark("Training", existing),
                new Training.Mark("Cell", other));

        var added = Training.placeRectangle(768, 400, 384, 200, 256, plane, marks);
        assertEquals(200, added.getCentroidY());
        assertEquals(256, Math.abs(added.getCentroidX() - existing.getCentroidX()));
        assertEquals(256, added.getBoundsWidth());
        assertTrue(added.getBoundsX() == 0 || added.getBoundsX() == 512);
    }

    @Test
    void reportsWhenTheRequestedSizeCannotFit() {
        var plane = ImagePlane.getDefaultPlane();
        var wholeImage = ROIs.createRectangleROI(0, 0, 300, 300, plane);
        var training = new Training.Mark("Training", wholeImage);
        assertThrows(IllegalArgumentException.class,
                () -> Training.placeRectangle(300, 300, 150, 150, 256, plane, List.of(training)));
        assertThrows(IllegalArgumentException.class,
                () -> Training.placeRectangle(200, 300, 100, 150, 256, plane, List.of()));
    }
}

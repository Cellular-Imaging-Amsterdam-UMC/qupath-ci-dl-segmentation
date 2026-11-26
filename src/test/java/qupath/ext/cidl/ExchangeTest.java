package qupath.ext.cidl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.stream.DoubleStream;

import static org.junit.jupiter.api.Assertions.*;

class ExchangeTest {
    @TempDir
    Path work;

    @Test
    void roundTripsLittleEndianInstanceLabels() throws Exception {
        var job = new Job(line -> {});
        var path = work.resolve("mask");
        PatchIO.writeMask(job, path, 2, 2, new int[]{0, 1, 258, 65_537});
        assertArrayEquals(new byte[]{0, 0, 0, 0, 1, 0, 0, 0, 2, 1, 0, 0, 1, 0, 1, 0}, Files.readAllBytes(path));
        assertArrayEquals(new float[]{0, 1, 258, 65_537}, PatchIO.readLabels(job, path, 2, 2));
    }

    @Test
    void rejectsTruncatedInstanceMask() throws Exception {
        var path = Files.write(work.resolve("mask"), new byte[12]);
        assertThrows(java.io.IOException.class, () -> PatchIO.readLabels(new Job(line -> {}), path, 2, 2));
    }

    @Test
    void cancelledJobStopsExport() {
        var job = new Job(line -> {});
        job.cancel();
        assertThrows(CancellationException.class, () -> PatchIO.writeMask(job, work.resolve("mask"), 1, 1, new int[]{1}));
    }

    @Test
    void importsSpotiflowRowsAsYXAndClipsToTheParent() throws Exception {
        var plane = qupath.lib.regions.ImagePlane.getPlane(1, 2);
        var region = qupath.lib.roi.ROIs.createRectangleROI(100, 220, 90, 40, plane);
        var request = qupath.lib.regions.RegionRequest.createInstance("test", 2, region);
        var spots = Files.writeString(work.resolve("spots"), "4\t23\t0.9\n80\t8\t0.8\n");
        var objects = Prediction.readSpots(spots, request, region, 2);
        assertEquals(1, objects.size());
        var object = objects.getFirst();
        assertEquals(147, object.getROI().getCentroidX());
        assertEquals(229, object.getROI().getCentroidY());
        assertEquals(plane, object.getROI().getImagePlane());
        assertEquals(0.9, object.getMeasurementList().get("Spotiflow: Probability"), 1e-6);
    }

    @Test
    void chartKeepsPaddingForNearlyConstantLoss() {
        var warmup = LossChart.range(DoubleStream.of(0.65, 0.66), true);
        var recent = LossChart.range(DoubleStream.of(0.65, 0.66), false);
        assertEquals(0, warmup.lower());
        assertTrue(recent.lower() > 0.55);
        assertTrue(recent.lower() < 0.65);
        assertTrue(recent.upper() > 0.66);
        assertTrue(recent.upper() - recent.lower() >= 0.05);
    }
}

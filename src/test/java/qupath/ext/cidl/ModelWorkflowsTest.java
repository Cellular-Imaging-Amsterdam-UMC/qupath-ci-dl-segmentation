package qupath.ext.cidl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelWorkflowsTest {
    @Test
    void scoresOnlyMatchesOnTheSamePlane() {
        var plane = ImagePlane.getDefaultPlane();
        var truth = ROIs.createRectangleROI(10, 10, 20, 20, plane);
        var anotherPlane = ROIs.createRectangleROI(10, 10, 20, 20, ImagePlane.getPlane(1, 0));
        var extra = ROIs.createRectangleROI(40, 40, 10, 10, plane);
        var scores = ModelComparison.score(new Job(line -> {}), List.of(truth, anotherPlane), List.of(truth, extra));
        assertEquals(0.5, scores.f1());
        assertEquals(1, scores.missed());
        assertEquals(1, scores.extra());
        assertEquals(0, scores.split());
        assertEquals(0, scores.merged());
    }

    @Test
    void trainingRegistersAProjectRelativeModelAndChannelMapping(@TempDir Path directory) throws Exception {
        var channels = new Channels(new Channels.Choice(0, "DAPI"), null);
        var settings = new Training.Settings(Engine.CELLPOSE, "", "", "auto", channels,
                2, directory.resolve("models").resolve("test"), 1, 0.005, 1, 0, 1, 224, 0, 0, 0, 0);
        ModelLibrary.trained(directory.resolve("project.qpproj"), settings);
        var path = directory.resolve("models").resolve("ci-dl-library.json");
        var loaded = new com.google.gson.Gson().fromJson(Files.readString(path), ModelLibrary.Entry[].class);
        assertEquals(1, loaded.length);
        assertEquals("models/test", loaded[0].location());
        assertEquals(ModelLibrary.Origin.TRAINED, loaded[0].origin());
        assertEquals(channels, loaded[0].options().channels());
        assertEquals(2.0, loaded[0].options().downsample());
    }
}


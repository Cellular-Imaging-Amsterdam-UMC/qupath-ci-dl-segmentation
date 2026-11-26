package qupath.ext.cidl;

import qupath.lib.images.servers.ColorTransforms;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntToDoubleFunction;

final class PatchIO {
    private static final long MAX_PIXELS = 16_777_216L;

    record Patch(String image, String target, String split, int width, int height, int channels) {
        Patch target(Path path, String split) {
            return new Patch(image, path.toString(), split, width, height, channels);
        }
    }

    static RegionRequest request(ImageServer<BufferedImage> server, ROI roi, double downsample) {
        var request = RegionRequest.createInstance(server.getPath(), downsample, roi)
                .intersect2D(0, 0, server.getWidth(), server.getHeight());
        var pixels = Math.ceil(request.getWidth() / downsample) * Math.ceil(request.getHeight() / downsample);
        if (request.getWidth() <= 0 || request.getHeight() <= 0 || pixels > MAX_PIXELS)
            throw new IllegalArgumentException("A region is empty or exceeds 16.8 megapixels at this downsample. "
                    + "Use smaller regions or increase downsample.");
        return request;
    }

    static Patch export(Job job, ImageServer<BufferedImage> server, RegionRequest request,
                        List<Integer> channels, Path file) throws IOException {
        job.check();
        var image = server.readRegion(request);
        int width = image.getWidth();
        int height = image.getHeight();
        if (width < 2 || height < 2)
            throw new IOException("A region is too small at this downsample.");
        var bands = new float[channels.size()][];
        for (int i = 0; i < bands.length; i++)
            bands[i] = ColorTransforms.createChannelExtractor(channels.get(i)).extractChannel(server, image, null);
        write(job, file, width * channels.size(), height, true, index -> {
            int band = index % channels.size();
            return bands[band][index / channels.size()];
        });
        return new Patch(file.toString(), null, null, width, height, channels.size());
    }

    static void writeMask(Job job, Path path, int width, int height, int[] labels) throws IOException {
        write(job, path, width, height, false, index -> labels[index]);
    }

    private static void write(Job job, Path file, int rowLength, int rows, boolean floats,
                              IntToDoubleFunction values) throws IOException {
        var buffer = ByteBuffer.allocate(rowLength * 4).order(ByteOrder.LITTLE_ENDIAN);
        try (var output = Files.newOutputStream(file)) {
            for (int y = 0; y < rows; y++) {
                job.check();
                buffer.clear();
                for (int x = 0; x < rowLength; x++) {
                    var value = values.applyAsDouble(y * rowLength + x);
                    if (floats)
                        buffer.putFloat((float) value);
                    else
                        buffer.putInt((int) value);
                }
                output.write(buffer.array());
            }
        }
    }

    static float[] readLabels(Job job, Path path, int width, int height) throws IOException {
        if (Files.size(path) != 4L * width * height)
            throw new IOException("Cellpose returned an instance mask with an unexpected size.");
        var labels = new float[width * height];
        var row = new byte[4 * width];
        var buffer = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN);
        try (InputStream input = Files.newInputStream(path)) {
            for (int y = 0; y < height; y++) {
                job.check();
                if (input.readNBytes(row, 0, row.length) != row.length)
                    throw new IOException("Incomplete instance mask.");
                buffer.rewind();
                for (int x = 0; x < width; x++)
                    labels[y * width + x] = buffer.getInt();
            }
        }
        return labels;
    }
}

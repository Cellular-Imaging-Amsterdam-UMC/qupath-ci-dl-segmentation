package qupath.ext.cidl;

import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.DoubleStream;

final class LossChart {
    private final NumberAxis x = new NumberAxis();
    private final NumberAxis y = new NumberAxis(0, 1, 0.2);
    private final LineChart<Number, Number> chart = new LineChart<>(x, y);
    private final XYChart.Series<Number, Number> training = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> validation = new XYChart.Series<>();
    private final TreeMap<Integer, XYChart.Data<Number, Number>> trainPoints = new TreeMap<>();
    private final TreeMap<Integer, XYChart.Data<Number, Number>> valPoints = new TreeMap<>();
    private final CheckBox fitAll = new CheckBox("Fit all epochs");
    private int lastFitEpoch;
    private int lastEpoch;

    record Range(double lower, double upper) {}

    LossChart() {
        x.setLabel("Epoch");
        x.setForceZeroInRange(false);
        x.setMinorTickVisible(false);
        x.setAutoRanging(false);
        y.setLabel("Loss");
        y.setMinorTickVisible(false);
        chart.setAnimated(false);
        chart.setLegendVisible(false);
        chart.setMinHeight(135);
        chart.setPrefHeight(220);
        chart.getStyleClass().add("loss-chart");
        training.setName("Training");
        validation.setName("Validation");
        chart.getData().add(training);
        chart.getData().add(validation);
        fitAll.setSelected(false);
        fitAll.setOnAction(event -> refit(true));
        clear();
    }

    VBox content() {
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var legend = new HBox(12, key("Training", "training-key"), key("Validation", "validation-key"), spacer, fitAll);
        legend.setAlignment(Pos.CENTER_LEFT);
        var card = Workspace.card("Training & validation loss", chart, legend);
        VBox.setVgrow(chart, Priority.ALWAYS);
        return card;
    }

    private static HBox key(String title, String color) {
        var swatch = new Region();
        swatch.getStyleClass().addAll("loss-key", color);
        var key = new HBox(5, swatch, new Label(title));
        key.setAlignment(Pos.CENTER_LEFT);
        return key;
    }

    void clear() {
        trainPoints.clear();
        valPoints.clear();
        training.getData().clear();
        validation.getData().clear();
        lastFitEpoch = 0;
        lastEpoch = 0;
        refit(true);
    }

    boolean accept(String line) {
        if (!line.startsWith("CI_LOSS\t"))
            return false;
        var fields = line.split("\t");
        if (fields.length != 4)
            return false;
        try {
            int epoch = Integer.parseInt(fields[1]);
            if (epoch < 1)
                return false;
            Double train = parse(fields[2]);
            Double valid = parse(fields[3]);
            if (train != null)
                put(trainPoints, training, epoch, train);
            if (valid != null)
                put(valPoints, validation, epoch, valid);
            if (train != null || valid != null) {
                lastEpoch = Math.max(lastEpoch, epoch);
                refit(false);
            }
        } catch (NumberFormatException e) {
            return false;
        }
        return true;
    }

    void finish() {
        refit(true);
    }

    private static Double parse(String value) {
        if (value.equals("-"))
            return null;
        double parsed = Double.parseDouble(value);
        return Double.isFinite(parsed) && parsed >= 0 ? parsed : null;
    }

    private static void put(TreeMap<Integer, XYChart.Data<Number, Number>> points,
                            XYChart.Series<Number, Number> series, int epoch, double loss) {
        var point = points.get(epoch);
        if (point != null) {
            point.setYValue(loss);
        } else {
            var data = new XYChart.Data<Number, Number>(epoch, loss);
            points.put(epoch, data);
            series.getData().add(points.headMap(epoch).size(), data);
        }
    }

    private void refit(boolean force) {
        x.setLowerBound(0);
        x.setUpperBound(Math.max(5, lastEpoch + 1));
        x.setTickUnit(Math.max(1, Math.ceil(lastEpoch / 6.0)));
        if (!force && lastEpoch >= 10 && lastEpoch - lastFitEpoch < 5
                && !outsideRange(trainPoints.get(lastEpoch)) && !outsideRange(valPoints.get(lastEpoch)))
            return;
        lastFitEpoch = lastEpoch;
        int start = fitAll.isSelected() || lastEpoch < 10 ? 1 : Math.max(1, lastEpoch - 24);
        var values = new ArrayList<Double>();
        for (var series : List.of(trainPoints, valPoints)) {
            var visible = series.tailMap(start).values();
            if (visible.isEmpty() && !series.isEmpty())
                values.add(series.lastEntry().getValue().getYValue().doubleValue());
            else
                visible.forEach(point -> values.add(point.getYValue().doubleValue()));
        }
        var range = range(values.stream().mapToDouble(Double::doubleValue), lastEpoch < 10);
        double desired = (range.upper() - range.lower()) / 4;
        double scale = Math.pow(10, Math.floor(Math.log10(desired)));
        double normalized = desired / scale;
        double tick = (normalized < 1.5 ? 1 : normalized < 3 ? 2 : normalized < 7 ? 5 : 10) * scale;
        y.setLowerBound(Math.floor(range.lower() / tick) * tick);
        y.setUpperBound(Math.ceil(range.upper() / tick) * tick);
        y.setTickUnit(tick);
    }

    private boolean outsideRange(XYChart.Data<Number, Number> point) {
        if (point == null)
            return false;
        var value = point.getYValue().doubleValue();
        return value < y.getLowerBound() || value > y.getUpperBound();
    }

    static Range range(DoubleStream values, boolean includeZero) {
        var stats = values.summaryStatistics();
        if (stats.getCount() == 0)
            return new Range(0, 1);
        var min = includeZero ? 0 : stats.getMin();
        var max = stats.getMax();
        double pad = Math.max((max - min) * 0.12, Math.max(0.025, max * 0.05));
        double lower = Math.max(0, min - pad);
        return new Range(lower, Math.max(max + pad, lower + 0.05));
    }
}

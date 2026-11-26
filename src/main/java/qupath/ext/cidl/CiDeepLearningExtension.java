package qupath.ext.cidl;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

public final class CiDeepLearningExtension implements QuPathExtension {
    private Workspace prediction;
    private Workspace training;
    private boolean installed;

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (installed)
            return;
        installed = true;
        var pythonPaths = new java.util.EnumMap<Engine, javafx.beans.property.StringProperty>(Engine.class);
        for (var engine : Engine.values())
            pythonPaths.put(engine, ModelForm.pythonPreference(engine));
        var library = new ModelLibrary(qupath);
        var microSam = new MicroSamTool(qupath, ModelForm.pythonPreference("microsam"));
        var menu = new Menu("CI Deep Learning Segmentation");
        menu.setGraphic(WindowIcon.view(16));
        var predict = new MenuItem("Prediction…");
        var train = new MenuItem("Training…");
        predict.setOnAction(event -> {
            if (prediction == null)
                prediction = new Workspace(qupath, false, pythonPaths, library, microSam);
            prediction.show();
        });
        train.setOnAction(event -> {
            if (training == null)
                training = new Workspace(qupath, true, pythonPaths, library, microSam);
            training.show();
        });
        var models = new MenuItem("Model Manager…");
        models.setOnAction(event -> library.show(qupath.getStage()));
        var prompt = new MenuItem("µSAM annotation…");
        prompt.setOnAction(event -> microSam.show());
        menu.getItems().addAll(predict, train, models, prompt);
        qupath.getMenu("Extensions", true).getItems().add(menu);
    }

    @Override
    public String getName() {
        return "CI Deep Learning Segmentation";
    }

    @Override
    public String getDescription() {
        return "Predict, compare and train segmentation models, and annotate interactively with µSAM.";
    }

    @Override
    public Version getQuPathVersion() {
        return Version.parse("0.7.0");
    }
}

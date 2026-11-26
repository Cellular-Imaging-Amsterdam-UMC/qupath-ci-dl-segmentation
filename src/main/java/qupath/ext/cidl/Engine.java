package qupath.ext.cidl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

enum Engine {
    CELLPOSE("Cellpose 3.x", "cellpose", "CELLPOSE3_LEGACY_LOCAL_MODELS_PATH"),
    CELLPOSE_SAM("Cellpose-SAM 4+", "cellpose-sam", "CELLPOSE_LOCAL_MODELS_PATH"),
    STARDIST("Stardist", "stardist", "CI_DL_STARDIST_MODELS"),
    STARDIST_QP("Stardist-QP", "stardist-qp", "STARDIST_LOCAL_MODELS_PATH"),
    INSTANSEG("InstanSeg", "instanseg", "CI_DL_INSTANSEG_MODELS"),
    SPOTIFLOW("Spotiflow", "spotiflow", "SPOTIFLOW_LOCAL_MODELS_PATH");

    final String title;
    final String id;
    final String modelEnvironment;

    Engine(String title, String id, String modelEnvironment) {
        this.title = title;
        this.id = id;
        this.modelEnvironment = modelEnvironment;
    }

    boolean isCellpose() {
        return this == CELLPOSE || this == CELLPOSE_SAM;
    }

    boolean usesSecondaryChannel() {
        return isCellpose() || this == INSTANSEG;
    }

    boolean usesThirdChannel() {
        return this == CELLPOSE_SAM || this == INSTANSEG;
    }

    boolean usesPython(boolean training) {
        return training || this != STARDIST_QP;
    }

    boolean browsesFolders(boolean training) {
        return this == SPOTIFLOW || this == STARDIST || this == STARDIST_QP && training
                || this == INSTANSEG && !training;
    }

    List<String> modelPresets(boolean training) {
        return switch (this) {
            case CELLPOSE -> List.of("cyto3", "nuclei", "transformer_cp3");
            case CELLPOSE_SAM -> List.of("cpsam_v2", "cpsam", "cpdino", "cpdino-vitb");
            case STARDIST -> List.of("SD_Nuclei_Versatile", "SD_Foci_Finn", "SD_Foci_Aggregates");
            case STARDIST_QP -> training ? STARDIST.modelPresets(true)
                    : List.of("dsb2018_heavy_augment.pb", "he_heavy_augment.pb");
            case INSTANSEG -> training ? List.of()
                    : List.of("single_channel_nuclei", "fluorescence_nuclei_and_cells", "brightfield_nuclei");
            case SPOTIFLOW -> List.of("general", "hybiss", "fluo_live", "synth_complex");
        };
    }

    String defaultModel(boolean training) {
        if (training && this != CELLPOSE_SAM)
            return "";
        return modelChoice(modelPresets(training).getFirst(), training);
    }

    String modelChoice(String name, boolean training) {
        var folder = modelDirectory(training);
        if (folder == null)
            return name;
        var path = folder.resolve(name);
        if (Files.exists(path))
            return path.toString();
        if (this == SPOTIFLOW && Files.isDirectory(folder.resolve("models").resolve(name)))
            return folder.resolve("models").resolve(name).toString();
        if (this == CELLPOSE && name.equals("nuclei") && Files.isRegularFile(folder.resolve("nucleitorch_0")))
            return folder.resolve("nucleitorch_0").toString();
        return name;
    }

    Path modelDirectory(boolean training) {
        if (this == STARDIST_QP && training)
            return STARDIST.modelDirectory(true);
        String configured = System.getenv(modelEnvironment);
        if (configured == null && this == CELLPOSE)
            configured = System.getenv("CELLPOSE_LOCAL_MODELS_PATH");
        if (configured != null && !configured.isBlank())
            return Path.of(configured);
        String base = System.getenv("QUPATH_BASE");
        if (base == null || base.isBlank())
            return null;
        return Path.of(base).resolve(switch (this) {
            case CELLPOSE -> "models/cellpose3";
            case CELLPOSE_SAM -> "models/cellpose-sam";
            case STARDIST -> "models/stardist";
            case STARDIST_QP -> "stardist_models";
            case INSTANSEG -> "models/instanseg";
            case SPOTIFLOW -> "spotiflow_models";
        });
    }
}

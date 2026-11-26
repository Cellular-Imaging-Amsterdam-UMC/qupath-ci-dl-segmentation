package qupath.ext.cidl;

enum Engine {
    CELLPOSE("Cellpose 3.x", "cellpose", "CELLPOSE_LOCAL_MODELS_PATH"),
    STARDIST("StarDist", "stardist", "STARDIST_LOCAL_MODELS_PATH"),
    SPOTIFLOW("Spotiflow", "spotiflow", "SPOTIFLOW_LOCAL_MODELS_PATH");

    final String title;
    final String id;
    final String modelEnvironment;

    Engine(String title, String id, String modelEnvironment) {
        this.title = title;
        this.id = id;
        this.modelEnvironment = modelEnvironment;
    }

    String defaultModel() {
        String dir = System.getenv(modelEnvironment);
        return switch (this) {
            case CELLPOSE -> localModel(dir, "cyto3", "cyto3");
            case STARDIST -> localModel(dir, "dsb2018_heavy_augment.pb", "");
            case SPOTIFLOW -> localModel(dir, "models/general", "general");
        };
    }

    private static String localModel(String dir, String name, String fallback) {
        if (dir != null && !dir.isBlank()) {
            var path = java.nio.file.Path.of(dir).resolve(name);
            if (java.nio.file.Files.exists(path))
                return path.toString();
        }
        return fallback;
    }
}

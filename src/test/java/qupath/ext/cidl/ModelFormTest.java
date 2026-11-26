package qupath.ext.cidl;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelFormTest {
    @Test
    void displaysModelNamesWithoutTheInstallationPath() {
        var modelRoot = Path.of("very-long-installation-directory", "portable-models");
        assertEquals("SD_Nuclei_Versatile", ModelForm.modelDisplayName(modelRoot.resolve("SD_Nuclei_Versatile").toString()));
        assertEquals("cpsam_v2", ModelForm.modelDisplayName("cpsam_v2"));
        assertEquals("trained-nuclei / model.onnx",
                ModelForm.modelDisplayName(modelRoot.resolve("trained-nuclei").resolve("model.onnx").toString()));
    }
}

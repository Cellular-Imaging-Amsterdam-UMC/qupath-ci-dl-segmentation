# CI Deep Learning Segmentation for QuPath 0.7

A Java extension with two English-language workspaces under **Extensions → CI Deep Learning Segmentation**. The layout follows the [CI Pixel Classifier](https://github.com/Cellular-Imaging-Amsterdam-UMC/qupath-ci-pixel-classifier). The workflows build on the [QuPath Day 3 lessons](https://github.com/Cellular-Imaging-Amsterdam-UMC/qp-lessons/tree/main/Day3).

| Workspace | Tabs |
| --- | --- |
| Prediction | Cellpose 3.x, StarDist, Spotiflow |
| Training | Cellpose 3.x, Spotiflow |

## Install

1. Open QuPath 0.7 and install the extension JAR from [Releases](https://github.com/Cellular-Imaging-Amsterdam-UMC/qupath-ci-dl-segmentation/releases). Restart QuPath.
2. For Cellpose and Spotiflow, prepare Python environments and select their Python executables in **Advanced settings** on the corresponding tabs. The executable is remembered for each engine across both windows. If left blank, the extension uses `python` from QuPath's environment.
3. For StarDist, install the [QuPath StarDist extension](https://github.com/qupath/qupath-extension-stardist) from QuPath's Extension Manager and choose a compatible local model, such as [`dsb2018_heavy_augment.pb`](https://github.com/qupath/models/tree/main/stardist).

The JAR includes the Python backend script. QuPath handles region export, annotation labels, and import of predictions directly. Cellpose and Spotiflow run through their Python packages, with no BIOP extension required.

A starting point for a Python 3.11 or 3.12 environment with PyTorch is:

```sh
python -m pip install "cellpose>=3,<4" "spotiflow>=0.6.5,<0.7"
```

The workflows were tested with Cellpose 3.1.1.3 and Spotiflow 0.6.5. Choose **Auto**, **CPU**, or **CUDA** in **Advanced settings**. CUDA requires a compatible PyTorch installation. Each engine can use a separate environment.

### Portable installations

The extension recognizes `CELLPOSE_LOCAL_MODELS_PATH`, `STARDIST_LOCAL_MODELS_PATH`, and `SPOTIFLOW_LOCAL_MODELS_PATH` for the lesson model folders. Spotiflow also honors its own `SPOTIFLOW_CACHE_DIR`. Set `CI_DL_CELLPOSE_PYTHON` or `CI_DL_SPOTIFLOW_PYTHON` before launching QuPath to supply an initial Python path. A portable install with `QUPATH_BASE` can automatically pick up `PythonEnvs/qupathenv/python.exe`.

## Prediction

1. Open an image. Select one or more area annotations or TMA cores in the viewer.
2. Open **Prediction**, choose a model tab, then set the model, channel, and downsample. Cellpose supports a built-in model name such as `cyto3` or a model file. Spotiflow supports a built-in 2D model name such as `general` or a model folder.
3. Click **Run prediction**. Watch the log or click **Stop** to cancel. Completed results are added under the original regions.

Cellpose and StarDist produce nucleus detections, expanded cells, or annotations depending on the selected settings. Spotiflow creates `Foci` detections with a probability measurement and adds `Foci: Count` to each parent. Cellpose and StarDist append results on rerun. Spotiflow can replace its own previous detections under the same parents.

Downsample applies to the XY resolution in the selected plane. A downsample of 2 uses half the native width and height. Model thresholds, peak distances, and diameters use the model input resolution. Cell expansion and spot-marker radius use the original image pixel coordinates. Cellpose and Spotiflow process each selected region's bounding crop and accept up to 16.8 megapixels per crop at the chosen downsample. Use smaller annotations or increase downsample for larger crops. StarDist uses the QuPath engine's tile processing.

## Training

1. In a QuPath project, draw rectangle annotations and assign the classes **Training** and **Validation**. Use **Add region classes** in the training window if needed.
2. Label the objects in those rectangles. For Cellpose, draw one area annotation per instance. For Spotiflow, use point annotations (each point is a spot) or use area annotations (each centroid is a spot). Keep training and validation rectangles separate on the same image plane.
3. Use **Choose images** to select the exact project images to include. Open images contribute their current annotations. Closed images contribute their saved annotations. Channel names are matched across selected images. Both splits are required.
4. Open the Cellpose or Spotiflow tab. Enter a new model name and choose a base model to fine-tune, or leave it empty to train from scratch. Set the channel and training parameters, then click **Start training**.

The extension saves successful models under `models/` in the project folder. Cellpose produces a weights file. Spotiflow produces a folder containing the model configuration, weights, and thresholds. Pick this file or folder from the corresponding Prediction tab.

The log streams Python output. Spotiflow reports training and validation loss each epoch. Cellpose reports both on its native validation schedule during training and fills in per-epoch training loss at completion when the installed Cellpose version returns it. The chart starts with a zero baseline, then gradually fits the recent range with padding. Enable **Fit all epochs** to bring earlier values back into view.

Use the chevron in the Training header to collapse or expand the workspace. Training continues in the compact view, with the run status and Stop button visible.

## Build

Requires Java 25. Run `./gradlew build` on macOS or Linux, or `.\gradlew.bat build` on Windows. The extension JAR is written to `build/libs/`.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE) and [THIRD-PARTY-NOTICES.txt](THIRD-PARTY-NOTICES.txt).

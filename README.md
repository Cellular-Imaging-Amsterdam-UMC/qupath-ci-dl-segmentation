# CI Deep Learning Segmentation for QuPath 0.7

A Java extension with two English-language workspaces under **Extensions → CI Deep Learning Segmentation**. The layout follows the [CI Pixel Classifier](https://github.com/Cellular-Imaging-Amsterdam-UMC/qupath-ci-pixel-classifier). The workflows build on the [QuPath Day 3 lessons](https://github.com/Cellular-Imaging-Amsterdam-UMC/qp-lessons/tree/main/Day3).

| Workspace | Tabs |
| --- | --- |
| Prediction | Cellpose 3.x, Cellpose-SAM 4+, StarDist, StarDist-QP, InstanSeg, Spotiflow, CellSAM |
| Training | Cellpose 3.x, Cellpose-SAM 4+, StarDist, StarDist-QP, InstanSeg, Spotiflow |

## Install

1. Open QuPath 0.7 and install the extension JAR from [Releases](https://github.com/Cellular-Imaging-Amsterdam-UMC/qupath-ci-dl-segmentation/releases). Restart QuPath.
2. For the Python backends, prepare an environment and select their Python executables in **Advanced settings** on the corresponding tabs. The executable is remembered for each engine across both windows. If left blank, the extension uses `python` from QuPath's environment.
3. For StarDist-QP prediction, install the [QuPath StarDist extension](https://github.com/qupath/qupath-extension-stardist) from QuPath's Extension Manager and choose a compatible local model, such as [`dsb2018_heavy_augment.pb`](https://github.com/qupath/models/tree/main/stardist).

The JAR includes the Python backend script. QuPath handles region export, annotation labels, and import of predictions directly. Cellpose and Spotiflow run through their Python packages.

A starting point for a Python 3.11 or 3.12 environment with PyTorch is:

```sh
python -m pip install "cellpose==4.2.1.1" "spotiflow==0.6.5" "cistardist-pytorch==0.4.1" "stardist==0.9.2" onnx "instanseg-torch==0.1.1" seaborn
python -m pip install "cellpose3_legacy @ git+https://github.com/Cellular-Imaging-Amsterdam-UMC/cellpose3_legacy.git@5789ad61c99a9e2fc8ea0a404f92d6ea1a6a9cd1"
python -m pip install "dinov3 @ git+https://github.com/facebookresearch/dinov3.git@6876159a11b4df116f30f667f8c9888617df0751"
```

Cellpose 3 and 4 can share this environment through cellpose3_legacy. The Cellpose 3 tab also accepts a separate environment with Cellpose 3.x installed directly. Choose **Auto**, **CPU**, or **CUDA** in **Advanced settings**. CUDA requires a compatible PyTorch installation. Each engine can use a separate environment.

### Portable installations

The extension recognizes `CELLPOSE_LOCAL_MODELS_PATH`, `STARDIST_LOCAL_MODELS_PATH`, and `SPOTIFLOW_LOCAL_MODELS_PATH` for the lesson model folders. Spotiflow also honors its own `SPOTIFLOW_CACHE_DIR`. Set `CI_DL_CELLPOSE_PYTHON` or `CI_DL_SPOTIFLOW_PYTHON` before launching QuPath to supply an initial Python path. A portable install with `QUPATH_BASE` can automatically pick up `PythonEnvs/qupathenv/python.exe`.

## Prediction

Choose a model with **Browse…** or **Presets**. In Prediction and Training, the model label shows a short name. Hover over it to see the full selection. Models that support training from scratch offer that option in **Presets**.

1. Open an image. Select one or more area annotations or TMA cores in the viewer.
2. Open **Prediction**, choose a model tab, then set the model, channel, and downsample. Cellpose supports a built-in model name such as `cyto3` or a model file. Spotiflow supports a built-in 2D model name such as `general` or a model folder.
3. Click **Run prediction**. Watch the log or click **Stop** to cancel. Completed results are added under the original regions.

Cellpose and StarDist produce nucleus detections, expanded cells, or annotations depending on the selected settings. Spotiflow creates `Foci` detections with a probability measurement and adds `Foci: Count` to each parent. Cellpose and StarDist append results on rerun. Spotiflow can replace its own previous detections under the same parents.

Downsample applies to the XY resolution in the selected plane. A downsample of 2 uses half the native width and height. Model thresholds, peak distances, and diameters use the model input resolution. Cell expansion and spot-marker radius use the original image pixel coordinates. Cellpose and Spotiflow process each selected region's bounding crop and accept up to 16.8 megapixels per crop at the chosen downsample. Use smaller annotations or increase downsample for larger crops. StarDist-QP uses the QuPath engine's tile processing.

## Training

1. Open a project image and use **Add Training Rectangle** or **Add Validation Rectangle**. The extension places an unlocked rectangle near the current view on the active plane and avoids overlapping existing Training or Validation regions. Drag the rectangle into position with the QuPath Move tool. The default is 256 × 256 image pixels. Change **New rectangle size** on the active model tab to use a different preset and check its dimensions at the current downsample.
2. Use the padlock button to lock all Training and Validation rectangles in the active image before labeling. A closed padlock means all rectangles are locked. An open padlock means one or more are unlocked, or the active image has no rectangles. Click the closed padlock to unlock them together. For one rectangle, select it and use **Objects → Lock… → Toggle selected objects lock**. You can also draw and classify rectangles manually.
3. Label the objects in those rectangles. For Cellpose, draw one area annotation per instance. For Spotiflow, use point annotations (each point is a spot) or use area annotations (each centroid is a spot). Keep training and validation rectangles separate on the same image plane.
4. In the **Images** row, use **Add current open image** or **Choose images…** to select at least one project image. The channel selectors use the current selected image or the first selected image. Channel names are matched across the selected images. The totals above **Images** and **Annotate** include current annotations for open images and saved annotations for closed images. Both splits are required.
5. Open the Cellpose or Spotiflow tab. Enter a new model name and choose a base model to fine-tune, or leave it empty to train from scratch. Set the channel and training parameters, then click **Start training**.

The extension saves successful models under `models/` in the project folder. Cellpose produces a weights file. Spotiflow produces a folder containing the model configuration, weights, and thresholds. Pick this file or folder from the corresponding Prediction tab.

The log streams Python output. Spotiflow reports training and validation loss each epoch. Cellpose reports both on its native validation schedule during training and fills in per-epoch training loss at completion when the installed Cellpose version returns it. The chart starts with a zero baseline, then gradually fits the recent range with padding. Enable **Fit all epochs** to bring earlier values back into view.

Use the chevron in the Training header to collapse or expand the workspace. The training toolbar, selected image and rectangle totals, run status, and Stop button stay visible in the compact view.

### New model formats and training options

- **Cellpose-SAM 4+** fine-tunes a pretrained checkpoint. Choose `cpsam_v2` or `cpsam` for SAM-ViT-L, or `cpdino` or `cpdino-vitb` for DINOv3. Compatible custom Cellpose 4 weights can also be used. SAM runs at 256 × 256 model pixels. DINO defaults to 384 × 384 and supports other compatible crop sizes. Training starts with a learning rate of 1e-5 and batch size one. [Cellpose model documentation](https://cellpose.readthedocs.io/en/latest/models.html)
- **StarDist** trains or fine-tunes a single-channel PyTorch model. **StarDist-QP** uses the same PyTorch workflow and additionally saves `model.onnx` for the QuPath Prediction tab. Use a local StarDist folder with weights, configuration and thresholds for PyTorch prediction.
- **InstanSeg** predicts nuclei or cells with a local TorchScript model that provides the chosen output. Training produces a single instance target using one to three selected channels. Select `weights.pt` from a previous CI DL InstanSeg run to fine-tune it. Use `instanseg.pt` or its containing model folder for prediction. Enter a pixel size override in Advanced settings when needed.
- Portable launchers can set `CELLPOSE3_LEGACY_LOCAL_MODELS_PATH`, `CELLPOSE_LOCAL_MODELS_PATH` for Cellpose 4, `CI_DL_STARDIST_MODELS` for PyTorch, `STARDIST_LOCAL_MODELS_PATH` for QuPath, and `CI_DL_INSTANSEG_MODELS` to expose local presets.

## Model Manager

Open **Extensions → CI Deep Learning Segmentation → Model Manager**, or open it from a method's **Presets** menu. Select a method on the left, filter by library, or search model names, versions, notes and preset names. Use **Import model…** to copy a checkpoint or model folder into a library, or register its existing location. Model details include source, version, notes, favorite status and saved presets.

- **Project** travels with the QuPath project when models are stored in its model folder. CI DL registers successful training runs here.
- **My library** is available across projects in the current QuPath user profile.
- **Shared** uses a folder selected with **Shared folder…**. Choose a team folder and use **Copy to → Shared** to publish the model files and its presets into that folder. Colleagues can select the same location in their own Model Manager.
- **Move to** transfers a trained model owned by its source library. QuPath verifies the destination copy before removing the original. If another library entry points to the same files, use **Copy to** or remove the other reference first.

Select a model and use **New preset…** to create named Prediction or Training settings for that method, including channel selection, resolution and the relevant inference or learning parameters. Use **Presets → Save as preset…** to capture the active tab's settings. Apply a saved preset from the manager or the method's **Presets** menu. The channel pickers show the colors from the image metadata. For a shared project, open a representative image before setting the channel mapping.

## Compare models

1. Open a test image and select one or more area annotations or TMA cores. For scoring, label the reference instances **Ground truth** inside fully labeled test regions.
2. Choose a pretrained or trained model on a Prediction tab and click **Set A** in **Compare models**. Choose the other model and click **Set B**. The two slots retain their own model and prediction settings.
3. Click **Compare**. Drag the divider in the viewer or move the **A/B** slider to see A in teal and B in orange. You can also show both contours together or show only one model. **Clear preview** removes the temporary contours.
4. Review object counts and runtime. For regions with reference annotations, scoring reports object F1 from single matches at IoU ≥ 0.5, missed and extra detections, and split and merge counts. Use test images held out from training.

## µSAM annotation and CellSAM

Install extra backends in the Python environment that QuPath uses:

```sh
python -m pip install "micro-sam==1.8.14" "cellSAM @ git+https://github.com/vanvalenlab/cellSAM.git@362a8e521bcd7313d5cb280bcd4cfc12977c1e9f"
```

- **µSAM quick annotation.** Enable **µSAM** from the Training toolbar or open **µSAM annotation…** from the extension menu. Alt-click inside an object to add its outline directly as an editable QuPath area annotation. Each click starts a separate annotation on the active image plane. Choose an image channel or RGB. Use **Presets** to choose the light microscopy ViT-B or ViT-Tiny model. The portable bundle includes both checkpoints. **Browse…** opens other compatible µSAM checkpoints. When enabled, µSAM loads the model and prepares the area at the center of the viewer. Channel changes prepare the new input with the model already loaded. µSAM can reuse the most recent image-area embeddings when returning to a previous channel. Change the Python executable under **Advanced settings**.
- **CellSAM.** The Prediction tab accepts `cellsam_general`, `cellsam_extra`, or a local CellSAM checkpoint through **Browse…**. Download the official 1.2 archive from [Van Valen Lab on Hugging Face](https://huggingface.co/vanvalenlab/cellsam) after accepting the model terms. Extract `cellsam_general.pt` and `cellsam_extra.pt` for offline prediction. In the portable bundle, place them in `models/cellsam/` to pick them up from Presets. [CellSAM backend and usage](https://github.com/vanvalenlab/cellSAM)

ViT-Tiny requires [MobileSAM](https://github.com/ChaoningZhang/MobileSAM). Install it in the same Python environment with `python -m pip install "mobile-sam @ git+https://github.com/ChaoningZhang/MobileSAM.git@f706ad9c4eb7f219c00d9050e46328518ffb65d2"`. The light microscopy weights are listed in the [µSAM documentation](https://computational-cell-analytics.github.io/micro-sam/).

## Build

Requires Java 25. Run `./gradlew build` on macOS or Linux, or `.\gradlew.bat build` on Windows. The extension JAR is written to `build/libs/`.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE) and [THIRD-PARTY-NOTICES.txt](THIRD-PARTY-NOTICES.txt).

"""Run the model APIs for a single QuPath job."""
from collections import OrderedDict
import importlib
import inspect
import json
import logging
import math
import re
import sys
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path

import numpy as np


def report_loss(epoch, train=None, validation=None):
    def value(number):
        return "-" if number is None or not math.isfinite(float(number)) else f"{float(number):.9g}"

    # Lightning may leave a progress bar line open on stdout.
    print(f"\nCI_LOSS\t{epoch}\t{value(train)}\t{value(validation)}", flush=True)


def load_image(patch, config):
    data = np.fromfile(patch["image"], dtype="<f4")
    data = data.reshape(patch["height"], patch["width"], patch["channels"])
    if not np.isfinite(data).all():
        raise ValueError(f"Image patch {Path(patch['image']).name} contains non-finite intensities.")
    if config.get("mean") or config.get("gaussian"):
        from scipy.ndimage import gaussian_filter, uniform_filter

        if config.get("mean"):
            size = 2 * config["mean"] + 1
            data = uniform_filter(data, size=(size, size, 1))
        if config.get("gaussian"):
            sigma = config["gaussian"]
            data = gaussian_filter(data, sigma=(sigma, sigma, 0))
    return data


def select_device(requested):
    import torch

    if requested == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("CUDA was requested, but it is unavailable in this Python environment.")
    if requested != "auto":
        return requested
    if torch.cuda.is_available():
        return "cuda"
    if torch.backends.mps.is_available():
        return "mps"
    return "cpu"


def cellpose_api(sam):
    if sam:
        installed = version("cellpose")
        if int(installed.split(".", 1)[0]) < 4:
            raise RuntimeError(f"Cellpose 4 or newer is required. This environment has Cellpose {installed}.")
        return "cellpose", installed
    try:
        return "cellpose3_legacy", version("cellpose3_legacy")
    except PackageNotFoundError:
        installed = version("cellpose")
        if installed.split(".", 1)[0] != "3":
            raise RuntimeError("Install cellpose3_legacy to use Cellpose 3 alongside Cellpose 4.")
        return "cellpose", installed


def cellpose_model(config, device, module, installed, sam):
    import torch

    models = importlib.import_module(f"{module}.models")
    name = config["model"]
    opts = {"gpu": device == "cuda", "device": torch.device(device)}
    if sam:
        if not name:
            raise ValueError("Choose a pretrained SAM or DINO checkpoint for Cellpose 4 fine-tuning.")
        if not Path(name).is_file() and name not in models.MODEL_NAMES:
            raise ValueError(f"Unknown Cellpose 4 model or checkpoint: {name}")
        opts["pretrained_model"] = name
        if "use_bfloat16" in inspect.signature(models.CellposeModel).parameters:
            opts["use_bfloat16"] = device == "cuda" and config["task"] == "predict"
    elif config["task"] == "train" and not name:
        opts.update(pretrained_model=False, diam_mean=config["diameter"] or 30.0)
    elif Path(name).is_file():
        opts["pretrained_model"] = name
    elif name in models.MODEL_NAMES:
        opts["model_type"] = name
    else:
        raise ValueError(f"Unknown Cellpose 3 model or file: {name}")
    if not sam and "transformer" in Path(name).name.lower():
        opts["backbone"] = "transformer"
    print(f"{module} {installed} · model {name or 'from scratch'} · {device}")
    return models.CellposeModel(**opts)


def cellpose_channels(patch):
    return [1, 2] if patch["channels"] == 2 else [0, 0]


def predict_cellpose(config, device):
    sam = config["engine"] == "cellpose-sam"
    module, installed = cellpose_api(sam)
    model = cellpose_model(config, device, module, installed, sam)
    for index, patch in enumerate(config["patches"], 1):
        print(f"Cellpose region {index} of {len(config['patches'])}")
        data = load_image(patch, config)
        opts = {} if sam else {"channels": cellpose_channels(patch)}
        masks, *_ = model.eval(
            data, channel_axis=-1, do_3D=False, diameter=config["diameter"] or None,
            cellprob_threshold=config.get("threshold", 0), flow_threshold=config["flow_threshold"], **opts,
        )
        save_instances(patch, masks)


def train_cellpose(config, device):
    sam = config["engine"] == "cellpose-sam"
    module, installed = cellpose_api(sam)
    train = importlib.import_module(f"{module}.train")
    model = cellpose_model(config, device, module, installed, sam)
    train_images, train_masks, val_images, val_masks = [], [], [], []
    for patch in config["patches"]:
        images, masks = (train_images, train_masks) if patch["split"] == "training" else (val_images, val_masks)
        images.append(load_image(patch, config))
        masks.append(load_mask(patch))

    class CellposeLossHandler(logging.Handler):
        pattern = re.compile(r"^\s*(\d+),\s*train_loss=([^,]+),\s*test_loss=([^,]+)")

        def emit(self, record):
            match = self.pattern.match(record.getMessage())
            if match:
                epoch, training, validation = match.groups()
                report_loss(int(epoch) + 1, float(training), float(validation))

    handler = CellposeLossHandler()
    train.train_logger.addHandler(handler)
    artifact = Path(config["output"])
    try:
        opts = {} if sam else {"channels": cellpose_channels(config["patches"][0])}
        if "bsize" in inspect.signature(train.train_seg).parameters:
            if sam:
                backbone = getattr(model.net, "backbone", "sam_vitl")
                crop = config["crop"] or (384 if backbone.startswith("dino") else 256)
                if (backbone.startswith("dino") and (crop < 32 or crop % 16)) or (
                    not backbone.startswith("dino") and crop != 256
                ):
                    raise ValueError("SAM requires 256 px crops. DINO requires a multiple of 16 of at least 32 px.")
                opts["bsize"] = crop
                print(f"Fine-tuning {backbone} with {crop} × {crop} px crops.")
            else:
                opts["bsize"] = config["crop"]
        result = train.train_seg(
            model.net, train_data=train_images, train_labels=train_masks, test_data=val_images,
            test_labels=val_masks, channel_axis=-1,
            n_epochs=config["epochs"], batch_size=config["batch_size"], learning_rate=config["learning_rate"],
            min_train_masks=config["minimum_masks"], save_path=artifact.parent, model_name=config["name"],
            **opts,
        )
    finally:
        train.train_logger.removeHandler(handler)
    if isinstance(result, (tuple, list)):
        model_path, train_history, *_ = result
        if hasattr(train_history, "__iter__"):
            for epoch, loss in enumerate(train_history, 1):
                report_loss(epoch, train=loss)
    else:
        model_path = result
    Path(model_path).replace(artifact)


def spotiflow_model(config, device):
    from spotiflow.model import Spotiflow

    name = config["model"]
    if not name:
        from spotiflow.model.config import SpotiflowModelConfig

        model = Spotiflow(SpotiflowModelConfig(sigma=config["sigma"]))
    elif Path(name).is_dir():
        model = Spotiflow.from_folder(name, map_location=device, inference_mode=config["task"] == "predict")
    else:
        model = Spotiflow.from_pretrained(name, map_location=device, inference_mode=config["task"] == "predict")
    if model.config.is_3d or model.config.in_channels != 1 or model.config.out_channels != 1:
        raise ValueError("Choose a single-channel, single-class 2D Spotiflow model.")
    print(f"Spotiflow {version('spotiflow')} · model {name or 'from scratch'} · {device}")
    return model


def predict_spotiflow(config, device):
    model = spotiflow_model(config, device)
    for index, patch in enumerate(config["patches"], 1):
        print(f"Spotiflow region {index} of {len(config['patches'])}")
        points, details = model.predict(
            load_image(patch, config)[..., 0], prob_thresh=config.get("threshold"), device=device,
            min_distance=config["min_distance"], exclude_border=config["exclude_border"],
        )
        np.savetxt(patch["target"], np.column_stack((points, details.prob)), fmt="%.9g", delimiter="\t")
        print(f"Spotiflow returned {len(points)} spot(s).")


def load_points(path):
    rows = Path(path).read_text(encoding="utf-8").splitlines()
    return np.asarray([line.split() for line in rows], dtype=np.float32).reshape(-1, 2)


def train_spotiflow(config, device):
    from spotiflow.model import spotiflow as api

    model = spotiflow_model(config, device)
    train_images, train_points, val_images, val_points = [], [], [], []
    for patch in config["patches"]:
        images, points = (train_images, train_points) if patch["split"] == "training" else (val_images, val_points)
        images.append(load_image(patch, config)[..., 0])
        points.append(load_points(patch["target"]))
    if not any(len(points) for points in train_points) or not any(len(points) for points in val_points):
        raise ValueError("Spotiflow requires at least one labeled spot in both splits.")
    min_size = 2 ** model.config.levels * max(model.config.grid)
    if min(min(image.shape) for image in train_images + val_images) < min_size:
        raise ValueError(f"This Spotiflow model requires patches at least {min_size} px wide and high.")

    class LossCallback(api.pl.Callback):
        def on_train_epoch_end(self, trainer, module):
            metrics = trainer.callback_metrics
            report_loss(trainer.current_epoch + 1, metrics.get("train_loss"), metrics.get("val_loss"))

    # Spotiflow's checkpoint callback expects a Lightning logger for its metrics.
    metrics_logger = api.pl.loggers.CSVLogger(save_dir=str(Path(config["output"]).parent), name="metrics")
    model.fit(
        train_images, train_points, val_images, val_points, save_dir=config["output"],
        train_config={
            "num_epochs": config["epochs"], "lr": config["learning_rate"],
            "batch_size": min(config["batch_size"], len(train_images)), "crop_size": config["crop"],
        },
        device="gpu" if device == "cuda" else device, callbacks=[LossCallback()],
        logger=metrics_logger, num_workers=0, default_root_dir=config["output"],
    )



def load_mask(patch):
    return np.fromfile(patch["target"], dtype="<i4").reshape(patch["height"], patch["width"])


def save_instances(patch, masks):
    masks = np.asarray(masks, dtype="<i4")
    if masks.shape != (patch["height"], patch["width"]):
        raise ValueError(f"Expected a 2D instance mask for {patch['width']} × {patch['height']} pixels, got {masks.shape}.")
    masks.tofile(patch["target"])
    print(f"Returned {np.unique(masks[masks > 0]).size} instance(s).")


def patch_pixel_size(patch, config):
    return config.get("pixel_size") or patch.get("pixelSize")


def predict_stardist(config, device):
    from cistardist_pytorch import StarDist2D

    model = StarDist2D.from_folder(config["model"], device=device)
    print(f"StarDist PyTorch · {config['model']} · {device}")
    for index, patch in enumerate(config["patches"], 1):
        print(f"StarDist region {index} of {len(config['patches'])}")
        data = load_image(patch, config)[..., 0]
        labels, _ = model.predict_instances(data, prob_thresh=config.get("threshold"), nms_thresh=config.get("nms"))
        save_instances(patch, labels)


def predict_instanseg(config, device):
    import torch
    from instanseg import InstanSeg

    path = Path(config["model"])
    if path.is_dir():
        path /= "instanseg.pt"
    if not path.is_file():
        raise FileNotFoundError("Choose a local InstanSeg model folder or instanseg.pt file.")
    script = torch.jit.load(str(path), map_location="cpu")
    model = InstanSeg(script, device=device, verbosity=0)
    cells = config.get("cells", False)
    if cells and not script.cells_and_nuclei:
        raise ValueError("Choose an InstanSeg model with a cell output for the Cells target.")
    target = "cells" if cells else "nuclei"
    print(f"InstanSeg · {path} · {target} · {device}")
    for index, patch in enumerate(config["patches"], 1):
        print(f"InstanSeg region {index} of {len(config['patches'])}")
        data = np.moveaxis(load_image(patch, config), -1, 0)
        pixel_size = patch_pixel_size(patch, config)
        if pixel_size is None:
            print("Image has no pixel calibration. Evaluating at the model's native scale.")
        else:
            factor = pixel_size / float(script.pixel_size)
            if np.prod(np.maximum(1, np.ceil(np.array(data.shape[-2:]) * factor))) > 2**24:
                raise ValueError("InstanSeg's rescaled region is too large. Use a smaller region or increase downsample.")
        with torch.inference_mode():
            result = model.eval_small_image(data, pixel_size=pixel_size, target=target, return_image_tensor=False)
        save_instances(patch, result.cpu().numpy().reshape(patch["height"], patch["width"]))


def predict_cellsam(config, device):
    import torch
    from cellSAM import get_local_model
    from cellSAM.utils import fill_holes_and_remove_small_masks, normalize_image

    selection = config["model"]
    path = Path(selection)
    if path.is_file():
        model = get_local_model(str(path))
    elif selection in ("cellsam_general", "cellsam_extra"):
        cached = sorted((Path.home() / ".deepcell" / "models").glob(f"cellsam_v*/{selection}.pt"))
        if cached:
            model = get_local_model(str(cached[-1]))
        else:
            raise FileNotFoundError(
                "Download and unpack the official CellSAM weights from huggingface.co/vanvalenlab/cellsam. "
                "Choose cellsam_general.pt or cellsam_extra.pt with Browse."
            )
    else:
        raise FileNotFoundError("Choose a CellSAM checkpoint file or a CellSAM preset.")
    device = "cpu" if device == "mps" else device
    threshold = config.get("threshold")
    model = model.to(device).eval()
    model.bbox_threshold = 0.4 if threshold is None else threshold
    print(f"CellSAM · {path.name} · {device}")
    for index, patch in enumerate(config["patches"], 1):
        print(f"CellSAM region {index} of {len(config['patches'])}")
        data = load_image(patch, config)
        if min(data.shape[:2]) < 8:
            raise ValueError("CellSAM requires regions of at least 8 × 8 model pixels.")
        normalized = normalize_image(data)
        tensor = torch.from_numpy(np.moveaxis(normalized, -1, 0).copy()).float().unsqueeze(0).to(device)
        with torch.inference_mode():
            prediction = model.predict(tensor)
        instances = None if prediction is None else prediction[0]
        if instances is None:
            labels = np.zeros(data.shape[:2], dtype=np.int32)
        else:
            labels = fill_holes_and_remove_small_masks(instances, min_size=25)
        save_instances(patch, labels)


class MicroSamPredictor:
    def __init__(self, config, device):
        from micro_sam.util import get_sam_model

        weights = config["model"] or None
        if weights and not Path(weights).is_file():
            raise FileNotFoundError("Choose an accessible µSAM checkpoint file.")
        model_type = config.get("model_type", "vit_b_lm")
        print("µSAM · Loading checkpoint", flush=True)
        self.predictor = get_sam_model(model_type=model_type, device=device, checkpoint_path=weights)
        self.embedding_key = None
        self.embeddings = OrderedDict()

    def prepare(self, config):
        import torch
        from micro_sam.util import set_precomputed

        key = config.get("embedding_key")
        key = None if key is None else json.dumps(key)
        if key is not None and self.embedding_key == key:
            return
        with torch.inference_mode():
            if key is not None and key in self.embeddings:
                self.embeddings.move_to_end(key)
                set_precomputed(self.predictor, self.embeddings[key])
            else:
                print("µSAM · Preparing image area", flush=True)
                data = load_image(config["patches"][0], config)
                low, high = np.percentile(data, (1, 99.8), axis=(0, 1))
                normalized = np.uint8(255 * np.clip((data - low) / np.maximum(high - low, 1e-12), 0, 1))
                if data.shape[-1] == 1:
                    normalized = np.repeat(normalized, 3, axis=-1)
                self.predictor.set_image(normalized)
                if key is not None:
                    self.embeddings[key] = {
                        "features": self.predictor.get_image_embedding(),
                        "input_size": self.predictor.input_size,
                        "original_size": self.predictor.original_size,
                    }
                    if len(self.embeddings) > 4:
                        self.embeddings.popitem(last=False)
        self.embedding_key = key

    def predict(self, config):
        import torch
        from scipy.ndimage import label

        self.prepare(config)
        patch = config["patches"][0]
        points = np.array([[item["x"], item["y"]] for item in config["prompts"]], dtype=np.float32)
        labels = np.array([item["label"] for item in config["prompts"]], dtype=np.int32)
        with torch.inference_mode():
            masks, scores, _ = self.predictor.predict(point_coords=points, point_labels=labels, multimask_output=True)
        foreground = points[labels == 1][0]
        x, y = map(int, foreground)
        result = np.zeros(masks.shape[1:], dtype=np.int32)
        if 0 <= x < result.shape[1] and 0 <= y < result.shape[0]:
            for index in np.argsort(scores)[::-1]:
                if masks[index, y, x]:
                    components, _ = label(masks[index])
                    result = components == components[y, x]
                    break
        save_instances(patch, result)


def prompt_microsam(config, device):
    MicroSamPredictor(config, device).predict(config)


def microsam_session():
    predictor = None
    for line in sys.stdin:
        try:
            config = json.loads(line)
            if predictor is None:
                predictor = MicroSamPredictor(config, select_device(config["device"]))
            if config.get("prepare_only"):
                predictor.prepare(config)
            else:
                predictor.predict(config)
            result = {"ok": True}
        except Exception as exc:
            result = {"error": f"{type(exc).__name__}: {exc}"}
        print("CI_MICROSAM\t" + json.dumps(result), flush=True)


def labeled_crop(image, labels, size, rng):
    h, w = labels.shape
    ys, xs = np.nonzero(labels)
    if ys.size == 0:
        raise ValueError("A training region contains no labeled pixels after rescaling.")
    i = rng.integers(ys.size) if rng is not None else ys.size // 2
    y = int(np.clip(ys[i] - size // 2, 0, max(0, h - size)))
    x = int(np.clip(xs[i] - size // 2, 0, max(0, w - size)))
    image = image[y:y+size, x:x+size]
    labels = labels[y:y+size, x:x+size]
    height, width = labels.shape
    pad = ((0, size - height), (0, size - width))
    image = np.pad(image, (*pad, (0, 0)), mode="edge")
    labels = np.pad(labels, pad)
    if rng is not None:
        if rng.random() < 0.5:
            image, labels = image[::-1], labels[::-1]
        if rng.random() < 0.5:
            image, labels = image[:, ::-1], labels[:, ::-1]
    return np.ascontiguousarray(image), np.ascontiguousarray(labels)


def instance_patches(config):
    return [
        (patch, load_image(patch, config), load_mask(patch))
        for patch in config["patches"]
    ]


def optimize_instances(net, optimizer, examples, config, crop, loss_for, best_path):
    import torch

    rng = np.random.default_rng(11)
    training = [(image, labels) for patch, image, labels in examples if patch["split"] == "training"]
    validation = [(image, labels) for patch, image, labels in examples if patch["split"] == "validation"]
    best = math.inf
    batch_size = config["batch_size"]
    for epoch in range(1, config["epochs"] + 1):
        values = []
        for updating, examples in ((True, training), (False, validation)):
            net.train(updating)
            order = rng.permutation(len(examples)) if updating else range(len(examples))
            total = 0.0
            for offset in range(0, len(examples), batch_size):
                indices = order[offset:offset + batch_size]
                pairs = [labeled_crop(*examples[i], crop, rng if updating else None) for i in indices]
                with torch.set_grad_enabled(updating):
                    loss = loss_for(pairs)
                if not torch.isfinite(loss):
                    raise ValueError("Training stopped because the loss was not finite.")
                if updating:
                    optimizer.zero_grad(set_to_none=True)
                    loss.backward()
                    optimizer.step()
                total += loss.item() * len(indices)
            values.append(total / len(examples))
        report_loss(epoch, *values)
        print(f"Epoch {epoch}: training loss={values[0]:.5f}, validation loss={values[1]:.5f}")
        if values[1] < best:
            best = values[1]
            torch.save(net.state_dict(), best_path)


def train_stardist(config, device):
    import torch
    import torch.nn.functional as functional
    from cistardist_pytorch import StarDist2D
    from cistardist_pytorch.config import StarDist2DConfig
    from cistardist_pytorch.net import StarDist2DNet
    from cistardist_pytorch.model import normalize_percentile
    from stardist.geometry import star_dist
    from stardist.utils import edt_prob

    if config["model"]:
        model = StarDist2D.from_folder(config["model"], device=device)
    else:
        settings = StarDist2DConfig.from_dict({
            "n_dim": 2, "axes": "YXC", "n_channel_in": 1, "n_channel_out": 33, "n_rays": 32,
            "grid": [2, 2], "backbone": "unet", "unet_n_depth": 3, "unet_kernel_size": [3, 3],
            "unet_n_filter_base": 32, "unet_n_conv_per_depth": 2, "unet_pool": [2, 2],
            "unet_activation": "relu", "unet_last_activation": "relu", "unet_batch_norm": False,
            "unet_dropout": 0.0, "net_conv_after_unet": 32, "train_checkpoint": "weights_best.pt",
        })
        model = StarDist2D(StarDist2DNet(settings), settings, device=device)
    settings = model.config
    if settings.n_channel_in != 1:
        raise ValueError("Choose a single-channel StarDist 2D model.")
    stride = max(settings.axes_div_by())
    crop = config["crop"]
    if crop < stride or crop % stride:
        raise ValueError(f"StarDist needs a crop size that is a multiple of {stride}.")
    net = model.net
    out = Path(config["output"])
    out.mkdir()
    checkpoint = out / "weights_best.pt"
    print(f"Training StarDist PyTorch · {config['model'] or 'from scratch'} · {device}")

    def loss_for(pairs):
        images, probabilities, distances = [], [], []
        gy, gx = settings.grid
        for image, labels in pairs:
            images.append(normalize_percentile(image[..., 0]))
            probabilities.append(edt_prob(labels)[::gy, ::gx])
            distances.append(np.moveaxis(star_dist(labels, settings.n_rays, grid=settings.grid), -1, 0))
        array = lambda items: torch.from_numpy(np.asarray(items, dtype=np.float32)).to(device)
        prob, dist = net(array(images)[:, None])
        ref_prob = array(probabilities)[:, None]
        ref_dist = array(distances)
        weights = ref_prob.expand_as(dist)
        rays = (weights * torch.abs(dist - ref_dist)).sum() / weights.sum().clamp_min(1)
        background = (dist.abs() * (1 - weights)).mean()
        return functional.binary_cross_entropy(prob, ref_prob) + 0.2 * rays + 0.0001 * background

    optimize_instances(net, torch.optim.AdamW(net.parameters(), lr=config["learning_rate"]),
                       instance_patches(config), config, crop, loss_for, checkpoint)
    (out / "config.json").write_text(json.dumps(settings.as_dict(), indent=2), encoding="utf-8")
    (out / "thresholds.json").write_text(json.dumps(model.thresholds, indent=2), encoding="utf-8")
    if config["engine"] == "stardist-qp":
        export_stardist_onnx(net, checkpoint, out / "model.onnx", crop)


def export_stardist_onnx(net, checkpoint, output, crop):
    import torch

    class CombinedOutput(torch.nn.Module):
        def __init__(self, model):
            super().__init__()
            self.model = model

        def forward(self, image):
            probability, rays = self.model(image)
            return torch.cat((probability, rays.clamp_min(0.001)), dim=1)

    net = net.to("cpu").eval()
    net.load_state_dict(torch.load(checkpoint, map_location="cpu", weights_only=True))
    torch.onnx.export(
        CombinedOutput(net), torch.zeros((1, 1, crop, crop)), str(output), dynamo=False,
        input_names=["input"], output_names=["prediction"], opset_version=13,
        dynamic_axes={"input": {2: "height", 3: "width"}, "prediction": {2: "output_height", 3: "output_width"}},
    )
    print("Exported the trained probability and ray maps as model.onnx for StarDist-QP.")


def train_instanseg(config, device):
    import torch
    from instanseg.utils.loss.instanseg_loss import InstanSeg, InstanSeg_Torchscript
    from instanseg.utils.models.InstanSeg_UNet import InstanSeg_UNet
    from instanseg.utils.utils import percentile_normalize
    from skimage.transform import resize

    crop = config["crop"]
    if crop < 32 or crop % 16:
        raise ValueError("InstanSeg needs crops of at least 32 px and a multiple of 16.")
    examples = instance_patches(config)
    channels = examples[0][0]["channels"]
    sizes = [patch_pixel_size(patch, config) for patch, _, _ in examples]
    if any(sizes) and any(size is None for size in sizes):
        raise ValueError("Selected images mix calibrated and uncalibrated patches. Enter a pixel size override.")
    reference_size = float(np.median(sizes)) if all(sizes) else 0.5
    if not any(sizes):
        print("Images are uncalibrated. Saving the model with an assumed pixel size of 0.5 µm.")
    prepared = []
    for (patch, image, labels), size in zip(examples, sizes):
        if size is not None and not math.isclose(size, reference_size):
            shape = tuple(max(1, round(axis * size / reference_size)) for axis in labels.shape)
            if np.prod(shape) > 2**24:
                raise ValueError("An InstanSeg training patch exceeds the supported size after calibration.")
            image = resize(image, (*shape, channels), preserve_range=True).astype(np.float32)
            labels = resize(labels, shape, order=0, preserve_range=True, anti_aliasing=False).astype(np.int32)
        prepared.append((patch, image, labels))
    criterion = InstanSeg(n_sigma=2, device=device, window_size=min(crop, 64))
    net = criterion.initialize_pixel_classifier(InstanSeg_UNet(channels, criterion.dim_out)).to(device)
    base = config["model"]
    if base:
        path = Path(base)
        checkpoint = torch.load(path / "weights.pt" if path.is_dir() else path, map_location="cpu", weights_only=True)
        net.load_state_dict(checkpoint)
    print(f"Training InstanSeg at {reference_size:g} µm/pixel · {channels} channel(s) · {device}")
    out = Path(config["output"])
    out.mkdir()
    checkpoint = out / "weights.pt"

    def loss_for(pairs):
        images, masks = zip(*pairs)
        images = torch.from_numpy(np.moveaxis(np.stack(images), -1, 1)).to(device)
        images = torch.stack([percentile_normalize(image) for image in images])
        masks = torch.from_numpy(np.stack(masks)).to(device, dtype=torch.long)[:, None]
        return criterion(net(images), masks).mean()

    optimize_instances(net, torch.optim.AdamW(net.parameters(), lr=config["learning_rate"]),
                       prepared, config, crop, loss_for, checkpoint)
    net = net.cpu().eval()
    net.load_state_dict(torch.load(checkpoint, map_location="cpu", weights_only=True))
    scripted = InstanSeg_Torchscript(model=net, pixel_size=reference_size, n_sigma=2, backbone_dim_in=channels)
    torch.jit.script(scripted).save(str(out / "instanseg.pt"))
    print("Exported instanseg.pt for prediction and weights.pt for fine-tuning.")


def main():
    if sys.argv[1] == "--microsam-session":
        microsam_session()
        return
    config = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    logging.basicConfig(level=logging.INFO, format="%(name)s · %(message)s", stream=sys.stdout)
    device = select_device(config["device"])
    runners = {
        ("prompt", "microsam"): prompt_microsam,
        ("predict", "cellsam"): predict_cellsam,
        ("predict", "cellpose"): predict_cellpose,
        ("train", "cellpose"): train_cellpose,
        ("predict", "cellpose-sam"): predict_cellpose,
        ("train", "cellpose-sam"): train_cellpose,
        ("predict", "stardist"): predict_stardist,
        ("train", "stardist"): train_stardist,
        ("train", "stardist-qp"): train_stardist,
        ("predict", "instanseg"): predict_instanseg,
        ("train", "instanseg"): train_instanseg,
        ("predict", "spotiflow"): predict_spotiflow,
        ("train", "spotiflow"): train_spotiflow,
    }
    runners[config["task"], config["engine"]](config, device)


if __name__ == "__main__":
    main()

"""Run the model APIs for a single QuPath job."""
import inspect
import json
import logging
import math
import re
import sys
from importlib.metadata import version
from pathlib import Path

import numpy as np


def report_loss(epoch, train=None, validation=None):
    def value(number):
        return "-" if number is None or not math.isfinite(float(number)) else f"{float(number):.9g}"

    print(f"CI_LOSS\t{epoch}\t{value(train)}\t{value(validation)}", flush=True)


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


def cellpose_model(config, device):
    installed = version("cellpose")
    if installed.split(".", 1)[0] != "3":
        raise RuntimeError(f"Cellpose 3.x is required. This environment has Cellpose {installed}.")
    from cellpose import models
    import torch

    name = config["model"]
    opts = {"gpu": device == "cuda", "device": torch.device(device)}
    if config["task"] == "train" and not name:
        opts.update(pretrained_model=False, diam_mean=config["diameter"] or 30.0)
    elif Path(name).is_file():
        opts["pretrained_model"] = name
    elif name in models.MODEL_NAMES:
        opts["model_type"] = name
    else:
        raise ValueError(f"Unknown Cellpose 3 model or file: {name}")
    print(f"Cellpose {installed} · model {name or 'from scratch'} · {device}")
    return models.CellposeModel(**opts)


def cellpose_channels(patch):
    return [1, 2] if patch["channels"] == 2 else [0, 0]


def predict_cellpose(config, device):
    model = cellpose_model(config, device)
    for index, patch in enumerate(config["patches"], 1):
        print(f"Cellpose region {index} of {len(config['patches'])}")
        data = load_image(patch, config)
        masks, *_ = model.eval(
            data, channels=cellpose_channels(patch), channel_axis=-1, do_3D=False,
            diameter=config["diameter"] or None, cellprob_threshold=config["threshold"],
            flow_threshold=config["flow_threshold"],
        )
        np.asarray(masks, dtype="<i4").tofile(patch["target"])
        print(f"Cellpose returned {np.unique(masks[masks > 0]).size} instance(s).")


def train_cellpose(config, device):
    from cellpose import train

    model = cellpose_model(config, device)
    train_images, train_masks, val_images, val_masks = [], [], [], []
    for patch in config["patches"]:
        images, masks = (train_images, train_masks) if patch["split"] == "training" else (val_images, val_masks)
        images.append(load_image(patch, config))
        masks.append(np.fromfile(patch["target"], dtype="<i4").reshape(patch["height"], patch["width"]))

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
        opts = {}
        if "bsize" in inspect.signature(train.train_seg).parameters:
            opts["bsize"] = config["crop"]
        result = train.train_seg(
            model.net, train_data=train_images, train_labels=train_masks, test_data=val_images,
            test_labels=val_masks, channels=cellpose_channels(config["patches"][0]), channel_axis=-1,
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

    model.fit(
        train_images, train_points, val_images, val_points, save_dir=config["output"],
        train_config={
            "num_epochs": config["epochs"], "lr": config["learning_rate"],
            "batch_size": min(config["batch_size"], len(train_images)), "crop_size": config["crop"],
        },
        device="gpu" if device == "cuda" else device, callbacks=[LossCallback()],
        logger=False, num_workers=0, default_root_dir=config["output"],
    )


def main():
    config = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    logging.basicConfig(level=logging.INFO, format="%(name)s · %(message)s", stream=sys.stdout)
    device = select_device(config["device"])
    runners = {
        ("predict", "cellpose"): predict_cellpose,
        ("train", "cellpose"): train_cellpose,
        ("predict", "spotiflow"): predict_spotiflow,
        ("train", "spotiflow"): train_spotiflow,
    }
    runners[config["task"], config["engine"]](config, device)


if __name__ == "__main__":
    main()

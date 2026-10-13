"""Reference captioning with Florence-2-base-ft (ONNX, onnx-community export, MIT licence).

usage: python3 -I tools/model/florence_reference.py MODEL_DIR VARIANT IMAGE...
VARIANT is the ONNX file suffix (int8, fp16, ...). Greedy decoding with forced BOS and
no_repeat_ngram_size=3, exactly as the Android port (FlorenceCaptioner.kt) implements it.
"""
import json
import os
import sys

import numpy as np
from PIL import Image

TASK_PROMPT = "Describe with a paragraph what is shown in the image."
MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)
SIZE = 768
LAYERS = 6
HEADS = 12
HEAD_DIM = 64
DECODER_START, BOS, EOS = 2, 0, 2
MAX_NEW = 256
NO_REPEAT = 3


def pixel_values(img):
    img = img.convert("RGB").resize((SIZE, SIZE), resample=Image.BICUBIC)
    arr = np.asarray(img, dtype=np.uint8).astype(np.float32) / np.float32(255.0)
    arr = (arr - MEAN) / STD
    return np.ascontiguousarray(arr.transpose(2, 0, 1)[None, ...], dtype=np.float32)


def banned_tokens(generated):
    """Tokens that would repeat an n-gram (n=3) already present in `generated`."""
    if len(generated) < NO_REPEAT - 1:
        return set()
    prefix = tuple(generated[-(NO_REPEAT - 1):])
    banned = set()
    for i in range(len(generated) - NO_REPEAT + 1):
        if tuple(generated[i:i + NO_REPEAT - 1]) == prefix:
            banned.add(generated[i + NO_REPEAT - 1])
    return banned


class Florence:
    def __init__(self, model_dir, variant):
        import onnxruntime as ort
        opt = ort.SessionOptions()
        def load(name):
            return ort.InferenceSession(os.path.join(model_dir, "onnx", f"{name}_{variant}.onnx"), opt,
                                        providers=["CPUExecutionProvider"])
        self.vision = load("vision_encoder")
        self.embed = load("embed_tokens")
        self.encoder = load("encoder_model")
        self.decoder = load("decoder_model_merged")
        from tokenizers import Tokenizer
        self.tokenizer = Tokenizer.from_file(os.path.join(model_dir, "tokenizer.json"))
        self.prompt_ids = self.tokenizer.encode(TASK_PROMPT).ids

    def generate_ids(self, img, teacher=None):
        """Greedy ids; with `teacher`, follows those ids and returns (ids, agreement)."""
        feats = self.vision.run(None, {"pixel_values": pixel_values(img)})[0]
        prompt = self.embed.run(None, {"input_ids": np.array([self.prompt_ids], dtype=np.int64)})[0]
        inputs = np.concatenate([feats, prompt], axis=1)
        mask = np.ones(inputs.shape[:2], dtype=np.int64)
        hidden = self.encoder.run(None, {"attention_mask": mask, "inputs_embeds": inputs})[0]
        past = {}
        for l in range(LAYERS):
            for kind in ("decoder", "encoder"):
                for kv in ("key", "value"):
                    past[f"past_key_values.{l}.{kind}.{kv}"] = np.zeros((1, HEADS, 0, HEAD_DIM), dtype=np.float32)
        generated = []
        token = DECODER_START
        agreed = compared = 0
        for step in range(len(teacher) if teacher else MAX_NEW):
            emb = self.embed.run(None, {"input_ids": np.array([[token]], dtype=np.int64)})[0]
            feed = {"encoder_attention_mask": mask, "encoder_hidden_states": hidden, "inputs_embeds": emb,
                    "use_cache_branch": np.array([step > 0])}
            feed.update(past)
            outs = self.decoder.run(None, feed)
            names = [o.name for o in self.decoder.get_outputs()]
            logits = outs[0][0, -1].astype(np.float64)
            for name, value in zip(names[1:], outs[1:]):
                key = name.replace("present.", "past_key_values.")
                # Encoder cross-attention caches are computed on the first step only.
                if step == 0 or ".decoder." in key:
                    past[key] = value
            if step == 0:
                token = BOS
            else:
                for b in banned_tokens(generated):
                    logits[b] = -np.inf
                token = int(np.argmax(logits))
                if teacher:
                    compared += 1
                    agreed += token == teacher[step]
                    token = teacher[step]
            generated.append(token)
            if token == EOS and not teacher:
                break
        return (generated, agreed / max(1, compared)) if teacher else generated

    def caption(self, img):
        ids = self.generate_ids(img)
        return self.tokenizer.decode(ids, skip_special_tokens=True).strip(), ids


if __name__ == "__main__":
    model_dir, variant = sys.argv[1], sys.argv[2]
    f = Florence(model_dir, variant)
    print("prompt ids:", f.prompt_ids)
    for path in sys.argv[3:]:
        text, ids = f.caption(Image.open(path))
        print(json.dumps({"file": os.path.basename(path), "caption": text, "ids": ids}))

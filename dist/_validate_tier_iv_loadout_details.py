from __future__ import annotations

from dataclasses import dataclass, replace
from pathlib import Path
import re

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
CLIENT = ROOT / "src/main/java/com/miningdim/job/engineer/armor/client"
TEXTURES = ROOT / "src/main/resources/assets/miningdim/textures/models/armor"

NUMBER = r"-?(?:\d+(?:\.\d*)?|\.\d+)F?"
TEX_HEADER = re.compile(r"\.texOffs\(\s*(\d+)\s*,\s*(\d+)\s*\)\s*\.addBox\(", re.DOTALL)
FOR_HEADER = re.compile(
    r"for\s*\(\s*int\s+(\w+)\s*=\s*([^;]+);\s*\1\s*<\s*([^;]+);\s*\1\+\+\s*\)\s*\{"
)
FLOAT_ASSIGNMENT = re.compile(r"float\s+(\w+)\s*=\s*([^;]+);")

CONNECT_EPSILON = 0.014
MAX_VISUAL_SEAM = 0.075
# Faces closer than 0.025 model units can shimmer once armor is animated and lit.
# Keep raised seams, lids, and adjacent shell courses at least 0.03 apart.
FACE_EPSILON = 0.025
AREA_EPSILON = 0.025


class ContractError(AssertionError):
    pass


@dataclass(frozen=True)
class Box:
    uid: str
    bone: str
    u: int
    v: int
    x: float
    y: float
    z: float
    width: float
    height: float
    depth: float
    inflate: float = 0.0

    @property
    def x1(self) -> float:
        return self.x + self.width

    @property
    def y1(self) -> float:
        return self.y + self.height

    @property
    def z1(self) -> float:
        return self.z + self.depth

    @property
    def volume(self) -> float:
        return self.width * self.height * self.depth

    @property
    def center_x(self) -> float:
        return self.x + self.width / 2.0

    @property
    def gx(self) -> float:
        return self.x - self.inflate

    @property
    def gx1(self) -> float:
        return self.x1 + self.inflate

    @property
    def gy(self) -> float:
        return self.y - self.inflate

    @property
    def gy1(self) -> float:
        return self.y1 + self.inflate

    @property
    def gz(self) -> float:
        return self.z - self.inflate

    @property
    def gz1(self) -> float:
        return self.z1 + self.inflate

    def shifted(self, x: float, y: float, z: float) -> "Box":
        return replace(self, x=self.x + x, y=self.y + y, z=self.z + z)


@dataclass(frozen=True)
class Model:
    name: str
    source: str
    body: tuple[Box, ...]
    bones: dict[str, tuple[Box, ...]]

    @property
    def world_boxes(self) -> tuple[Box, ...]:
        result = list(self.body)
        pivots = {
            "right_arm": (-5.0, 2.0, 0.0),
            "left_arm": (5.0, 2.0, 0.0),
        }
        for bone, bone_boxes in self.bones.items():
            pivot = pivots.get(bone, (0.0, 0.0, 0.0))
            result.extend(box.shifted(*pivot) for box in bone_boxes)
        return tuple(result)


def number(value: str) -> float:
    return float(value.removesuffix("F"))


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ContractError(message)


def balanced_block(source: str, opening: int, label: str) -> str:
    require(opening >= 0 and source[opening] == "{", f"{label}: missing block")
    depth = 0
    for index in range(opening, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[opening + 1:index]
    raise ContractError(f"{label}: unterminated block")


def method_block(source: str, model: str) -> str:
    marker = re.search(
        r"private\s+static\s+CubeListBuilder\s+(?:createBody|body)\s*\(\s*\)\s*\{",
        source,
    )
    require(marker is not None, f"{model}: missing body builder")
    return balanced_block(source, marker.end() - 1, f"{model}.body")


def matching_character(text: str, opening: int, opener: str, closer: str, label: str) -> int:
    require(opening >= 0 and text[opening] == opener, f"{label}: missing {opener}")
    depth = 0
    for index in range(opening, len(text)):
        if text[index] == opener:
            depth += 1
        elif text[index] == closer:
            depth -= 1
            if depth == 0:
                return index
    raise ContractError(f"{label}: unterminated {opener}{closer}")


def split_arguments(arguments: str) -> list[str]:
    result: list[str] = []
    start = 0
    depth = 0
    for index, character in enumerate(arguments):
        if character == "(":
            depth += 1
        elif character == ")":
            depth -= 1
        elif character == "," and depth == 0:
            result.append(arguments[start:index].strip())
            start = index + 1
    result.append(arguments[start:].strip())
    return result


def evaluate(expression: str, environment: dict[str, float]) -> float:
    cleaned = re.sub(r"(?<=\d)F\b", "", expression.strip())
    require(re.fullmatch(r"[\w\d.+\-*/()\s]+", cleaned) is not None,
            f"unsupported Java geometry expression: {expression}")
    try:
        return float(eval(cleaned, {"__builtins__": {}}, environment))
    except (NameError, SyntaxError, TypeError, ZeroDivisionError) as error:
        raise ContractError(f"cannot evaluate Java geometry expression {expression!r}: {error}") from error


def parse_boxes(text: str, bone: str, deformations: dict[str, float]) -> tuple[Box, ...]:
    parsed: list[Box] = []

    def execute(block: str, environment: dict[str, float]) -> None:
        cursor = 0
        while cursor < len(block):
            for_match = FOR_HEADER.search(block, cursor)
            assignment_match = FLOAT_ASSIGNMENT.search(block, cursor)
            box_match = TEX_HEADER.search(block, cursor)
            choices = [
                (match.start(), kind, match)
                for kind, match in (("for", for_match), ("assignment", assignment_match), ("box", box_match))
                if match is not None
            ]
            if not choices:
                return
            _, kind, match = min(choices, key=lambda item: item[0])
            if kind == "for":
                opening = match.end() - 1
                closing = matching_character(block, opening, "{", "}", f"{bone}.for")
                variable = match.group(1)
                start_value = int(evaluate(match.group(2), environment))
                end_value = int(evaluate(match.group(3), environment))
                for value in range(start_value, end_value):
                    nested_environment = dict(environment)
                    nested_environment[variable] = float(value)
                    execute(block[opening + 1:closing], nested_environment)
                cursor = closing + 1
            elif kind == "assignment":
                environment[match.group(1)] = evaluate(match.group(2), environment)
                cursor = match.end()
            else:
                opening = match.end() - 1
                closing = matching_character(block, opening, "(", ")", f"{bone}.addBox")
                arguments = split_arguments(block[opening + 1:closing])
                require(len(arguments) >= 6, f"{bone}.addBox: expected six geometry arguments")
                values = tuple(evaluate(argument, environment) for argument in arguments[:6])
                inflate = 0.0
                if len(arguments) >= 7:
                    token = arguments[6]
                    if token in deformations:
                        inflate = deformations[token]
                    else:
                        inline = re.fullmatch(r"new\s+CubeDeformation\(\s*([^)]*)\s*\)", token)
                        require(inline is not None, f"unsupported CubeDeformation expression: {token}")
                        inflate = evaluate(inline.group(1), environment)
                parsed.append(Box(
                    f"{bone}[{len(parsed)}]", bone,
                    int(match.group(1)), int(match.group(2)), *values, inflate,
                ))
                cursor = closing + 1

    execute(text, {})
    return tuple(parsed)


def parse_bone(source: str, model: str, bone: str,
               deformations: dict[str, float]) -> tuple[Box, ...]:
    marker = f'addOrReplaceChild("{bone}"'
    start = source.find(marker)
    require(start >= 0, f"{model}: missing {bone} bone")
    end = source.find("PartPose.", start)
    require(end >= 0, f"{model}: missing {bone} pose")
    return parse_boxes(source[start:end], bone, deformations)


def load_model(name: str) -> Model:
    path = CLIENT / f"{name}.java"
    require(path.is_file(), f"{name}: missing Java model")
    source = path.read_text(encoding="utf-8")
    deformations = {
        match.group(1): number(match.group(2))
        for match in re.finditer(
            rf"CubeDeformation\s+(\w+)\s*=\s*new\s+CubeDeformation\(\s*({NUMBER})\s*\)",
            source,
        )
    }
    body = parse_boxes(method_block(source, name), "body", deformations)
    bones = {
        bone: parse_bone(source, name, bone, deformations)
        for bone in ("right_arm", "left_arm")
    }
    require(body, f"{name}: no literal body cuboids parsed")
    require(source.count(".texOffs(") == source.count(".addBox("),
            f"{name}: every addBox statement must have an explicit texOffs")
    return Model(name, source, body, bones)


def axis_overlap(a0: float, a1: float, b0: float, b1: float) -> float:
    return min(a1, b1) - max(a0, b0)


def overlaps(first: Box, second: Box) -> tuple[float, float, float]:
    return (
        axis_overlap(first.x, first.x1, second.x, second.x1),
        axis_overlap(first.y, first.y1, second.y, second.y1),
        axis_overlap(first.z, first.z1, second.z, second.z1),
    )


def connected(first: Box, second: Box) -> bool:
    inflated_overlap = (
        axis_overlap(first.gx, first.gx1, second.gx, second.gx1),
        axis_overlap(first.gy, first.gy1, second.gy, second.gy1),
        axis_overlap(first.gz, first.gz1, second.gz, second.gz1),
    )
    return all(value >= -MAX_VISUAL_SEAM for value in inflated_overlap) \
        and sum(value >= CONNECT_EPSILON for value in inflated_overlap) >= 2


def require_connected_model(model: Model) -> None:
    boxes = model.world_boxes
    reached = {0}
    pending = [0]
    while pending:
        current = pending.pop()
        for candidate in range(len(boxes)):
            if candidate not in reached and connected(boxes[current], boxes[candidate]):
                reached.add(candidate)
                pending.append(candidate)
    missing = [boxes[index].uid for index in range(len(boxes)) if index not in reached]
    require(not missing, f"{model.name}: disconnected cuboids: {missing}")


def require_no_duplicates(model: Model) -> None:
    seen: dict[tuple[float, ...], str] = {}
    for box in model.world_boxes:
        signature = (box.x, box.y, box.z, box.width, box.height, box.depth, box.inflate)
        require(signature not in seen,
                f"{model.name}: exact duplicate cuboid {box.uid} == {seen.get(signature)}")
        seen[signature] = box.uid


def face_conflicts(first: Box, second: Box) -> list[str]:
    conflicts: list[str] = []
    axes = (
        ("x", 0.0, first.gx, first.gx1, second.gx, second.gx1,
         (first.gy, first.gy1, second.gy, second.gy1),
         (first.gz, first.gz1, second.gz, second.gz1)),
        ("y", 6.0, first.gy, first.gy1, second.gy, second.gy1,
         (first.gx, first.gx1, second.gx, second.gx1),
         (first.gz, first.gz1, second.gz, second.gz1)),
        ("z", 0.0, first.gz, first.gz1, second.gz, second.gz1,
         (first.gx, first.gx1, second.gx, second.gx1),
         (first.gy, first.gy1, second.gy, second.gy1)),
    )
    for axis, center, first_min, first_max, second_min, second_max, plane_a, plane_b in axes:
        projection_a = axis_overlap(*plane_a)
        projection_b = axis_overlap(*plane_b)
        if projection_a <= AREA_EPSILON or projection_b <= AREA_EPSILON:
            continue
        first_middle = (first_min + first_max) / 2.0
        second_middle = (second_min + second_max) / 2.0
        if first_middle <= center and second_middle <= center \
                and abs(first_min - second_min) <= FACE_EPSILON:
            conflicts.append(f"{axis}-min")
        if first_middle >= center and second_middle >= center \
                and abs(first_max - second_max) <= FACE_EPSILON:
            conflicts.append(f"{axis}-max")
    return conflicts


def require_no_coplanar_faces(model: Model) -> None:
    boxes = model.world_boxes
    failures: list[str] = []
    for first_index, first in enumerate(boxes):
        for second in boxes[first_index + 1:]:
            for face in face_conflicts(first, second):
                failures.append(f"{first.uid}/{second.uid}:{face}")
    require(not failures,
            f"{model.name}: exposed same-direction coplanar faces: {failures[:8]}")


def validate_common(model: Model, minimum_body_boxes: int) -> None:
    require(len(model.body) >= minimum_body_boxes,
            f"{model.name}: under-detailed body ({len(model.body)} < {minimum_body_boxes})")
    for box in model.world_boxes:
        require(box.width > 0 and box.height > 0 and box.depth > 0,
                f"{model.name}.{box.uid}: non-positive cuboid")
        require(0 <= box.u < 128 and 0 <= box.v < 128,
                f"{model.name}.{box.uid}: UV origin outside 128 atlas")
    require_no_duplicates(model)
    require_connected_model(model)
    require_no_coplanar_faces(model)


def front_side_bag_candidates(model: Model) -> tuple[list[Box], list[Box]]:
    candidates = [
        box for box in model.body
        if box.z <= -3.25 and box.depth >= 0.85 and box.height >= 4.0 and box.width >= 1.10
        and 4.0 <= box.y <= 7.0 and box.y1 <= 11.5
    ]
    left = [box for box in candidates if box.x1 <= -1.80]
    right = [box for box in candidates if box.x >= 1.80]
    return left, right


def side_bags(model: Model) -> tuple[Box, Box]:
    left, right = front_side_bag_candidates(model)
    require(left, f"{model.name}: missing solid left waist pouch")
    require(right, f"{model.name}: missing solid right waist pouch")
    return max(left, key=lambda box: box.volume), max(right, key=lambda box: box.volume)


def pouch_layers(model: Model, bag: Box) -> tuple[Box, Box]:
    lids = [
        box for box in model.body if box.uid != bag.uid
        and 0.35 <= box.height <= 1.25 and box.depth >= 0.22
        and axis_overlap(box.x, box.x1, bag.x, bag.x1) >= min(box.width, bag.width) * 0.62
        and axis_overlap(box.y, box.y1, bag.y, bag.y1) >= 0.08
        and bag.z - box.z >= 0.05
    ]
    require(lids, f"{model.name}.{bag.uid}: missing independent pouch lid")
    lid = max(lids, key=lambda box: axis_overlap(box.x, box.x1, bag.x, bag.x1))

    details = [
        box for box in model.body if box.uid not in {bag.uid, lid.uid}
        and 0.12 <= box.depth <= 0.36 and box.height >= 1.0
        and axis_overlap(box.x, box.x1, bag.x, bag.x1) >= min(box.width, bag.width) * 0.22
        and axis_overlap(box.y, box.y1, bag.y, bag.y1) >= 0.45
        and bag.z - box.z >= 0.05
    ]
    require(details, f"{model.name}.{bag.uid}: missing raised stitch/zip/detail layer")
    detail = max(details, key=lambda box: axis_overlap(box.y, box.y1, bag.y, bag.y1))
    return lid, detail


ARM_ENVELOPES = (
    Box("neutral_right_arm", "arm", 0, 0, -8.0, 0.0, -2.0, 4.0, 12.0, 4.0),
    Box("neutral_left_arm", "arm", 0, 0, 4.0, 0.0, -2.0, 4.0, 12.0, 4.0),
)


def require_arm_clearance(model: Model, boxes: tuple[Box, ...]) -> None:
    for box in boxes:
        for arm in ARM_ENVELOPES:
            overlap = overlaps(box, arm)
            require(not all(value > 0.001 for value in overlap),
                    f"{model.name}.{box.uid}: intersects {arm.uid}; overlap={overlap}")


def central_magazines(model: Model) -> list[Box]:
    return [
        box for box in model.body
        if box.z <= -3.25 and box.depth >= 0.85 and box.height >= 4.0
        and box.width >= 1.20 and box.x < 1.8 and box.x1 > -1.8 and 4.5 <= box.y <= 6.5
    ]


def validate_load_bearing_rig(model: Model, minimum_magazines: int) -> tuple[Box, Box]:
    left, right = side_bags(model)
    layers: list[Box] = []
    for bag in (left, right):
        lid, detail = pouch_layers(model, bag)
        layers.extend((bag, lid, detail))
        require(bag.z1 >= -2.42,
                f"{model.name}.{bag.uid}: pouch floats ahead of carrier (rear={bag.z1:.2f})")
    require_arm_clearance(model, tuple(layers))
    magazines = central_magazines(model)
    require(len(magazines) >= minimum_magazines,
            f"{model.name}: magazine bank regressed ({len(magazines)} < {minimum_magazines})")
    return left, right


def validate_rbav(model: Model) -> Box:
    groins = [
        box for box in model.body if abs(box.center_x) <= 0.2 and box.y >= 8.8
        and box.width >= 6.3 and box.height >= 4.8 and box.depth >= 0.65 and box.z <= -3.4
    ]
    require(groins, f"{model.name}: RBAV groin protector is missing or too small")
    groin = max(groins, key=lambda box: box.volume)
    lids = [
        box for box in model.body if box.uid != groin.uid and box.width >= 6.5
        and box.height >= 0.8 and box.height <= 1.2 and box.y <= groin.y
        and box.z <= groin.z - 0.10 and axis_overlap(box.y, box.y1, groin.y, groin.y1) >= 0.5
    ]
    require(lids, f"{model.name}: large groin protector has no raised lid")
    require(re.search(r"row\s*<\s*3.*?10\.45F\s*\+\s*row\s*\*\s*1\.05F.*?"
                      r"5\.70F\s*,\s*0\.18F\s*,\s*0\.24F", model.source, re.DOTALL) is not None,
            f"{model.name}: groin protector must retain three MOLLE rows")

    left = [box for box in model.body if box.x <= -4.40 and box.height >= 3.5
            and box.depth >= 0.60 and box.z <= -3.35]
    right = [box for box in model.body if box.x >= 3.40 and box.height >= 3.5
             and box.depth >= 0.60 and box.z <= -3.35]
    require(left and right, f"{model.name}: missing left/right front side pouches")
    for bag in (max(left, key=lambda box: box.volume), max(right, key=lambda box: box.volume)):
        matching_lids = [
            box for box in model.body if box.height <= 1.1 and box.z <= bag.z - 0.10
            and axis_overlap(box.x, box.x1, bag.x, bag.x1) >= bag.width * 0.8
            and axis_overlap(box.y, box.y1, bag.y, bag.y1) >= 0.25
        ]
        require(matching_lids, f"{model.name}.{bag.uid}: side pouch lid missing")
    return groin


def validate_trooper(model: Model) -> Box:
    bulky_side, bulky_right = front_side_bag_candidates(model)
    require(not bulky_side and not bulky_right,
            f"{model.name}: Trooper TFO must remain a clean carrier without external side bags")
    front_fabric = [box for box in model.body if box.z <= -2.35 and box.width >= 6.0
                    and box.height >= 2.0 and box.depth <= 0.5]
    rear_fabric = [box for box in model.body if box.z >= 1.95 and box.width >= 6.0
                   and box.height >= 2.0 and box.depth <= 0.5]
    require(len(front_fabric) >= 2 and len(rear_fabric) >= 2,
            f"{model.name}: front/rear fabric courses regressed")
    require(re.search(r"row\s*<\s*4.*?6\.50F\s*,\s*0\.28F\s*,\s*0\.24F",
                      model.source, re.DOTALL) is not None,
            f"{model.name}: four front MOLLE rows are required")
    chest_panels = [box for box in model.body if box.z <= -2.65 and box.width >= 5.5
                    and box.height >= 2.0 and box.depth >= 0.30]
    require(chest_panels, f"{model.name}: chest fabric/patch layer missing")
    return max(front_fabric, key=lambda box: box.height)


def validate_banshee(model: Model) -> Box:
    left, right = validate_load_bearing_rig(model, minimum_magazines=0)
    require(left.volume >= right.volume * 1.12,
            f"{model.name}: left IFAK and right pouch bank lost their reference asymmetry")
    require(re.search(r"column\s*<\s*3", model.source) is not None,
            f"{model.name}: triple front magazine bank missing")
    return left


def collar_ring_boxes(model: Model, minimum_height: float) -> list[Box]:
    return [
        box for box in model.body if box.y < 0.0 and box.y1 >= -0.25 and box.height >= minimum_height
        and (box.x <= -4.0 or box.x1 >= 4.0 or box.z <= -4.0 or box.z1 >= 4.0)
    ]


def validate_b6b13(model: Model) -> Box:
    for bone in ("right_arm", "left_arm"):
        shoulder = model.bones[bone]
        require(len(shoulder) >= 2, f"{model.name}: {bone} shoulder armor missing")
        require(any(box.width >= 4.0 and box.height >= 0.6 and box.depth >= 4.0 for box in shoulder),
                f"{model.name}: {bone} top shoulder plate missing")
        require(any(box.width <= 0.75 and box.height >= 1.2 and box.depth >= 4.0 for box in shoulder),
                f"{model.name}: {bone} outer shoulder plate missing")
    collar = collar_ring_boxes(model, 1.5)
    require(len(collar) >= 5 and min(box.y for box in collar) <= -0.75,
            f"{model.name}: tall five-piece collar missing")
    require_arm_clearance(model, tuple(collar))
    aprons = [box for box in model.body if box.y >= 11.0 and box.width >= 4.8
              and box.height >= 3.0 and box.z <= -2.4]
    require(aprons, f"{model.name}: long front apron missing")
    return model.bones["right_arm"][0]


def validate_ana(model: Model) -> Box:
    left, right = validate_load_bearing_rig(model, minimum_magazines=2)
    require(left.width >= 2.4 and 1.3 <= right.width <= 1.9 and left.volume >= right.volume * 1.45,
            f"{model.name}: ANA M1 must retain its large-left/narrow-right pouch asymmetry")
    small_pouches = [
        box for box in model.body
        if -2.65 <= box.x <= -1.45 and 0.75 <= box.width <= 1.10
        and 1.5 <= box.height <= 2.2 and box.depth >= 1.0 and box.z <= -3.45
    ]
    require(len(small_pouches) >= 2,
            f"{model.name}: two stacked small pouches between the left bag and magazine bank are required")
    for pouch in small_pouches[:2]:
        lids = [
            box for box in model.body if box.uid != pouch.uid
            and 0.35 <= box.height <= 0.75 and box.z <= pouch.z - 0.08
            and axis_overlap(box.x, box.x1, pouch.x, pouch.x1) >= pouch.width * 0.8
            and axis_overlap(box.y, box.y1, pouch.y, pouch.y1) >= 0.15
        ]
        require(lids, f"{model.name}.{pouch.uid}: stacked small pouch is missing its lid")
    return right


def validate_a18(model: Model) -> Box:
    left, right = validate_load_bearing_rig(model, minimum_magazines=2)
    require(abs(left.volume - right.volume) >= 0.8,
            f"{model.name}: rebuilt A18 must retain asymmetric sustainment bags")
    thin_rows = [box for box in model.body if box.z <= -2.65 and box.width >= 6.0
                 and box.height <= 0.35 and box.depth <= 0.35]
    require(len(thin_rows) >= 3, f"{model.name}: rebuilt shoulder/MOLLE surface hierarchy missing")
    return right


def validate_avs(model: Model) -> Box:
    _, right = validate_load_bearing_rig(model, minimum_magazines=3)
    stages = sorted(
        [box for box in model.body if abs(box.center_x) <= 0.15 and box.y >= 8.7
         and box.height >= 1.8 and box.width >= 3.2 and box.z <= -2.6],
        key=lambda box: box.y,
    )
    require(len(stages) >= 3, f"{model.name}: long tapered AVS groin apron missing")
    stages = stages[:3]
    require(stages[0].width >= 5.2 and stages[0].width > stages[1].width > stages[2].width
            and stages[-1].y1 >= 14.5,
            f"{model.name}: AVS apron no longer narrows through three long stages")
    return right


def validate_stich(model: Model) -> Box:
    _, right = validate_load_bearing_rig(model, minimum_magazines=2)
    lower = [box for box in model.body if abs(box.center_x) <= 0.2 and box.y >= 9.0
             and box.width >= 4.8 and box.height >= 3.5 and box.depth >= 0.8]
    require(lower, f"{model.name}: broad lower waist pouch missing")
    return right


def validate_tv110(model: Model) -> Box:
    left, right = validate_load_bearing_rig(model, minimum_magazines=2)
    require(left.width >= 2.0 and left.height >= 4.8,
            f"{model.name}: reference-defining left square pouch is too small")
    require(right.height >= 5.4, f"{model.name}: tall right radio/side pouch is too small")
    molle = [box for box in model.body if box.z <= -2.60 and box.width >= 6.0
             and box.height <= 0.32 and box.depth <= 0.30]
    require(len(molle) >= 4, f"{model.name}: four stepped MOLLE/detail rows are required")
    return right


def validate_b6b23(model: Model) -> Box:
    collar = collar_ring_boxes(model, 1.5)
    require(len(collar) >= 5 and min(box.y for box in collar) <= -1.7,
            f"{model.name}: high five-piece collar missing")
    yokes = [box for box in model.body if box.y < 0.0 and box.y1 >= 0.7
             and box.width >= 2.8 and box.depth >= 2.0]
    require(len(yokes) >= 4, f"{model.name}: four shoulder-top yokes missing")
    shoulder_tabs = [box for box in model.body if 0.0 <= box.y <= 0.5
                     and 0.8 <= box.width <= 1.4 and box.height >= 1.4
                     and box.depth <= 0.35 and box.z <= -2.5]
    require(any(box.center_x < 0 for box in shoulder_tabs)
            and any(box.center_x > 0 for box in shoulder_tabs),
            f"{model.name}: paired shoulder-top plates missing")
    raised_panels = [box for box in model.body if box.z <= -2.58 and box.width >= 6.0
                     and box.height >= 2.5 and box.depth <= 0.36 and box.y < 8.0]
    seams = [box for box in model.body if box.z <= -2.70 and box.depth <= 0.20
             and (box.width >= 5.5 or box.height >= 5.0)]
    require(len(raised_panels) >= 2 and len(seams) >= 4,
            f"{model.name}: layered front panels/stitch seams regressed")
    aprons = [box for box in model.body if box.y >= 11.5 and box.width >= 6.0
              and box.height >= 3.5 and box.z <= -2.6]
    require(aprons, f"{model.name}: long front apron missing")
    return max(aprons, key=lambda box: box.volume)


def validate_b6b5(model: Model) -> Box:
    inner = collar_ring_boxes(model, 1.6)
    outer = [box for box in model.body if box.y <= -1.8 and box.height <= 0.25
             and (box.x <= -4.0 or box.x1 >= 4.0 or box.z <= -4.0 or box.z1 >= 4.0)]
    require(len(inner) >= 5 and min(box.y for box in inner) <= -1.9,
            f"{model.name}: tall inner five-piece collar missing")
    require(len(outer) >= 5, f"{model.name}: second outer collar ring missing")

    pouches = [box for box in model.body if box.z <= -3.15 and box.depth >= 0.85
               and box.height >= 4.5 and box.width >= 1.5 and 4.5 <= box.y <= 5.2]
    require(len(pouches) >= 4, f"{model.name}: four front/side pouch bodies missing")
    for pouch in pouches[:4]:
        lids = [box for box in model.body if box.height <= 0.9 and box.z <= pouch.z - 0.10
                and axis_overlap(box.x, box.x1, pouch.x, pouch.x1) >= pouch.width * 0.8
                and axis_overlap(box.y, box.y1, pouch.y, pouch.y1) >= 0.5]
        require(lids, f"{model.name}.{pouch.uid}: independent pouch lid missing")
    pulls = [box for box in model.body if box.z <= -3.45 and box.width <= 0.22
             and box.height >= 2.0 and box.depth <= 0.18]
    require(len(pulls) >= 4, f"{model.name}: pouch pull straps/details missing")
    return inner[0]


TEXTURE_CONTRACTS = {
    "plate_armor_rbav_af_ranger_green_layer_1.png": 100,
    "plate_armor_trooper_tfo_multicam_layer_1.png": 100,
    "plate_armor_banshee_atacs_au_layer_1.png": 100,
    "plate_armor_6b13_flora_layer_1.png": 100,
    "plate_armor_ana_m1_olive_layer_1.png": 80,
    "plate_armor_a18_skanda_multicam_layer_1.png": 120,
    "plate_armor_avs_ranger_green_layer_1.png": 80,
    "plate_armor_avs_multicam_layer_1.png": 120,
    "plate_armor_stich_profi_v2_black_layer_1.png": 55,
    "plate_armor_tv110_coyote_layer_1.png": 75,
    "plate_armor_6b23_2_mountain_flora_layer_1.png": 110,
    "plate_armor_6b5_15_flora_layer_1.png": 90,
}


def texture_metrics(image: Image.Image) -> tuple[int, float, float, float, float]:
    rgb = image.convert("RGB")
    pixels = rgb.load()
    differences: list[int] = []
    for y in range(rgb.height):
        for x in range(rgb.width - 1):
            differences.append(max(abs(pixels[x, y][channel] - pixels[x + 1, y][channel])
                                   for channel in range(3)))
    for y in range(rgb.height - 1):
        for x in range(rgb.width):
            differences.append(max(abs(pixels[x, y][channel] - pixels[x, y + 1][channel])
                                   for channel in range(3)))
    tiles = []
    for y in range(0, rgb.height, 8):
        for x in range(0, rgb.width, 8):
            colors = rgb.crop((x, y, x + 8, y + 8)).getcolors(65) or []
            tiles.append(len(colors))
    colors = len(rgb.getcolors(rgb.width * rgb.height + 1) or ())
    edge_6 = sum(value >= 6 for value in differences) / len(differences)
    edge_12 = sum(value >= 12 for value in differences) / len(differences)
    mean_delta = sum(differences) / len(differences)
    local_ratio = sum(value >= 8 for value in tiles) / len(tiles)
    return colors, edge_6, edge_12, mean_delta, local_ratio


def validate_textures() -> None:
    for filename, minimum_colors in TEXTURE_CONTRACTS.items():
        path = TEXTURES / filename
        require(path.is_file(), f"missing corrected texture: {filename}")
        with Image.open(path) as image:
            require(image.size == (128, 128) and image.mode == "RGBA",
                    f"{filename}: expected 128x128 RGBA texture")
            require(image.getchannel("A").getextrema() == (255, 255),
                    f"{filename}: texture must remain fully opaque")
            colors, edge_6, edge_12, mean_delta, local_ratio = texture_metrics(image)
        require(colors >= minimum_colors,
                f"{filename}: insufficient material colors ({colors} < {minimum_colors})")
        require(edge_6 >= 0.105 and edge_12 >= 0.008,
                f"{filename}: insufficient edge detail (edge6={edge_6:.3f}, edge12={edge_12:.3f})")
        require(mean_delta >= 2.60,
                f"{filename}: surface variation too flat (mean delta={mean_delta:.2f})")
        require(local_ratio >= 0.94,
                f"{filename}: local 8x8 material variation too sparse ({local_ratio:.3f})")
        print(f"TEXTURE {filename}: colors={colors}, edge6={edge_6:.3f}, "
              f"edge12={edge_12:.3f}, local={local_ratio:.3f}")


VALIDATORS = {
    "RbavAfArmorModel": (23, validate_rbav),
    "TrooperTfoArmorModel": (21, validate_trooper),
    "BansheeArmorModel": (25, validate_banshee),
    "B6B13ArmorModel": (18, validate_b6b13),
    "AnaM1ArmorModel": (25, validate_ana),
    "A18SkandaArmorModel": (29, validate_a18),
    "AvsArmorModel": (33, validate_avs),
    "StichProfiV2ArmorModel": (25, validate_stich),
    "Tv110ArmorModel": (29, validate_tv110),
    "B6B23MountainFloraArmorModel": (32, validate_b6b23),
    "B6B5FloraArmorModel": (43, validate_b6b5),
}


def without_body_box(model: Model, target: Box) -> Model:
    return replace(model, body=tuple(box for box in model.body if box.uid != target.uid))


def without_bone_box(model: Model, bone: str, target: Box) -> Model:
    bones = dict(model.bones)
    bones[bone] = tuple(box for box in bones[bone] if box.uid != target.uid)
    return replace(model, bones=bones)


def mutation_probe(model: Model, validator, target: Box) -> None:
    if target.bone == "body":
        mutated = without_body_box(model, target)
    else:
        mutated = without_bone_box(model, target.bone, target)
    try:
        validator(mutated)
    except ContractError:
        return
    raise ContractError(f"{model.name}: deletion probe did not detect removal of {target.uid}")


def main() -> None:
    models: dict[str, Model] = {}
    critical: dict[str, Box] = {}
    for name, (minimum_boxes, validator) in VALIDATORS.items():
        model = load_model(name)
        validate_common(model, minimum_boxes)
        critical[name] = validator(model)
        models[name] = model
        print(f"MODEL {name}: body={len(model.body)}, world={len(model.world_boxes)}, contract=PASS")

    validate_textures()

    for name, (_, validator) in VALIDATORS.items():
        mutation_probe(models[name], validator, critical[name])
    print(f"MUTATION deletion_probes={len(VALIDATORS)}")
    print("PASS tier IV loadout detail contract")


if __name__ == "__main__":
    main()

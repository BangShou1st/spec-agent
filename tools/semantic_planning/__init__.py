"""R4 semantic planning diagnostic package (diagnostic-only, stdlib-only).

Never imported by production code. Implements planning-state.v2 schema
(C1), G1-G5 reference goal, C2 cross-checks, planning-mapping.v2,
R4 prompt, and synthetic calibration fixtures.
"""

SCHEMA_VERSION = "planning-state.v2"
MAPPING_VERSION = "planning-mapping.v2"
REFERENCE_GOAL_VERSION = "r4-goalref.v1"

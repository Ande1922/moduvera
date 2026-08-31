import importlib.util
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "reference_blackbox", ROOT / "scripts/reference-product/blackbox.py"
)
BLACKBOX = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(BLACKBOX)


def test_public_order_fulfillment_reference_product():
    BLACKBOX.acceptance()


def response(status: int, content_type: str, body: dict, **headers):
    return BLACKBOX.Response(
        status,
        {"Content-Type": content_type, **headers},
        json.dumps(body).encode("utf-8"),
    )


def test_problem_contract_requires_rfc_9457_shape_and_correlation():
    problem = response(
        400,
        "application/problem+json",
        {
            "type": "urn:problem:request.validation-failed",
            "title": "Request validation failed",
            "status": 400,
            "detail": "One or more request fields are invalid",
            "code": "request.validation-failed",
            "correlationId": "contract-correlation",
        },
        **{"X-Correlation-Id": "contract-correlation"},
    )

    BLACKBOX.expect_problem(
        problem,
        400,
        "request.validation-failed",
        "contract validation",
        correlation="contract-correlation",
    )


def test_success_contract_is_native_and_location_uses_external_order_path():
    created = response(
        201,
        "application/json",
        {"orderId": "42", "status": "PENDING_STOCK"},
        **{
            "Location": "/api/order/v1/orders/42",
            "X-Correlation-Id": "contract-create",
        },
    )

    BLACKBOX.expect_native_json(created, "contract create")
    BLACKBOX.expect_correlation(created, "contract-create", "contract create")
    BLACKBOX.expect_order_location(created, "42", "contract create")

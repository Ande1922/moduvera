import importlib.util
import json
from pathlib import Path
import uuid

import pytest


ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location(
    "reference_blackbox", ROOT / "verification/reference-product/harness/blackbox.py"
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
    generated = str(uuid.uuid4())
    problem = response(
        400,
        "application/problem+json",
        {
            "type": "urn:problem:request.validation-failed",
            "title": "Request validation failed",
            "status": 400,
            "detail": "One or more request fields are invalid",
            "code": "request.validation-failed",
            "correlationId": generated,
        },
        **{"X-Correlation-Id": generated},
    )

    BLACKBOX.expect_problem(
        problem,
        400,
        "request.validation-failed",
        "contract validation",
        correlation="contract-correlation",
    )


def test_success_contract_is_native_and_location_uses_external_order_path():
    generated = str(uuid.uuid4())
    created = response(
        201,
        "application/json",
        {"orderId": "42", "status": "PENDING_STOCK"},
        **{
            "Location": "/api/order/v1/orders/42",
            "X-Correlation-Id": generated,
        },
    )

    BLACKBOX.expect_native_json(created, "contract create")
    assert BLACKBOX.expect_public_correlation(created, "contract-create", "contract create") == generated
    BLACKBOX.expect_order_location(created, "42", "contract create")


@pytest.mark.parametrize("returned", [None, "caller-correlation", "00000000-0000-1000-8000-000000000001", "0" * 32])
def test_public_correlation_rejects_missing_reused_or_non_v4_values(returned):
    with pytest.raises(AssertionError):
        BLACKBOX.expect_public_correlation(
            response(200, "application/json", {}, **{"X-Correlation-Id": returned}),
            "caller-correlation", "public correlation",
        )


def test_public_correlation_rejects_reused_valid_caller_uuid():
    caller = str(uuid.uuid4())
    with pytest.raises(AssertionError):
        BLACKBOX.expect_public_correlation(
            response(200, "application/json", {}, **{"X-Correlation-Id": caller}),
            caller, "public correlation",
        )


def test_transparent_problem_keeps_a_distinct_downstream_correlation():
    generated, downstream = str(uuid.uuid4()), str(uuid.uuid4())
    problem = response(401, "application/problem+json", {
        "type": "urn:problem:identity.invalid-credentials", "title": "Invalid credentials",
        "status": 401, "detail": "Login rejected", "code": "identity.invalid-credentials",
        "correlationId": downstream,
    }, **{"X-Correlation-Id": generated})
    BLACKBOX.expect_problem(problem, 401, "identity.invalid-credentials", "transparent login",
                            correlation="caller-correlation", transparent_problem=True)
    with pytest.raises(AssertionError):
        BLACKBOX.expect_problem(problem, 401, "identity.invalid-credentials", "owned problem",
                                correlation="caller-correlation")

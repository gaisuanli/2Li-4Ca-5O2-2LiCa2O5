import unittest

import app


class AiAdapterTests(unittest.TestCase):
    def test_health_never_claims_validated_model(self):
        payload = app.health_payload()
        self.assertEqual(payload["status"], "UP")
        self.assertIn(payload["mode"], {"DISABLED", "DEMO_SAMPLE", "MODEL"})
        self.assertIn("验证", payload["notice"])

    def test_infer_is_disabled_by_default(self):
        if not app.ENABLE_MODEL and not app.DEMO_MODE:
            status, payload = app.infer({})
            self.assertEqual(status, 503)
            self.assertEqual(payload["code"], "MODEL_DISABLED")


if __name__ == "__main__":
    unittest.main()
